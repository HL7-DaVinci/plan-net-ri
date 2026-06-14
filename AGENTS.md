# AGENTS.md

This file provides guidance to AI coding agents working in this repository.

## What this is

Da Vinci PDex Plan-Net Reference Implementation: a HAPI FHIR R4 JPA server (based on the hapi-fhir-jpaserver-starter, parent pom `ca.uhn.hapi.fhir:hapi-fhir`) plus an experimental Directory Crawler. The crawler is a React SPA served at `/crawler` and a Spring REST API under `/api` that runs scheduled crawls of Plan-Net directories, diffs results between runs, and publishes Bulk Data manifests.

## Commands

```bash
# Full dev stack (FHIR server :8080 + frontend dev server :3000), from repo root
bun install --frozen-lockfile   # bun is preferred; only bun.lock is committed (no npm ci)
nx serve                        # or: bun run serve

# Server only
mvn spring-boot:run             # FHIR at /fhir, crawler API at /api; embedded H2

# Backend tests
mvn test                                          # unit tests only; *IT classes are excluded
mvn test -Dtest='CrawlServiceTest'                # single class
mvn test -Dtest='DirectoryCrawlerSelfCrawlIT#selfCrawlProducesServedManifest'  # single IT method

# Formatting (enforced in CI; config inherited from the HAPI parent pom)
mvn spotless:check
mvn spotless:apply

# Frontend (run inside frontend/)
bun run dev      # vite on :3000
bun run build    # vite build && tsc type check
bun run test     # vitest
bun run check    # biome lint + format
```

Test classes named `*IT` (e.g. `DirectoryCrawlerSelfCrawlIT`) boot the full server (~40s) and are excluded from the normal build: failsafe excludes `**/*IT.java` and its integration-test goal is commented out. They only run when named explicitly with `-Dtest`. After running one, check `target/surefire-reports/` for results since `-q` suppresses the summary.

Crawler unit tests use hand-rolled `java.lang.reflect.Proxy` fakes and anonymous subclass stubs, not Mockito; follow that pattern. They live in the `org.hl7.davinci.api` package even when the class under test is in a subpackage.

## Architecture

Two Java trees under `src/main/java`:

- `ca/uhn/fhir/jpa/starter/` - the HAPI starter server, mostly upstream code. Configured via `src/main/resources/application.yaml` (`hapi.fhir.*`).
- `org/hl7/davinci/api/` - the Directory Crawler, project-specific. Configured via the `api.*` yaml section bound to `ApiProperties`.

### Crawler configuration

`ApiProperties` is a `@Component` (bean name `apiProperties`) with `@ConfigurationProperties(prefix = "api")`; it is NOT registered via `@EnableConfigurationProperties`, and `CrawlScheduler` reads the poll interval through SpEL (`#{@apiProperties.pollerIntervalMs}`), so renaming the bean breaks scheduling. Env vars bind via Spring relaxed binding (`API_PUBLIC_BASE_URL`, `API_REQUEST_TIMEOUT_MS` default 180s, `API_PAGE_DELAY_MS` politeness pause between page fetches, `API_RESUME_CRAWLS_ON_STARTUP` default false, etc.). Exception: the UI server list is `APP_FHIR_SERVERS` by deliberate choice; the yaml bridges it (`api.fhir-servers: ${APP_FHIR_SERVERS:}`) into `ApiProperties`. `ConfigController` serves these to the SPA as a generated `/crawler/config.js` setting `window.APP_CONFIG`.

### Crawler persistence isolation (critical)

Crawler entities (`org.hl7.davinci.api.entity`) and repositories live in a dedicated persistence unit `CRAWLER_PU` (`CrawlerPersistenceConfig`) over the same datasource as HAPI, with `hbm2ddl.auto=update`. Any `@Transactional` touching crawler repositories MUST name the manager explicitly: `@Transactional("crawlerTransactionManager")`. A bare `@Transactional` binds to HAPI's primary transaction manager and fails. Custom `@Entity`/`@Repository` classes are not auto-discovered by HAPI; they need this isolated unit.

Do not put scannable test `@Configuration` classes under `org.hl7.davinci`; `hapi.fhir.custom-bean-packages` scans that package and they will poison full-application boots.

### Crawler domain model

Entities are linked by plain String ids, no DB foreign keys and no JPA cascades; deletes are orchestrated manually (`JobDeletionService`, which cancels any in-flight run first and then cascades manifests, steps, runs, job).

- `CrawlJob` - schedule (Spring cron; blank = manual-only, skipped by the scheduler), `enabled` (pause = enabled=false), `running` (display flag; goes stale after a crash, cleared at startup by `CrawlStartupRecovery` only when `api.resume-crawls-on-startup`)
- `CrawlRun` - per-server, per-batch history row (`jobId`, `batchId`); added/updated/deleted counts, `records` (fetched this run, NOT directory size), `totalAfter` (aggregate size after the run), `serverTimeAtStart`
- `CrawlStep` - play-by-play timeline, linked by `batchId` only (no jobId); `errorBody` holds the raw failed-response body (capped at 100k chars)
- `ManifestRecord` - one retained snapshot: DB row + `storageDir` pointing at gzipped NDJSON files on disk (`api.storage-path`; written as `{Type}.ndjson.gz` and decompressed on serve, so the wire format stays plain NDJSON)
- `CrawlResource` - current aggregate state keyed `serverKey|Type/id`. Server-scoped, NOT job-scoped: shared by all jobs targeting that server, so never cascade-delete it with a job. Bodies in the DB are stored compressed via `ResourceJsonCodec` (`gz:` + Base64(gzip); values without the prefix are legacy plaintext and must keep decoding in place). This per-row DB compression is separate from the on-disk `.ndjson.gz` snapshot files. The NDJSON export keysets on the PRIMARY KEY (`resource_key > ? ORDER BY resource_key`, bounded per server by the `serverKey|` key prefix) so H2 always range-scans the PK index; a serverKey-equality keyset instead let the optimizer pick the single-column serverKey index and re-sort the whole server set on every page (and `hbm2ddl.auto=update` does not reliably add a NEW index to an already-populated table, so the export must not depend on one being created after the fact). `CrawlResource implements Persistable<String>` (transient `isNew` flag) so diff-classified added rows INSERT without the per-row SELECT that an assigned-id `saveAll` otherwise forces.

### Crawl flow

`CrawlScheduler.poll()` (every `api.poller-interval-ms`) finds `enabled=true` jobs due by `nextRunAt` and calls `CrawlService.triggerAsync`. The in-memory `inFlight` map (jobId to batchId) is the real single-flight guard and is surfaced as `JobResponse.currentBatchId`; `CrawlService.cancelJob` cooperatively stops a run for force-delete (interrupts the worker, suppresses all further writes via the `cancelled` set). The crawl runs one of three strategies in `FhirCrawlClient` (SEARCH, BULK_EXPORT, HISTORY), then:

1. Persist (streaming): each strategy takes a `Consumer<List<FetchedResource>>` resourceSink wired to `session::accept` on a `CrawlPersistenceService.SnapshotSession` (`openSession`), so resources are fed in batches as they are fetched and the full set is never held in memory; `FhirCrawlClient` buffers via a `BatchEmitter` (flushes every 1000). Per batch, `DiffUtil.computeDiff` classifies against the `CrawlResource` version index (added when key absent; unchanged when versionId OR lastUpdated matches; updated otherwise); only changed rows are upserted and missing/resolved keys deleted, in chunked per-transaction writes (1000 rows each), never wipe-and-rewrite (that bloats H2's MVStore). `finishFullSnapshot()`/`finishIncremental()` return `PersistCounts` (added/updated/deleted plus the post-run `total`). Thin `persistFullSnapshot`/`persistIncremental` wrappers (open a session, feed one batch, finish) remain for callers holding the whole set in memory (tests).
2. Manifest: when all servers complete (and the run was not cancelled), `ManifestService.createManifest` writes the NDJSON snapshot via `NdjsonExportService` (keyset-paginated, one gzipped `{Type}.ndjson.gz` per type) and prunes to `api.retention-per-job`; `render` derives output[] from the files on disk, counting lines per type. Runs and steps have no retention (deliberate, deferred).

Incremental windows: the next run's `_since` is the previous COMPLETED run's `serverTimeAtStart` (captured at crawl start, so windows intentionally overlap; the diff absorbs the overlap). History-bundle deletions are identified by `request.method == DELETE` (the spec marker, which wins over a contradictory resource body); a 4xx on the deletion scan degrades gracefully to `historySupported=false`, a 5xx fails the run with a logged failure step.

Outbound HTTP: timeouts from `api.request-timeout-ms` (applied factory-wide on the shared FhirContext plus the bulk HttpClient); `FhirCrawlClient.withRetry` retries transient failures (connection failures/timeouts, 429, 502/503/504) up to 3 attempts with backoff, honoring a 429's `Retry-After` (clamped to 60s); `api.page-delay-ms` adds an optional pause between page fetches. Paging strictly follows `Bundle.link[next]` (spec-conformant; never construct page URLs).

### Events: persisted steps vs transient progress

`StepEvent` has two kinds. Persisted steps (`info`/`request`/`failure`) are written to `crawl_step` with CONTIGUOUS seq numbers and broadcast over SSE as `step` events; failure steps carry the raw response body for the UI's Response viewer. Transient progress markers (`StepEvent.progress`) are broadcast-only as `progress` events, never persisted, and MUST NOT consume a seq number (the sink passes 0). `CrawlEventService` remembers the latest progress marker per active batch and replays it to late SSE subscribers.

Every step is also mirrored to SLF4J (`CrawlService.logStep`: info/request to INFO, failure to ERROR, transient progress to DEBUG) with `batchId`/`serverKey` in MDC (logback `CRAWLER_CONSOLE` appender, `org.hl7.davinci.api` only). The long fetch/persist/export loops add throttled INFO heartbeats (every N pages/records), so a headless run is followable and a stalled run is distinguishable from a healthy one.

### REST + frontend integration

- Controllers in `org.hl7.davinci.api.web`: `ApiJobController` (jobs CRUD, pause/resume, run, paginated runs; DELETE force-cancels running jobs and always returns 204), `ApiManifestController`, `CrawlEventController` (step replay + SSE live stream per batchId), `ConfigController` (`/crawler/config.js`; annotated controllers outrank the static resource handler, so it overrides the bundled placeholder).
- `CrawlerFrontendConfig` serves the built SPA from `classpath:/static/crawler/` at `/crawler` with an SPA fallback to index.html. The Docker build copies `frontend/dist/` there; locally the frontend is a separate Vite dev server at the web root.
- CORS is wide open on `/api/**` only (`ApiConfig`), which is what lets the :3000 dev frontend call the :8080 API.

### Frontend (`frontend/`)

React 19 + TanStack Router/Query, Tailwind 4, shadcn-style primitives in `src/components/ui/`, biome for lint/format, vitest for tests. It is a Bun/Nx workspace member of the root package.json. Key files: `src/lib/api/client.ts` (typed fetch wrapper for all `/api` endpoints), `src/hooks/use-api.ts` (React Query hooks + cache invalidation; jobs poll 3s while running, 15s idle), `src/lib/fhir-config.ts` (runtime config: `window.APP_CONFIG` first, then `VITE_*` build-time vars, then defaults). Vite `base` is `/crawler/` for builds, `/` for dev.

UI conventions: the live play-by-play panel is selection-driven (shows only the selected job's active run via `currentBatchId`; completed runs are reviewed in run history); manual-only jobs (no cron) surface no scheduling affordances (no enabled checkbox, pause/resume, or paused badge); raw JSON is always shown through the shared `JsonViewerDialog` (which renders string data verbatim for non-JSON payloads).

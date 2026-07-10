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
mvn test -Dtest='BulkPublishIT#bulkPublishReflectsCreateThenUpdateWithGzipNegotiationAndConditionalGet'  # $bulk-publish IT

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

Package separation within `org.hl7.davinci` (all covered by the `custom-bean-packages` scan): `api.*` is regular Spring code specific to the `/api/*` endpoints; `provider` holds HAPI operation providers tied into the FHIR servlet; `publish` is the bulk-publish engine (service, manifest model, and `PublishProperties` bound from the top-level `publish.*` yaml section); `common` holds code shared across `api`, `provider`, and `publish`: `BaseProvider` (HAPI plain-provider registration), `PathUtils.deleteRecursively` (recursive directory delete, swallowing per-file errors), `NdjsonFiles` (the `SAFE_FILE` filename pattern and a gzip writer factory for `.ndjson.gz` output), and `PlanNetTypes.TYPES` (the 8 published/crawled resource type names).

### Crawler configuration

`ApiProperties` is a `@Component` (bean name `apiProperties`) with `@ConfigurationProperties(prefix = "api")`; it is NOT registered via `@EnableConfigurationProperties`, and `CrawlScheduler` reads the poll interval through SpEL (`#{@apiProperties.pollerIntervalMs}`), so renaming the bean breaks scheduling. Env vars bind via Spring relaxed binding (`API_PUBLIC_BASE_URL`, `API_REQUEST_TIMEOUT_MS` default 180s, `API_PAGE_DELAY_MS` politeness pause between page fetches, `API_RESUME_CRAWLS_ON_STARTUP` default false, etc.). Exception: the UI server list is `APP_FHIR_SERVERS` by deliberate choice; the yaml bridges it (`api.fhir-servers: ${APP_FHIR_SERVERS:}`) into `ApiProperties`. `ConfigController` serves these to the SPA as a generated `/crawler/config.js` setting `window.APP_CONFIG`.

### Crawler persistence isolation (critical)

Crawler entities (`org.hl7.davinci.api.entity`) and repositories live in a dedicated persistence unit `CRAWLER_PU` (`CrawlerPersistenceConfig`) over the same datasource as HAPI, with `hbm2ddl.auto=update`. Any `@Transactional` touching crawler repositories MUST name the manager explicitly: `@Transactional("crawlerTransactionManager")`. A bare `@Transactional` binds to HAPI's primary transaction manager and fails. Custom `@Entity`/`@Repository` classes are not auto-discovered by HAPI; they need this isolated unit.

Do not put scannable test `@Configuration` classes under `org.hl7.davinci`; `hapi.fhir.custom-bean-packages` scans that package and they will poison full-application boots.

### Crawler domain model

Entities are linked by plain String ids, no DB foreign keys and no JPA cascades; deletes are orchestrated manually (`JobDeletionService`, which cancels any in-flight run first, then cascades manifests, steps, runs, job, and finally clears `crawl_resource` for any of the job's servers that no remaining job targets, via a bulk `@Modifying` `deleteByServerKey` that does no entity loading).

- `CrawlJob` - schedule (Spring cron; blank = manual-only, skipped by the scheduler), `enabled` (pause = enabled=false), `running` (display flag; goes stale after a crash, cleared at startup by `CrawlStartupRecovery` only when `api.resume-crawls-on-startup`)
- `CrawlRun` - per-server, per-batch history row (`jobId`, `batchId`); added/updated/deleted counts, `records` (fetched this run, NOT directory size), `totalAfter` (aggregate size after the run), `serverTimeAtStart`
- `CrawlStep` - play-by-play timeline, linked by `batchId` only (no jobId); `errorBody` holds the raw failed-response body (capped at 100k chars); `message`/`url` and `CrawlRun.error` are clipped to 10k (`StepEvent.clip`) since exception messages can embed whole response bodies
- `ManifestRecord` - one retained snapshot: DB row + `storageDir` pointing at gzipped NDJSON files on disk (`api.storage-path`; written as `{Type}.ndjson.gz` and decompressed on serve, so the wire format stays plain NDJSON). Snapshot files are written BEFORE the row is saved, so a crash in between strands a directory; `ManifestService.sweepOrphanedSnapshots` (ApplicationReadyEvent) deletes any dir under `api.storage-path` whose name matches no manifest id
- `CrawlResource` - current aggregate state keyed `serverKey|Type/id`. Server-scoped, NOT job-scoped: shared by all jobs targeting that server, so it is never cascade-deleted with a single job; `JobDeletionService` clears a server's rows only when the LAST job targeting it is deleted (`serverKeys(job)` minus the server keys of all remaining jobs). Bodies in the DB are stored as raw gzip bytes at max compression in a `resource_json` column mapped `@JdbcTypeCode(LONGVARBINARY)` with `length = Length.LONG32` (which H2 renders as `BINARY LARGE OBJECT`; without the explicit length Hibernate defaults to 32600 bytes and large payer resources overflow it) via `ResourceJsonCodec` (no base64 wrapper, so no ~33% inflation; the DB is reset on each start in the hosted/ephemeral case, so there is no legacy plaintext decode path). This per-row DB compression is separate from the on-disk `.ndjson.gz` snapshot files. The row is deliberately minimal: the PK (`resource_key`) plus only `resourceType`, `versionId`, `lastUpdated`, and the gzip body; there are NO serverKey/serverLabel/resId columns and NO secondary index (they duplicated the key prefix at ~1.4GB per 5M rows). EVERY per-server access is therefore a PK-range operation on the exclusive prefix range (`key > 'serverKey|' and key < 'serverKey}'`, '}' being the character after '|'): the NDJSON export keyset (`resource_key > ? ORDER BY resource_key`), `countByServerKey`/`deleteByServerKey` (repository default methods over `countByKeyRange`/`deleteByKeyRange`), and the stats `countDistinctServers` (distinct key prefix via substring/locate). This also means H2 always range-scans the PK index; a serverKey-equality predicate would need a secondary index, and `hbm2ddl.auto=update` does not reliably add a NEW index to an already-populated table. `CrawlResource implements Persistable<String>` (transient `isNew` flag) so diff-classified added rows INSERT without the per-row SELECT that an assigned-id `saveAll` otherwise forces.

### Crawl flow

`CrawlScheduler.poll()` (every `api.poller-interval-ms`) finds `enabled=true` jobs due by `nextRunAt` and calls `CrawlService.triggerAsync`. The in-memory `inFlight` map (jobId to batchId) is the real single-flight guard and is surfaced as `JobResponse.currentBatchId`; `CrawlService.cancelJob` cooperatively stops a run for force-delete (interrupts the worker, suppresses all further writes via the `cancelled` set). The crawl runs one of four strategies in `FhirCrawlClient` (SEARCH page-link paging, SEARCH_LAST_UPDATED which pages by an advancing `_lastUpdated` watermark (re-querying `ge{watermark}` instead of following `link[next]`), falling back to `link[next]` to page through any cluster of resources sharing one `_lastUpdated` instant larger than the page size so none are dropped, BULK_EXPORT, HISTORY), then:

1. Persist (streaming): each strategy takes a `Consumer<List<FetchedResource>>` resourceSink wired to `session::accept` on a `CrawlPersistenceService.SnapshotSession` (`openSession`), so resources are fed in batches as they are fetched and the full set is never held in memory; `FhirCrawlClient` buffers via a `BatchEmitter` (flushes every 1000). Per batch, classification is O(batch), not O(directory): the session queries prior versions for only that batch's keys (`findVersionViewByKeys`, a PK-index `IN` lookup; no whole-server map is ever loaded) and feeds them to `DiffUtil.computeDiff` (added when key absent; unchanged when versionId OR lastUpdated matches; updated otherwise). Only changed rows are upserted, in chunked per-transaction writes (1000 rows each), never wipe-and-rewrite (that bloats H2's MVStore). A "handled this run" set (`seenKeys`, or a bounded `DEDUP_WINDOW` recent-key window on a first crawl) is consulted BEFORE the IN query and excludes already-handled keys, so an intra-run re-fetch (the SEARCH_LAST_UPDATED boundary overlap) is classified against what THIS run wrote rather than reading-its-own-writes from the live DB; this keeps the added/updated counts identical to a start-of-run snapshot. `finishIncremental` detects deletions from the explicit `_history` list, existence-checked via a small `IN` over just the deletion keys. `finishFullSnapshot` (only BULK_EXPORT reaches it with a populated DB; SEARCH/HISTORY full always start empty on the ephemeral deployment) streams the server's primary keys at finish (`findKeysByKeyGreaterThanOrderByKeyAsc`, the same PK-range-scan keyset pattern as the NDJSON export, `serverKey|`-prefix bounded) and deletes any key absent from `seenKeys`; a first crawl (`startCount == 0`) skips the scan since there is nothing prior to delete. `total = startCount + added - deleted` where `startCount = countByServerKey` is captured once in the ctor. `finishFullSnapshot()`/`finishIncremental()` return `PersistCounts` (added/updated/deleted plus the post-run `total`). Thin `persistFullSnapshot`/`persistIncremental` wrappers (open a session, feed one batch, finish) remain for callers holding the whole set in memory (tests).
2. Manifest: when all servers complete (and the run was not cancelled), `ManifestService.createManifest` writes the NDJSON snapshot via `NdjsonExportService` (keyset-paginated, one gzipped `{Type}.ndjson.gz` per type) and prunes to `api.retention-per-job`; `render` derives output[] from the files on disk, counting lines per type. Runs and steps have no retention (deliberate, deferred).

Incremental windows: the next run's `_since` is the previous COMPLETED run's `serverTimeAtStart` (captured at crawl start, so windows intentionally overlap; the diff absorbs the overlap). History-bundle deletions are identified by `request.method == DELETE` (the spec marker, which wins over a contradictory resource body); a 4xx on the deletion scan degrades gracefully to `historySupported=false`, a 5xx fails the run with a logged failure step.

Outbound HTTP: timeouts from `api.request-timeout-ms` (applied factory-wide on the shared FhirContext plus the bulk HttpClient); `FhirCrawlClient.withRetry` retries transient failures (connection failures/timeouts, 429, and any 5xx; crawl requests are idempotent GETs, and servers return 500 for internal timeouts that resolve on retry, e.g. HAPI's 60s search coordinator limit) up to 3 attempts with backoff, honoring a 429's `Retry-After` (clamped to 60s); `api.page-delay-ms` adds an optional pause between page fetches. Paging strictly follows `Bundle.link[next]` (spec-conformant; never construct page URLs). BULK_EXPORT output files are STREAMED (`BodyHandlers.ofInputStream` + line reader into the `BatchEmitter`, `streamExportFile`), never buffered whole; a multi-million-row NDJSON file as one `ofString` body was a guaranteed OOM.

### Events: persisted steps vs transient progress

`StepEvent` has two kinds. Persisted steps (`info`/`request`/`failure`) are written to `crawl_step` with CONTIGUOUS seq numbers and broadcast over SSE as `step` events; failure steps carry the raw response body for the UI's Response viewer. Transient progress markers (`StepEvent.progress`) are broadcast-only as `progress` events, never persisted, and MUST NOT consume a seq number (the sink passes 0). `CrawlEventService` remembers the latest progress marker per active batch and replays it to late SSE subscribers.

Every step is also mirrored to SLF4J (`CrawlService.logStep`: info/request to INFO, failure to ERROR, transient progress to DEBUG) with `batchId`/`serverKey` in MDC (logback `CRAWLER_CONSOLE` appender, `org.hl7.davinci.api` only). The long fetch/persist/export loops add throttled INFO heartbeats (every N pages/records), so a headless run is followable and a stalled run is distinguishable from a healthy one.

### REST + frontend integration

- Controllers in `org.hl7.davinci.api.web`: `ApiJobController` (jobs CRUD, pause/resume, run, paginated runs; DELETE force-cancels running jobs and always returns 204), `ApiManifestController`, `ApiStatsController` (`GET /api/stats`; overall cross-job totals via `StatsService.computeOverall`), `CrawlEventController` (step replay + SSE live stream per batchId), `ConfigController` (`/crawler/config.js`; annotated controllers outrank the static resource handler, so it overrides the bundled placeholder), `PublishSnapshotsController` (`GET /api/publish/snapshots`; retained snapshot metas for the UI, newest first, unreadable metas skipped).
- `CrawlerFrontendConfig` serves the built SPA from `classpath:/static/crawler/` at `/crawler` with an SPA fallback to index.html. The Docker build copies `frontend/dist/` there; locally the frontend is a separate Vite dev server at the web root.
- CORS is wide open on `/api/**` only (`ApiConfig`), which is what lets the :3000 dev frontend call the :8080 API.

### Bulk Data $bulk-publish (self-publish)

`PublishService` (`org.hl7.davinci.publish`) periodically publishes a snapshot of THIS server's own Plan-Net data (`PlanNetTypes.TYPES`) via the FHIR Bulk Data `$bulk-publish` operation. State is filesystem-only under `publish.storage-path`: a `current` pointer, per-snapshot `meta.json`, and gzipped `{Type}.ndjson.gz` files. Fully isolated from the crawler: no DB entity, no `crawlerTransactionManager`, no `crawl_resource` involvement, its own storage root.

Snapshots advance by replaying `HFJ_RES_VER` change windows (`dao.history`) bounded by a write frontier (`WriteFrontier`: an in-flight write registry fed by JPA storage interceptors, held back from the wall clock by a grace lag). Per-id winners are chosen by numeric `versionId`, never timestamp (timestamps only bound which rows a window considers). A strictly-monotone merge (`SnapshotFileMerger`) updates files, so replaying any window twice never regresses one. Bootstrap (first publish, or reset-on-startup) is a bounded scan, a frontier wait, then a window repair over what changed during it. Mechanism detail: `.claude/specs/bulk-publish-v2-design.md`.

Trap: the `WriteFrontier` interceptor must be registered on the JPA `IInterceptorService` bean (`WriteFrontierRegistrar`), never on the `RestfulServer` - a `SystemRequestDetails` write (`$import`, a system transaction) carries a no-op request broadcaster and never reaches a server-registered interceptor.

Trap: `StorageSettingsGuard` asserts `resource_dbhistory_enabled=true`, `expunge_enabled=false` (the yaml now ships this explicitly; the starter default is `true`), `delete_expunge_enabled=false`, update-with-history-rewrite disabled (no starter yaml key; asserted on the `JpaStorageSettings` bean), tag storage mode `VERSIONED`, and partitioning disabled; a violation ERROR-logs once and skips publishing rather than corrupting the feed. The whole feed assumes `HFJ_RES_VER` is append-only.

Bean name `publishProperties` is referenced by the scheduler SpEL like `apiProperties`; renaming breaks scheduling. Config (`PublishProperties`, top-level `publish.*`, not under `api.*`): `enabled`, `interval-ms` (also drives the manifest's `updateCadence`), `frontier-grace-ms` (frontier's lag floor), `overlap-ms` (how far a steady tick's window reaches back), `retention` + `grace-period-ms` (count window plus a time floor), `export-page-size` (bootstrap scan page size), `storage-path`, `reset-on-startup`. Relaxed env binding, `PUBLISH_*`.

Endpoint: `GET /fhir/$bulk-publish` via `BulkPublishProvider` (extends `BaseProvider`, self-registers; `manualResponse`, system-level). Manifest `Content-Type` is `application/json`, NOT `application/fhir+json`; `ETag` is the SHA-256 of the exact rendered bytes, not the snapshot id, honoring `If-None-Match`/304, `Cache-Control: public, max-age=10`; 503 + `OperationOutcome` before the first snapshot exists. Files at `GET /api/publish/{snapshotId}/{Type}.ndjson` via `PublishFileController`: `Content-Type: application/fhir+ndjson` always, stored gzip streamed as-is with `Content-Encoding: gzip` only when `Accept-Encoding` allows it (else decompressed), `Vary: Accept-Encoding`, 406 + `OperationOutcome` for a non-ndjson `Accept`, `Cache-Control` immutable and long-lived.

An unchanged type carries its `FileMeta` forward with the snapshot id that still OWNS the file, so its URL stays byte-identical across snapshots; a type whose merge empties keeps no file and no output entry. Retention prune keeps the newest `retention` snapshots by `transactionTime` (plus whichever is current) but spares any directory still referenced by a retained snapshot's `FileMeta`, and never prunes below `grace-period-ms` regardless of the count.

Deletions are conveyed by absence in the snapshot, not a `deleted[]` array. The crawler UI's Bulk Publish page and `GET /api/publish/snapshots` (`PublishSnapshotsController`) are unchanged consumers of this feed.

Acceptance proof: `BulkPublishRaceIT` (snapshot-vs-oracle diff under concurrent writer storms). Everything deeper - tick/bootstrap mechanics, merge internals, exportType paging - is in the design doc.

### Frontend (`frontend/`)

React 19 + TanStack Router/Query, Tailwind 4, shadcn-style primitives in `src/components/ui/`, biome for lint/format, vitest for tests. It is a Bun/Nx workspace member of the root package.json. Key files: `src/lib/api/client.ts` (typed fetch wrapper for all `/api` endpoints), `src/hooks/use-api.ts` (React Query hooks + cache invalidation; jobs poll 3s while running, 15s idle), `src/lib/fhir-config.ts` (runtime config: `window.APP_CONFIG` first, then `VITE_*` build-time vars, then defaults), `src/lib/publish/manifest.ts` (manifest types + pure helpers: ISO-8601 cadence parsing, curl builder, exported-vs-reused classification), `src/hooks/use-bulk-publish.ts` (conditional-GET watcher + snapshots query). Vite `base` is `/crawler/` for builds, `/` for dev.

UI conventions: the live play-by-play panel is selection-driven (shows only the selected job's active run via `currentBatchId`; completed runs are reviewed in run history); manual-only jobs (no cron) surface no scheduling affordances (no enabled checkbox, pause/resume, or paused badge); raw JSON is always shown through the shared `JsonViewerDialog` (which renders string data verbatim for non-JSON payloads).

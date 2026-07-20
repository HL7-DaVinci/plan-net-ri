# AGENTS.md

This file provides guidance to AI coding agents working in this repository. It covers orientation, conventions, and traps that cannot be discovered by reading a single file; the code is the source of truth for mechanism detail.

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

Test classes named `*IT` (e.g. `DirectoryCrawlerSelfCrawlIT`, `BulkPublishIT`) boot the full server (~40s) and only run when named explicitly with `-Dtest`. After running one, check `target/surefire-reports/` since `-q` suppresses the summary.

Crawler unit tests use hand-rolled `java.lang.reflect.Proxy` fakes and anonymous subclass stubs, not Mockito; follow that pattern. They live in the `org.hl7.davinci.api` package even when the class under test is in a subpackage. `FhirCrawlClientTest`'s default `props()` fixture pins `crawlConcurrency` to 1 (exact-serial path); tests asserting parallel behavior override it.

## Architecture

Two Java trees under `src/main/java`:

- `ca/uhn/fhir/jpa/starter/` - the HAPI starter server, mostly upstream code. Configured via `src/main/resources/application.yaml` (`hapi.fhir.*`).
- `org/hl7/davinci/api/` - the Directory Crawler, project-specific. Configured via the `api.*` yaml section bound to `ApiProperties`.

Within `org.hl7.davinci` (all covered by the `custom-bean-packages` scan): `api.*` is the crawler; `provider` holds HAPI operation providers (they extend `common.BaseProvider`, which self-registers on the `RestfulServer`); `publish` is the bulk-publish engine (`publish.*` yaml, `PublishProperties`); `common` holds shared code (`PathUtils`, `NdjsonFiles`, `PlanNetTypes.TYPES` - the 8 published/crawled resource type names).

### Traps (read before touching anything)

- Any `@Transactional` touching crawler repositories MUST name the manager: `@Transactional("crawlerTransactionManager")`. Crawler entities/repositories live in a dedicated persistence unit `CRAWLER_PU` (`CrawlerPersistenceConfig`, `hbm2ddl.auto=update`), sharing HAPI's datasource unless `api.datasource.url` points them at their own database; a bare `@Transactional` binds to HAPI's primary manager and fails.
- Do not put scannable test `@Configuration` classes under `org.hl7.davinci`; `custom-bean-packages` scans that package and they poison full-application boots.
- Bean names `apiProperties` and `publishProperties` are referenced by scheduler SpEL (`#{@apiProperties.pollerIntervalMs}`); renaming either breaks scheduling.
- `crawl_resource`'s composite primary key `(server_id, type_id, uid)` is the only index; every per-server or per-type access must stay on its leftmost prefix (see `CrawlResourceRepository`). Field and column names are chosen so their alphabetical order matches the desired index order (Hibernate orders composite PK columns alphabetically by attribute name, not declaration order) - do not rename `uid` to `remoteId`/`resourceId`. `hbm2ddl.auto=update` does not reliably add a new index to a populated table.
- The gzip body column is `@JdbcTypeCode(LONGVARBINARY)` with `length = Length.LONG32`; without the explicit length Hibernate caps it at 32600 bytes and large resources overflow.
- Paging strictly follows `Bundle.link[next]`; never construct page URLs. Bulk-export output files are streamed line-by-line, never buffered whole (one multi-million-row file as a single string body is a guaranteed OOM).
- `API_STORAGE_PATH` and `PUBLISH_STORAGE_PATH` must be persistent volumes on a persistent-DB deployment (the Docker image defaults them to `/data/crawler` and `/data/publish`; docker-compose mounts named volumes), or restarts strand manifest rows whose files are gone and wipe the publish feed. Local dev defaults stay under `./target/`.
- The `WriteFrontier` interceptor must be registered on the JPA `IInterceptorService` bean (`WriteFrontierRegistrar`), never on the `RestfulServer` - system-request writes (`$import`) never reach a server-registered interceptor.
- Transient-failure classification is by HTTP status class and idempotency only (429/5xx/connection failures), never by vendor error strings.

### Crawler configuration

`ApiProperties` binds `api.*`; env vars via relaxed binding (`API_*`): `API_STORAGE_PATH` (snapshot file root), `API_PUBLIC_BASE_URL`, `API_REQUEST_TIMEOUT_MS` (default 180s), `API_PAGE_DELAY_MS` (politeness pause, paced per chain), `API_CRAWL_CONCURRENCY` (default 4, floor 1), `API_RESUME_CRAWLS_ON_STARTUP` (default true), `API_DATASOURCE_URL` (+`_USERNAME`/`_PASSWORD`: dedicated crawler database, SQLite or Postgres; SQLite urls get WAL mode automatically; blank shares HAPI's datasource, but H2 amplifies bulk crawl writes ~30x). Exception: the UI server list is `APP_FHIR_SERVERS` by deliberate choice, bridged into `ApiProperties` by yaml. `ConfigController` serves runtime config to the SPA as `/crawler/config.js` (`window.APP_CONFIG`).

Every server has one `RateGate` (registry on `FhirCrawlClient`, keyed by `CrawlService.normalizeServerKey`) shared across all jobs targeting it: a semaphore caps in-flight requests, and a 429's `Retry-After` pauses all sibling chains (only a 429 does; a 500 or timeout does not).

### Domain model

Entities (`org.hl7.davinci.api.entity`) are linked by plain String ids, no DB foreign keys and no JPA cascades; `JobDeletionService` orchestrates deletes manually.

- `CrawlJob` - schedule (Spring cron; blank = manual-only), `enabled`, `running` (display flag; `CrawlStartupRecovery` clears stale flags at startup and re-triggers interrupted jobs, which resume from checkpoints; a graceful shutdown deliberately records in-flight runs as PAUSED and leaves the flag set so recovery auto-resumes them).
- `CrawlRun` - per-server, per-batch history row; saved RUNNING at crawl start, updated at the end. A completed run absorbs its PAUSED predecessor segments' counts and durations (`absorbSegment`; segments telescope), so the final row reads as the whole crawl and `StatsService` excludes PAUSED/RUNNING rows.
- `CrawlStep` - play-by-play timeline keyed by `batchId`; `errorBody` capped at 100k chars, messages/urls clipped to 10k.
- `ManifestRecord` - one retained snapshot: DB row + `storageDir` of gzipped `{Type}.ndjson.gz` files (decompressed on serve; wire format is plain NDJSON). Per-type counts come from a `counts.json` sidecar written at export time. Orphaned snapshot dirs are swept at startup.
- `CrawlCheckpoint` - per `jobId|serverKey|Type` resume watermark: everything below it is fetched AND persisted. Written only by the watermark strategies as the persisted frontier advances (a checkpoint never claims unpersisted rows; a failed window pins the frontier), consumed at crawl start as per-type lower bounds. Powers restart resume, mid-run-failure resume, and user pause/resume (`POST /jobs/{id}/pause` and `/resume`); after a pause request, any in-flight exception maps to PAUSED, not ERROR.
- `CrawlResource` - current aggregate keyed by the composite `CrawlResourceId` (`server_id`, `type_id`, `uid`), server-scoped (shared by all jobs targeting that server; cleared only when the last such job is deleted). `server_id` resolves through the `crawl_server` lookup table (`ServerRegistry`, the only component that touches it) and `type_id` is `PlanNetTypes`' hardcoded literal id map. Body stored as raw gzip via `ResourceJsonCodec`. Implements `Persistable<CrawlResourceId>` so diff-classified inserts skip the per-row exists-SELECT.

### Crawl flow

`CrawlScheduler.poll()` (every `api.poller-interval-ms`) triggers due jobs via `CrawlService.triggerAsync`; the in-memory `inFlight` map is the single-flight guard (surfaced as `JobResponse.currentBatchId`). Five concrete strategies in `FhirCrawlClient`:

- SEARCH - page-link paging, one parallel task per type.
- SEARCH_LAST_UPDATED - pages by an advancing `_lastUpdated` watermark (re-querying `ge{watermark}`), falling back to `link[next]` through same-instant clusters larger than the page size.
- SEARCH_LAST_UPDATED_PARTITIONED - counts each type (each count form tried once, no per-form retries; the next form is the retry), slices the range into uniform time windows (~50k resources each, capped 4096), crawls windows as parallel watermark tasks with an exclusive `lt` upper bound. Watermarks consider only match-mode entries of the requested type; a page violating the `lt` bound fails the window loudly rather than inverting the query.
- BULK_EXPORT - $export kick-off/poll, output files downloaded as parallel streamed tasks; incremental via `_since` once a completed run exists (a rejected `_since` retries once as a bare full export). A file failing after retries fails the export (otherwise its resources would be misclassified as deletions).
- HISTORY - `_history`-based export.

AUTO (the frontend default) re-resolves the best supported strategy every run, narrated with persisted STRATEGY steps: $export kick-off, else probe partitioned watermark search per type, else plain SEARCH, else HISTORY. Fallback triggers ONLY at the entry phase (4xx except 429, or a kick-off 5xx surviving retries); transient failures retry via `withRetry` and then fail the run - they never demote a capable server. Once committed, a strategy's failures fail the run. `CrawlRun.strategy` records the concrete strategy, never AUTO.

Persistence streams: strategies feed batches to a `CrawlPersistenceService.SnapshotSession` sink (the full set is never in memory; `BatchEmitter` flushes every 1000). Classification is O(batch): prior versions are fetched by PK `IN` for only that batch's keys and diffed (`DiffUtil.computeDiff`); only changed rows upsert, in chunked transactions, never wipe-and-rewrite. A seen-keys set makes intra-run re-fetches (watermark boundary overlap) classify against this run's own writes. Deletions come from `_history` scans (incremental; `request.method == DELETE` is the marker, a 4xx degrades to `historySupported=false`, a 5xx fails the run) or a full key scan against seen keys (full bulk export only). A resumed FULL crawl finishes with incremental (no-deletion) semantics since it cannot have covered the whole server. The next incremental `_since` is the previous COMPLETED run's `serverTimeAtStart` (windows overlap deliberately; the diff absorbs it).

Outbound HTTP: `api.request-timeout-ms` factory-wide; `FhirCrawlClient.withRetry` retries connection failures, 429, and any 5xx up to 3 attempts with backoff (crawl requests are idempotent GETs), honoring `Retry-After` clamped to 60s. Each paging chain prefetches one page ahead; rate-gate waits and page delays run inside the prefetch so they overlap processing. Each parallel task gets its own `IGenericClient` and `IParser` (HAPI parsers are not thread-safe).

### Events: persisted steps vs transient progress

Steps carry an optional `track` (type name, `Type [i/n]` window, or file name) so concurrent chains can be told apart; null track = job-level. Persisted steps (`info`/`request`/`failure`) get contiguous seq numbers (assignment, save, and SSE broadcast serialized in one synchronized block), are written to `crawl_step`, and broadcast as `step` events. Transient progress markers are broadcast-only and MUST NOT consume a seq (the sink passes 0). `CrawlEventService` tracks the latest marker per batch+track and replays the full persisted timeline plus active tracks to each SSE subscriber; a marker carrying an HTTP status is a settled resolution that clears its track. The frontend play-by-play panel resets on every SSE `open` and lets the replay rebuild, so reconnects never duplicate. Every step also mirrors to SLF4J with `batchId`/`serverKey` in MDC, and long loops emit throttled INFO heartbeats so headless runs are followable.

### REST + frontend integration

Controllers in `org.hl7.davinci.api.web`:

- `ApiJobController` - jobs CRUD, run, pause/resume (pause stops an in-flight run as PAUSED; resume re-triggers a checkpointed crawl), paginated runs; DELETE force-cancels and returns 204.
- `ApiManifestController` - list/render/delete plus `POST /api/manifests/{id}/regenerate`: re-exports a manifest's files from the current `crawl_resource` aggregate on a background worker (builds in a `{id}.regen` temp dir, atomically moved so a concurrent render never sees half-written gzip; single-flight per manifest, surfaced as `ManifestSummary.regenerating`; 409 while the job is crawling, when the job is gone, or on delete during regeneration).
- `ApiStatsController` - `GET /api/stats`; `StatsService.computeOverall` never full-scans `crawl_resource` (server keys from the jobs table, per-(server,type) PK-range counts), cached stale-while-revalidate with a 60s TTL and warmed at startup.
- `CrawlEventController` - step replay + SSE per batchId. `ConfigController` - `/crawler/config.js`. `PublishSnapshotsController` - `GET /api/publish/snapshots`.

`CrawlerFrontendConfig` serves the built SPA from `classpath:/static/crawler/` at `/crawler` with an SPA fallback; the Docker build copies `frontend/dist/` there, locally the frontend is a separate Vite dev server. CORS is wide open on `/api/**` only (`ApiConfig`).

### Bulk Data $bulk-publish (self-publish)

`PublishService` (`org.hl7.davinci.publish`) periodically publishes a snapshot of THIS server's own Plan-Net data via `GET /fhir/$bulk-publish`. State is filesystem-only under `publish.storage-path` (`current` pointer, per-snapshot `meta.json`, gzipped `{Type}.ndjson.gz`); fully isolated from the crawler. Snapshots advance by replaying `HFJ_RES_VER` change windows bounded by a `WriteFrontier` (in-flight write registry + grace lag); per-id winners are chosen by numeric `versionId`, never timestamp, and the merge is strictly monotone so replaying a window twice is safe. Deletions are conveyed by absence, not a `deleted[]` array. An unchanged type's file URL stays byte-identical across snapshots; retention spares any directory still referenced by a retained snapshot. Acceptance proof: `BulkPublishRaceIT` (snapshot-vs-oracle diff under concurrent writer storms).

`StorageSettingsGuard` asserts the storage settings the feed depends on (`HFJ_RES_VER` append-only: history enabled, expunge/delete-expunge/history-rewrite disabled, VERSIONED tags, no partitioning); a violation ERROR-logs once and skips publishing. Config is `PublishProperties` (`publish.*`, env `PUBLISH_*`): `enabled`, `interval-ms`, `frontier-grace-ms`, `overlap-ms`, `retention` + `grace-period-ms`, `export-page-size`, `storage-path`, `reset-on-startup` (false on persistent deployments so the feed survives restarts).

Serving details worth knowing: the manifest `Content-Type` is `application/json` (not fhir+json) with a byte-exact SHA-256 `ETag` honoring `If-None-Match`; files serve as `application/fhir+ndjson`, stored gzip streamed as-is only when `Accept-Encoding` allows.

### Frontend (`frontend/`)

React 19 + TanStack Router/Query, Tailwind 4, shadcn-style primitives in `src/components/ui/`, biome, vitest; a Bun/Nx workspace member. Key files: `src/lib/api/client.ts` (typed fetch wrapper), `src/hooks/use-api.ts` (React Query hooks; jobs poll 3s while running, 15s idle), `src/lib/fhir-config.ts` (runtime config precedence: `window.APP_CONFIG`, then `VITE_*`, then defaults), `src/lib/publish/manifest.ts`, `src/hooks/use-bulk-publish.ts`. Vite `base` is `/crawler/` for builds, `/` for dev.

UI conventions: the live play-by-play panel is selection-driven (only the selected job's active run) and renders one live line per active track via the pure reducer in `src/lib/crawler/progress-lines.ts`; track labels appear only with 2+ concurrent lines. Pause/resume buttons render only when a genuine action exists: pause for any running job or an enabled schedule; resume for a disabled job with a schedule or a checkpointed crawl; an idle, enabled, resumable job gets neither (Run covers it). Raw JSON always goes through the shared `JsonViewerDialog`.

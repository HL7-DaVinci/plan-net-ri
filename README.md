# Da Vinci PDex Plan-Net Reference Implementation

A HAPI FHIR R4 server for the [Da Vinci PDex Plan-Net](http://hl7.org/fhir/us/davinci-pdex-plan-net/) implementation guide. It runs as a standard FHIR server, and also ships an experimental **Directory Crawler** web app (see [Directory Crawler](#directory-crawler-experimental) below).

The server is based on the [HAPI FHIR JPA Server Starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter). See that project for additional configuration.

## Hosted instances

The server is publicly hosted in the HL7 Foundry.

- Server base:https://plan-net-ri.davinci.hl7.org 
- FHIR base: https://plan-net-ri.davinci.hl7.org/fhir
- Directory crawler: https://plan-net-ri.davinci.hl7.org/crawler

## Quick start (pre-built Docker image)

```bash
docker run -p 8080:8080 hlseven/davinci-plan-net-ri
```

- FHIR server: http://localhost:8080/fhir
- Directory Crawler: http://localhost:8080/crawler

Image on Docker Hub: https://hub.docker.com/r/hlseven/davinci-plan-net-ri

## Quick start (development)

Both the FHIR server and the Directory Crawler frontend can be run together in their dev configuration with `nx serve`. Install dependencies once from the repository root, then run it:

```bash
bun install --frozen-lockfile
nx serve   # serves both the FHIR server and the crawler frontend
```

- FHIR server: http://localhost:8080/fhir
- Directory Crawler dev UI: http://localhost:3000

[Bun](https://bun.sh) is preferred for installing dependencies; the equivalent root script is `bun run serve` (or `npm run serve`). To run just the FHIR server, or for crawler configuration, see the sections below.

## Running the FHIR server

The server runs as a standalone FHIR server; the Directory Crawler frontend is optional and is covered separately below. It uses an embedded H2 database by default, so nothing else is required to start. Building locally requires JDK 17+ and Maven.

**Development**

```bash
mvn spring-boot:run
```

The server is available at http://localhost:8080/fhir.

**Production**

Run the pre-built image shown above, or build your own (the Docker build also bundles the Directory Crawler):

```bash
docker build -t davinci-plan-net-ri .
docker run -p 8080:8080 davinci-plan-net-ri
```

---

# Directory Crawler (experimental)

The Directory Crawler is an experimental web app (a React single-page app plus a Spring REST API under `/api`) that runs scheduled crawls of FHIR Plan-Net directories, diffs the results between runs, and publishes Bulk Data manifests. It is bundled into the server and served under `/crawler`.

- **Public:** https://plan-net-ri.davinci.hl7.org/crawler
- **Local:** http://localhost:8080/crawler (when running the Docker image)

## Running the crawler frontend in development

The simplest way is `nx serve` from the repository root, which starts both the server and the frontend dev server together (see [Quick start (development)](#quick-start-development) above). To run them separately:

**1. Start the server** (provides the FHIR server and the crawler API):

```bash
mvn spring-boot:run
```

**2. Start the frontend dev server.** The frontend is a [Bun](https://bun.sh) workspace, and Bun is preferred (it is what the Docker build uses). Install from the repository root with a frozen lockfile so dependencies match the committed `bun.lock`:

```bash
bun install --frozen-lockfile   # run from the repository root
cd frontend
bun run dev
```

npm can also be used. Because the repository commits only `bun.lock`, a frozen `npm ci` install is not available, so use a regular install:

```bash
npm install   # run from the repository root
cd frontend
npm run dev
```

The dev UI is served at http://localhost:3000. In development it runs at the web root and calls the crawler API on the running server at http://localhost:8080.

## Configuration (environment variables)

The crawler reads these at runtime, so a deployment can be configured without rebuilding the frontend. Spring relaxed binding maps a property such as `api.public-base-url` to the environment variable `API_PUBLIC_BASE_URL` (uppercase, dots and hyphens become underscores).

**Crawler UI** (served to the browser via `GET /crawler/config.js`):

| Variable | Default | Description |
|---|---|---|
| `APP_FHIR_SERVERS` | `[]` | JSON array of `{ "name", "url" }` FHIR servers shown in the UI server picker. When empty, the UI uses its built-in default. |
| `API_PUBLIC_BASE_URL` | unset | Base URL the UI uses for crawler API calls. When unset, the UI derives it from the selected FHIR server's origin. |

Example:

```bash
APP_FHIR_SERVERS='[{"name":"Production","url":"https://example.org/fhir"}]'
API_PUBLIC_BASE_URL=https://plan-net-ri.davinci.hl7.org
```

**Crawler API / scheduler:**

| Variable | Default | Description |
|---|---|---|
| `API_ENABLED` | `true` | Master switch for the scheduled-crawl poller. REST endpoints stay available when `false`. |
| `API_STORAGE_PATH` | `./target/crawler-data` | Directory where per-manifest NDJSON snapshots are written. |
| `API_POLLER_INTERVAL_MS` | `30000` | How often the scheduler checks for due jobs. |
| `API_PAGE_SIZE` | `500` | Page size used when fetching from FHIR servers. |
| `API_REQUEST_TIMEOUT_MS` | `60000` | Per-request HTTP timeout for crawls. |
| `API_RETENTION_PER_JOB` | `5` | Manifests retained per job (`0` = unlimited). |
| `API_PUBLIC_BASE_URL` | unset | Public base URL used for manifest `output[].url`. The inbound request URL is used when unset. |

For local frontend builds, the `VITE_FHIR_SERVERS` and `VITE_API_BASE_URL` values in `frontend/.env` are baked in at build time (see `frontend/.env.example`); values from `/config.js` take precedence at runtime.

---

## Questions and contributions

Questions can be asked in the [Da Vinci Plan-Net stream on the FHIR Zulip Chat](https://chat.fhir.org/#narrow/stream/229922-Da.2BVinci.2BPDex.2BPlan-Net). Issues should be submitted via the [GitHub issue tracker](https://github.com/HL7-DaVinci/plan-net-ri/issues).

As of October 1, 2022, the Lantana Consulting Group is responsible for the management and maintenance of this Reference Implementation. You can also contact [Corey Spears](mailto:corey.spears@lantanagroup.com).

import type { CrawlStrategy } from "@/lib/api/types";

interface StrategyDoc {
  title: string;
  summary: string;
  steps: string[];
  requests: string[];
  bestFor: string;
  tradeoffs: string;
}

const STRATEGY_DOCS: Record<CrawlStrategy, StrategyDoc> = {
  AUTO: {
    title: "Auto (best available)",
    summary:
      "Resolves the best strategy the server actually supports, re-checked at the start of every run: Bulk $export first, then partitioned last-updated search, then plain search paging, with history paging as the last resort.",
    steps: [
      "Attempt a Bulk Data $export kick-off. A 202 commits the run to Bulk $export; a rejection (4xx, or a 5xx that survives retries, since some servers answer unknown operations with 500) falls through to search.",
      "Probe one cheap _lastUpdated-sorted query per type; the first success commits to partitioned last-updated search.",
      "If every type rejects that, probe a minimal plain search per type; the first success commits to standard search paging.",
      "If search is rejected outright, fall back to history paging.",
      "Transient failures are retried before any decision. A kick-off 5xx that outlives the retries counts as unsupported; persistent rate limiting (429), timeouts, and probe failures fail the run rather than silently demoting the server.",
      "Once a strategy is committed, the run lives or dies with it; each run records which strategy it resolved to.",
    ],
    requests: [
      "GET /$export?_type=...  (Prefer: respond-async; _since on incremental runs)",
      "GET /{Type}?_count=1&_total=none&_sort=_lastUpdated&_lastUpdated=ge1970-01-01  (partitioned probe)",
      "GET /{Type}?_count=1  (plain search probe)",
    ],
    bestFor:
      "New or unfamiliar servers, where capability statements are unreliable and the best access path is unknown.",
    tradeoffs:
      "Spends one kick-off attempt and up to a handful of probe requests before crawling; a server that silently ignores _lastUpdated can pass the probe and degrade partitioned paging.",
  },
  SEARCH: {
    title: "Search (paging)",
    summary:
      "Pulls only what changed since the last run by searching each type and detecting deletions via system history.",
    steps: [
      "Capture a server-time anchor from the HTTP Date header.",
      "For each of the 8 Plan-Net types, search for resources changed after the anchor (first run pulls everything).",
      "Scan system _history since the anchor to find deletions.",
      "Diff against the retained snapshot: added / updated / deleted.",
    ],
    requests: [
      "GET /{Type}?_lastUpdated=gt{anchor}&_sort=_lastUpdated",
      "GET /_history?_since={anchor}",
    ],
    bestFor: "Keeping a directory in sync with minimal data transfer.",
    tradeoffs:
      "Many small requests; the first run is a full baseline; needs system _history for deletion detection.",
  },
  SEARCH_LAST_UPDATED: {
    title: "Search (by last updated)",
    summary:
      "Walks each type in last-updated order, advancing a date cursor instead of following the server's page links. Better for very large directories where standard paging eventually times out.",
    steps: [
      "Capture a server-time anchor from the HTTP Date header.",
      "For each of the 8 Plan-Net types, search ordered by _lastUpdated (from the anchor on incremental runs).",
      "Advance the cursor to the newest _lastUpdated on each page and re-query from there, so every request is a fresh shallow query.",
      "If more resources share one _lastUpdated instant than fit on a page, follow that page's next links to read the whole cluster before advancing the cursor.",
      "Diff against the retained snapshot: added / updated / deleted.",
    ],
    requests: [
      "GET /{Type}?_lastUpdated=ge{cursor}&_sort=_lastUpdated&_count=N&_total=none",
      "GET {next-link}  (only to traverse a same-instant cluster)",
      "GET /_history?_since={anchor}  (incremental deletions)",
    ],
    bestFor:
      "Very large directories where standard search paging times out partway through.",
    tradeoffs:
      "Primarily shallow date-windowed queries, but a _lastUpdated instant shared by more resources than fit on a page falls back to page-link paging to read that cluster; re-queries cause small boundary overlaps that the diff dedupes.",
  },
  SEARCH_LAST_UPDATED_PARTITIONED: {
    title: "Search (last updated - partitioned)",
    summary:
      "Counts each type's _lastUpdated range once, slices it into equal time windows targeting 50,000 resources each, then crawls those windows in parallel using the same advancing-cursor paging as Search (by last updated). Built for very large directories on servers that clamp page size.",
    steps: [
      "Capture a server-time anchor from the HTTP Date header.",
      "Count each of the 8 Plan-Net types once, trying each form in turn: _summary=count, then _count=0, then _total=accurate on a first page. Each form gets one attempt; a count that outlives the gateway keeps running server-side, so the next form is the retry. A first crawl counts bare; incremental runs bound the count to changes since the anchor.",
      "If the count calls for more than one window, find the earliest _lastUpdated with a single _count=1 lookup to anchor the cuts (skipped on incremental runs and for single-window types).",
      "Slice the range into equal-duration windows of roughly 50,000 resources by the count; if no count form works, slice it across the worker pool anyway.",
      "Crawl the windows in parallel, each one paging with an advancing _lastUpdated cursor and following next links through same-instant clusters.",
      "Scan system _history since the anchor to find deletions.",
      "Diff against the retained snapshot: added / updated / deleted.",
    ],
    requests: [
      "GET /{Type}?_summary=count  (count; falls back to _count=0, then _count=1&_total=accurate; date-bounded on incremental runs)",
      "GET /{Type}?_lastUpdated=ge{cursor}&_lastUpdated=lt{hi}&_sort=_lastUpdated&_count=N&_total=none  (per window)",
      "GET {next-link}  (only to traverse a same-instant cluster)",
      "GET /_history?_since={anchor}  (incremental deletions)",
    ],
    bestFor:
      "Very large directories on servers that clamp page size but support _lastUpdated search and report totals for at least one count form.",
    tradeoffs:
      "Adds up to three count requests per type before any data page is fetched; windows are sized by time, not by exact resource count, so uneven update density makes some windows larger than others (the cursor paging absorbs this).",
  },
  BULK_EXPORT: {
    title: "Bulk $export",
    summary:
      "Asks the server to generate the directory asynchronously, then downloads the resulting NDJSON files. Incremental runs pass the previous run's anchor as _since so only changes are exported.",
    steps: [
      "Kick off GET /$export with Prefer: respond-async (adding _since={anchor} after the first completed run); the server returns 202 and a polling URL.",
      "If the server rejects the _since kick-off, retry once as a full export.",
      "Poll that URL, honoring Retry-After, while the server builds the files.",
      "On 200, read the manifest listing the output files, then download and ingest each NDJSON file.",
      "A full export detects deletions by absence; an incremental export scans system _history for them instead.",
    ],
    requests: [
      "GET /$export?_type=Endpoint,...,PractitionerRole  (Prefer: respond-async; _since={anchor} on incremental runs)",
      "GET {content-location}  (poll until 200)",
      "GET {output file url}",
      "GET /_history?_since={anchor}  (incremental deletions)",
    ],
    bestFor:
      "Efficient snapshots from servers that support Bulk Data; the server does the heavy lifting.",
    tradeoffs:
      "Asynchronous: you wait while the server works; requires server $export support, and _since support varies (a server that ignores it just re-exports everything, which the diff absorbs).",
  },
  HISTORY: {
    title: "History paging",
    summary:
      "Walks the system-level _history feed and collapses each resource to its current version. The first run reads full history; later runs page only changes since the last anchor.",
    steps: [
      "Capture a server-time anchor from the HTTP Date header.",
      "Request system _history (full on the first run, or _since the anchor afterward), following next links.",
      "Keep the newest version of each resource; entries whose latest state is a deletion are removed (incremental runs apply these as deletions).",
      "First run replaces the snapshot; later runs apply the delta (upserts + deletions).",
    ],
    requests: [
      "GET /_history?_count=500",
      "GET /_history?_since={anchor}&_count=500",
    ],
    bestFor:
      "Full snapshots from servers without $export, then cheap incremental syncs including deletions.",
    tradeoffs:
      "The first run returns every version (the client dedupes) and can be heavy on large directories.",
  },
};

export function StrategyInfo({ strategy }: { strategy: CrawlStrategy }) {
  const doc = STRATEGY_DOCS[strategy];
  return (
    <div className="rounded-md border bg-muted/30 p-3 text-sm">
      <p className="font-medium">{doc.title}</p>
      <p className="mt-0.5 text-muted-foreground">{doc.summary}</p>

      <p className="mt-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
        How it works
      </p>
      <ol className="mt-1 list-decimal space-y-0.5 pl-5">
        {doc.steps.map((step) => (
          <li key={step}>{step}</li>
        ))}
      </ol>

      <p className="mt-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
        Requests it issues
      </p>
      <ul className="mt-1 space-y-0.5">
        {doc.requests.map((req) => (
          <li key={req}>
            <code className="text-xs">{req}</code>
          </li>
        ))}
      </ul>

      <p className="mt-2">
        <span className="font-medium">Best for:</span> {doc.bestFor}
      </p>
      <p className="mt-0.5">
        <span className="font-medium">Tradeoffs:</span> {doc.tradeoffs}
      </p>
    </div>
  );
}

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
  SEARCH: {
    title: "Incremental search",
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
  BULK_EXPORT: {
    title: "Bulk $export",
    summary:
      "Asks the server to generate the whole directory asynchronously, then downloads the resulting NDJSON files.",
    steps: [
      "Kick off GET /$export with Prefer: respond-async; the server returns 202 and a polling URL.",
      "Poll that URL, honoring Retry-After, while the server builds the files.",
      "On 200, read the manifest listing the output files.",
      "Download each NDJSON file and ingest it.",
    ],
    requests: [
      "GET /$export?_type=Endpoint,...,PractitionerRole  (Prefer: respond-async)",
      "GET {content-location}  (poll until 200)",
      "GET {output file url}",
    ],
    bestFor: "Efficient full snapshots from servers that support Bulk Data.",
    tradeoffs:
      "Asynchronous: you wait while the server works; requires server $export support.",
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

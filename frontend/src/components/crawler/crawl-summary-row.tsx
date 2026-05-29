import { MinusCircle, PencilLine, PlusCircle } from "lucide-react";
import type { CrawlBatchSummary } from "@/lib/crawler/types";

interface CrawlSummaryRowProps {
  latest?: CrawlBatchSummary;
}

/** Compact combined added/changed/removed for the latest crawl operation. */
export function CrawlSummaryRow({ latest }: CrawlSummaryRowProps) {
  return (
    <div className="shrink-0 rounded-lg border bg-card p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
          Last crawl changes
        </span>
        {latest && latest.serverCount > 1 && (
          <span className="text-[10px] text-muted-foreground">
            {latest.serverCount} servers
          </span>
        )}
      </div>
      {latest ? (
        <div className="mt-2 flex items-center gap-4 text-sm tabular-nums">
          <span className="flex items-center gap-1 text-success">
            <PlusCircle className="h-3.5 w-3.5" />
            {latest.added} added
          </span>
          <span className="flex items-center gap-1 text-amber-500">
            <PencilLine className="h-3.5 w-3.5" />
            {latest.updated} changed
          </span>
          <span className="flex items-center gap-1 text-destructive">
            <MinusCircle className="h-3.5 w-3.5" />
            {latest.deleted} removed
          </span>
        </div>
      ) : (
        <p className="mt-1 text-xs text-muted-foreground">No crawls yet.</p>
      )}
    </div>
  );
}

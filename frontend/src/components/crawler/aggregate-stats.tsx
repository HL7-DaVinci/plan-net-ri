import type { AggregateCounts } from "@/lib/crawler/db";
import { formatDuration, formatTimestamp } from "@/lib/crawler/format";
import type { CrawlBatchSummary, ScopeServer } from "@/lib/crawler/types";

interface AggregateStatsProps {
  aggregate: AggregateCounts;
  scope: ScopeServer[];
  /** Combined totals for the latest crawl operation (across all servers). */
  latest?: CrawlBatchSummary;
}

function Stat({
  label,
  value,
  valueClassName = "text-lg",
  title,
}: {
  label: string;
  value: string;
  valueClassName?: string;
  title?: string;
}) {
  return (
    <div className="min-w-0">
      <div className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
        {label}
      </div>
      <div
        className={`truncate font-semibold tabular-nums ${valueClassName}`}
        title={title}
      >
        {value}
      </div>
    </div>
  );
}

/** Compact at-a-glance stats for the right column. */
export function AggregateStats({
  aggregate,
  scope,
  latest,
}: AggregateStatsProps) {
  const lastCrawl = latest ? formatTimestamp(latest.serverTimeAtStart) : "-";
  return (
    <div className="grid shrink-0 grid-cols-2 gap-x-4 gap-y-2 rounded-lg border bg-card p-3">
      <Stat label="Total resources" value={aggregate.total.toLocaleString()} />
      <Stat label="Servers" value={scope.length.toString()} />
      <Stat
        label="Last crawl"
        value={lastCrawl}
        valueClassName="text-sm"
        title={lastCrawl}
      />
      <Stat
        label="Last run"
        value={latest ? formatDuration(latest.durationMs) : "-"}
      />
    </div>
  );
}

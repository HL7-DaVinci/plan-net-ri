import { Clock, Database, Gauge, Server } from "lucide-react";
import { StatCard } from "@/components/stat-card";
import type { AggregateCounts } from "@/lib/crawler/db";
import {
  formatDuration,
  formatRate,
  formatTimestamp,
} from "@/lib/crawler/format";
import type { CrawlBatchSummary, ScopeServer } from "@/lib/crawler/types";

interface AggregateStatsProps {
  aggregate: AggregateCounts;
  scope: ScopeServer[];
  /** Combined totals for the latest crawl operation (across all servers). */
  latest?: CrawlBatchSummary;
}

/**
 * At-a-glance stat cards (total resources, servers, last crawl, last run).
 */
export function AggregateStats({
  aggregate,
  scope,
  latest,
}: AggregateStatsProps) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <StatCard
        title="Total resources"
        icon={<Database className="h-4 w-4 text-primary" />}
        value={aggregate.total.toLocaleString()}
        subtitle={`${Object.keys(aggregate.perType).length} resource types`}
      />
      <StatCard
        title="Servers"
        icon={<Server className="h-4 w-4 text-primary" />}
        value={scope.length.toString()}
        subtitle="in crawl scope"
      />
      <StatCard
        title="Last crawl"
        icon={<Clock className="h-4 w-4 text-primary" />}
        value={latest ? formatTimestamp(latest.serverTimeAtStart) : "-"}
        valueClassName="text-sm"
        subtitle={latest ? `${latest.mode} crawl` : "no crawls yet"}
      />
      <StatCard
        title="Last run"
        icon={<Gauge className="h-4 w-4 text-primary" />}
        value={latest ? formatDuration(latest.durationMs) : "-"}
        subtitle={
          latest
            ? [
                `${latest.pages} pages, ${latest.requests} requests`,
                formatRate(latest.records, latest.durationMs),
              ]
                .filter(Boolean)
                .join(", ")
            : ""
        }
      />
    </div>
  );
}

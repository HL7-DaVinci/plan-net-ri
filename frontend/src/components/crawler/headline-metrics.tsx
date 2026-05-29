import { CheckCircle2, ShieldCheck, TrendingDown } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { AggregateCounts } from "@/lib/crawler/db";
import { formatBytes, formatTimestamp } from "@/lib/crawler/format";
import type {
  CrawlBatchSummary,
  EfficiencyComparison,
} from "@/lib/crawler/types";

interface HeadlineMetricsProps {
  efficiency: EfficiencyComparison;
  aggregate: AggregateCounts;
  /** Combined totals for the latest crawl operation (across all servers). */
  latest?: CrawlBatchSummary;
}

function MiniBar({ value, max }: { value: number; max: number }) {
  const pct = max > 0 ? Math.max(2, Math.round((value / max) * 100)) : 0;
  return (
    <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
      <div
        className="h-full rounded-full bg-primary"
        style={{ width: `${pct}%` }}
      />
    </div>
  );
}

export function HeadlineMetrics({
  efficiency,
  aggregate,
  latest,
}: HeadlineMetricsProps) {
  const { full, incremental, savingsPct } = efficiency;
  const maxBytes = Math.max(full?.bytes ?? 0, incremental?.bytes ?? 0, 1);

  const verifiedDeletions =
    latest?.hasIncremental && latest.status === "completed"
      ? latest.deleted
      : undefined;

  return (
    <div className="grid gap-4 md:grid-cols-2">
      {/* Efficiency */}
      <Card className="border-l-4 border-l-primary/40">
        <CardHeader className="pb-2">
          <CardTitle className="text-base flex items-center gap-2">
            <TrendingDown className="h-4 w-4 text-primary" />
            Incremental efficiency
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {full && incremental ? (
            <>
              <div className="flex items-baseline gap-2">
                <span className="text-4xl font-semibold tracking-tight metric-value text-primary">
                  {savingsPct ?? 0}%
                </span>
                <span className="text-sm text-muted-foreground">
                  less data on incremental sync
                </span>
              </div>
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">
                    Full crawl: {full.records.toLocaleString()} records,{" "}
                    {full.requests} requests
                  </span>
                  <span className="font-medium tabular-nums">
                    {formatBytes(full.bytes)}
                  </span>
                </div>
                <MiniBar value={full.bytes} max={maxBytes} />
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">
                    Incremental: {incremental.records.toLocaleString()} records,{" "}
                    {incremental.requests} requests
                  </span>
                  <span className="font-medium tabular-nums">
                    {formatBytes(incremental.bytes)}
                  </span>
                </div>
                <MiniBar value={incremental.bytes} max={maxBytes} />
              </div>
            </>
          ) : full ? (
            <div className="space-y-1">
              <div className="text-2xl font-semibold metric-value">
                {full.records.toLocaleString()}{" "}
                <span className="text-sm font-normal text-muted-foreground">
                  records, {formatBytes(full.bytes)}, {full.requests} requests
                </span>
              </div>
              <p className="text-sm text-muted-foreground">
                Full-crawl baseline. Run an incremental crawl to see how little
                data a keep-in-sync update needs.
              </p>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">
              Run a full crawl, then an incremental crawl to compare data
              transferred.
            </p>
          )}
        </CardContent>
      </Card>

      {/* Accuracy */}
      <Card className="border-l-4 border-l-success/50">
        <CardHeader className="pb-2">
          <CardTitle className="text-base flex items-center gap-2">
            <ShieldCheck className="h-4 w-4 text-success" />
            Directory accuracy
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-baseline gap-2">
            <span className="text-4xl font-semibold tracking-tight metric-value">
              {aggregate.total.toLocaleString()}
            </span>
            <span className="text-sm text-muted-foreground">
              resources in the local directory
            </span>
          </div>
          <p className="text-sm text-muted-foreground">
            Accurate as of{" "}
            <span className="font-medium text-foreground">
              {formatTimestamp(latest?.serverTimeAtStart)}
            </span>{" "}
            (server time).
          </p>
          {verifiedDeletions !== undefined && verifiedDeletions > 0 && (
            <div className="flex items-center gap-2 text-sm text-success">
              <CheckCircle2 className="h-4 w-4" />
              Verified: {verifiedDeletions} deletion(s) removed from the local
              copy.
            </div>
          )}
          {verifiedDeletions === 0 && latest && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <CheckCircle2 className="h-4 w-4 text-success" />
              In sync. No deletions detected in the last incremental crawl.
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

import {
  CheckCircle2,
  GitCompare,
  MinusCircle,
  PencilLine,
  PlusCircle,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  formatDuration,
  formatRate,
  formatTimestamp,
} from "@/lib/crawler/format";
import type { CrawlBatchSummary, CrawlRun } from "@/lib/crawler/types";

interface CrawlDiffPanelProps {
  /** Combined totals for the latest crawl operation (across all servers). */
  latest?: CrawlBatchSummary;
  /** Per-server run history. */
  runs: CrawlRun[];
}

function DiffStat({
  icon,
  label,
  value,
  className,
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  className: string;
}) {
  return (
    <div className="flex items-center gap-2">
      <span className={className}>{icon}</span>
      <span className="text-2xl font-semibold tabular-nums metric-value">
        {value}
      </span>
      <span className="text-xs text-muted-foreground">{label}</span>
    </div>
  );
}

export function CrawlDiffPanel({ latest, runs }: CrawlDiffPanelProps) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base flex items-center gap-2">
          <GitCompare className="h-4 w-4" />
          Last crawl changes
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {latest ? (
          <>
            <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
              <DiffStat
                icon={<PlusCircle className="h-4 w-4" />}
                label="added"
                value={latest.added}
                className="text-success"
              />
              <DiffStat
                icon={<PencilLine className="h-4 w-4" />}
                label="changed"
                value={latest.updated}
                className="text-amber-500"
              />
              <DiffStat
                icon={<MinusCircle className="h-4 w-4" />}
                label="removed"
                value={latest.deleted}
                className="text-destructive"
              />
            </div>
            {formatRate(latest.records, latest.durationMs) && (
              <div className="text-xs text-muted-foreground">
                Throughput: {formatRate(latest.records, latest.durationMs)} (
                {formatDuration(latest.durationMs)})
              </div>
            )}
            {latest.hasIncremental && latest.status === "completed" && (
              <div className="flex items-center gap-2 text-sm text-success">
                <CheckCircle2 className="h-4 w-4" />
                {latest.deleted > 0
                  ? `Verified: ${latest.deleted} deleted resource(s) removed from the local copy.`
                  : "Verified: local copy matches the server (no deletions)."}
              </div>
            )}
          </>
        ) : (
          <p className="text-sm text-muted-foreground">
            No crawls yet. Run a full crawl to populate the directory.
          </p>
        )}

        {runs.length > 0 && (
          <div className="border-t pt-3">
            <div className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">
              Run history
            </div>
            <div className="space-y-1">
              {runs.slice(0, 8).map((run) => (
                <div
                  key={run.id}
                  className="flex items-center gap-3 text-xs py-1"
                >
                  <span className="text-muted-foreground w-36 shrink-0 truncate">
                    {formatTimestamp(run.startedAt)}
                  </span>
                  <span className="w-28 shrink-0 truncate font-medium">
                    {run.serverLabel}
                  </span>
                  <span className="w-20 shrink-0 capitalize text-muted-foreground">
                    {run.mode}
                  </span>
                  <span className="flex items-center gap-2 tabular-nums">
                    <span className="text-success">+{run.diff.added}</span>
                    <span className="text-amber-500">~{run.diff.updated}</span>
                    <span className="text-destructive">
                      -{run.diff.deleted}
                    </span>
                  </span>
                  <span className="ml-auto text-muted-foreground tabular-nums">
                    {formatDuration(run.durationMs)}
                  </span>
                  <span
                    className={`w-16 shrink-0 text-right ${
                      run.status === "completed"
                        ? "text-success"
                        : run.status === "aborted"
                          ? "text-amber-500"
                          : "text-destructive"
                    }`}
                  >
                    {run.status}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

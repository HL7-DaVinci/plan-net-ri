import { ChevronDown, ChevronLeft, ChevronRight } from "lucide-react";
import { Fragment, useEffect, useState } from "react";
import { PlayByPlay } from "@/components/api/play-by-play";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useRuns } from "@/hooks/use-api";
import type { RunResponse } from "@/lib/api/types";

const PAGE_SIZES = [10, 25, 50, 100];
const DEFAULT_PAGE_SIZE = 25;

function formatTime(iso: string | null): string {
  if (!iso) return "-";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function statusVariant(
  status: string,
): "default" | "secondary" | "destructive" {
  if (status === "COMPLETED") return "default";
  if (status === "ERROR") return "destructive";
  return "secondary";
}

interface RunHistoryProps {
  jobId: string | null;
}

export function RunHistory({ jobId }: RunHistoryProps) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // Reset to the first page when the job or page size changes.
  useEffect(() => {
    setPage(0);
  }, [jobId, pageSize]);

  const { data, isLoading } = useRuns(jobId ?? undefined, page, pageSize);

  const totalPages = data?.totalPages ?? 0;

  // Keep the page in range if runs were pruned out from under us.
  useEffect(() => {
    if (totalPages > 0 && page > totalPages - 1) {
      setPage(totalPages - 1);
    }
  }, [page, totalPages]);

  if (!jobId) {
    return (
      <p className="text-sm text-muted-foreground">
        Select a job to see its run history.
      </p>
    );
  }
  if (isLoading) {
    return <p className="text-sm text-muted-foreground">Loading runs...</p>;
  }

  const runs = data?.runs ?? [];
  const totalElements = data?.totalElements ?? 0;

  if (totalElements === 0) {
    return (
      <p className="text-sm text-muted-foreground">No runs yet for this job.</p>
    );
  }

  const pageStart = page * pageSize + 1;
  const pageEnd = page * pageSize + runs.length;

  return (
    <div className="space-y-3">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-xs uppercase tracking-wider text-muted-foreground">
              <th className="py-2 pr-3 font-medium">Started</th>
              <th className="py-2 pr-3 font-medium">Mode</th>
              <th className="py-2 pr-3 font-medium">Status</th>
              <th className="py-2 pr-3 font-medium text-right">+ / ~ / -</th>
              <th className="py-2 pr-3 font-medium text-right">Records</th>
              <th className="py-2 pr-3 font-medium text-right">Duration</th>
              <th className="py-2 pr-3 font-medium">History</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((run: RunResponse) => {
              const isOpen = expandedId === run.id;
              return (
                <Fragment key={run.id}>
                  <tr
                    className="cursor-pointer border-b last:border-0 hover:bg-accent/40"
                    onClick={() => setExpandedId(isOpen ? null : run.id)}
                  >
                    <td className="py-2 pr-3 whitespace-nowrap">
                      <span className="inline-flex items-center gap-1">
                        {isOpen ? (
                          <ChevronDown className="h-3.5 w-3.5" />
                        ) : (
                          <ChevronRight className="h-3.5 w-3.5" />
                        )}
                        {formatTime(run.startedAt)}
                      </span>
                    </td>
                    <td className="py-2 pr-3">{run.mode}</td>
                    <td className="py-2 pr-3">
                      <Badge variant={statusVariant(run.status)}>
                        {run.status}
                      </Badge>
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">
                      {run.added} / {run.updated} / {run.deleted}
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">
                      {run.records.toLocaleString()}
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">
                      {formatDuration(run.durationMs)}
                    </td>
                    <td className="py-2 pr-3">
                      {run.historySupported === null
                        ? "n/a"
                        : run.historySupported
                          ? "yes"
                          : "no"}
                    </td>
                  </tr>
                  {isOpen && (
                    <tr>
                      <td colSpan={7} className="bg-muted/20 px-3 py-2">
                        <PlayByPlay batchId={run.batchId} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground">Per page</span>
          <Select
            value={String(pageSize)}
            onValueChange={(v) => setPageSize(Number(v))}
          >
            <SelectTrigger className="h-8 w-[72px] cursor-pointer">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PAGE_SIZES.map((n) => (
                <SelectItem key={n} value={String(n)}>
                  {n}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex items-center gap-3">
          <span className="tabular-nums text-muted-foreground">
            {pageStart}-{pageEnd} of {totalElements.toLocaleString()}
          </span>
          <div className="flex items-center gap-1">
            <Button
              variant="outline"
              size="sm"
              className="cursor-pointer"
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              <ChevronLeft className="h-4 w-4" />
              Prev
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="cursor-pointer"
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

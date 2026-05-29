import { Loader2, Play, RefreshCw, Trash2, X, Zap } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { CrawlProgress } from "@/lib/crawler/types";

interface CrawlControlPanelProps {
  isCrawling: boolean;
  canIncremental: boolean;
  hasServers: boolean;
  progress: CrawlProgress | null;
  onFull: () => void;
  onIncremental: () => void;
  onCancel: () => void;
  onClear: () => void;
}

export function CrawlControlPanel({
  isCrawling,
  canIncremental,
  hasServers,
  progress,
  onFull,
  onIncremental,
  onCancel,
  onClear,
}: CrawlControlPanelProps) {
  const types = progress ? Object.keys(progress.perType) : [];
  const doneTypes = types.filter((t) => progress?.perType[t].done).length;
  const pct =
    types.length > 0 ? Math.round((doneTypes / types.length) * 100) : 0;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={onFull} disabled={isCrawling || !hasServers} size="sm">
          {isCrawling ? (
            <Loader2 className="h-4 w-4 mr-1 animate-spin" />
          ) : (
            <Play className="h-4 w-4 mr-1" />
          )}
          Full crawl
        </Button>
        <Button
          onClick={onIncremental}
          disabled={isCrawling || !hasServers || !canIncremental}
          size="sm"
          variant="outline"
          title={
            canIncremental
              ? "Fetch only what changed since the last crawl"
              : "Run a full crawl first"
          }
        >
          <Zap className="h-4 w-4 mr-1" />
          Incremental crawl
        </Button>
        {isCrawling && (
          <Button onClick={onCancel} size="sm" variant="ghost">
            <X className="h-4 w-4 mr-1" />
            Cancel
          </Button>
        )}
        <Button
          onClick={onClear}
          disabled={isCrawling || !hasServers}
          size="sm"
          variant="ghost"
          className="ml-auto text-muted-foreground"
        >
          <Trash2 className="h-4 w-4 mr-1" />
          Clear store
        </Button>
      </div>

      {isCrawling && progress && (
        <div className="space-y-1.5">
          <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
            <div
              className="h-full rounded-full bg-primary transition-all"
              style={{ width: `${pct}%` }}
            />
          </div>
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <RefreshCw className="h-3 w-3 animate-spin" />
              {progress.serverLabel}
              {progress.currentType ? `: ${progress.currentType}` : ""},{" "}
              {progress.totalFetched.toLocaleString()} fetched
            </span>
            <span className="tabular-nums">
              {doneTypes}/{types.length} types, {progress.requests} requests
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

import { ChevronRight, Copy, Pause, Play, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import type { WatchLogEntry } from "@/hooks/use-bulk-publish";
import { buildManifestCurl } from "@/lib/publish/manifest";

function statusBadge(status: number | "error") {
  if (status === 200) return <Badge>200 new manifest</Badge>;
  if (status === 304)
    return <Badge variant="secondary">304 not modified</Badge>;
  if (status === "error")
    return <Badge variant="destructive">network error</Badge>;
  return <Badge variant="destructive">{status}</Badge>;
}

export function WatchLog({
  log,
  paused,
  onTogglePause,
  onCheckNow,
  serverUrl,
  etag,
}: {
  log: WatchLogEntry[];
  paused: boolean;
  onTogglePause: () => void;
  onCheckNow: () => void;
  serverUrl: string;
  etag: string | null;
}) {
  const copyCurl = async () => {
    const curl = buildManifestCurl(`${serverUrl}/$bulk-publish`, etag);
    try {
      await navigator.clipboard.writeText(curl);
      toast.success("curl command copied");
    } catch {
      toast.error("Failed to copy to clipboard");
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between">
          <span>Live Watcher</span>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={copyCurl}>
              <Copy className="h-4 w-4 mr-1" />
              Copy as curl
            </Button>
            <Button variant="outline" size="sm" onClick={onCheckNow}>
              <RefreshCw className="h-4 w-4 mr-1" />
              Check now
            </Button>
            <Button variant="outline" size="sm" onClick={onTogglePause}>
              {paused ? (
                <>
                  <Play className="h-4 w-4 mr-1" />
                  Resume
                </>
              ) : (
                <>
                  <Pause className="h-4 w-4 mr-1" />
                  Pause
                </>
              )}
            </Button>
          </div>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="mb-2 text-xs text-muted-foreground">
          Conditional GETs against {serverUrl}/$bulk-publish. A 304 means the
          snapshot is unchanged; a 200 means a new snapshot was published.
        </p>
        <div className="max-h-72 space-y-1 overflow-y-auto">
          {log.length === 0 && (
            <p className="text-sm text-muted-foreground">No requests yet.</p>
          )}
          {log.map((entry) => (
            <Collapsible key={entry.at}>
              <CollapsibleTrigger className="group flex w-full items-center gap-2 rounded px-1 py-0.5 text-sm hover:bg-muted">
                <ChevronRight className="h-3 w-3 shrink-0 transition-transform group-data-[state=open]:rotate-90" />
                <span className="font-mono text-xs text-muted-foreground">
                  {new Date(entry.at).toLocaleTimeString()}
                </span>
                {statusBadge(entry.status)}
                <span className="ml-auto font-mono text-xs text-muted-foreground">
                  {entry.durationMs}ms
                </span>
              </CollapsibleTrigger>
              <CollapsibleContent className="ml-6 space-y-0.5 py-1 font-mono text-xs text-muted-foreground">
                <div>ETag: {entry.etag ?? "-"}</div>
                <div>Cache-Control: {entry.cacheControl ?? "-"}</div>
                {entry.detail && (
                  <div className="break-all">{entry.detail}</div>
                )}
              </CollapsibleContent>
            </Collapsible>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

import { CheckCircle2, FileWarning, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { JsonViewerDialog } from "@/components/json-viewer-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { CrawlStep } from "@/lib/api/types";
import {
  applyProgressEvent,
  type ProgressLines,
} from "@/lib/crawler/progress-lines";
import { getApiBaseUrl } from "@/lib/fhir-config";

function phaseVariant(phase: string): "default" | "secondary" | "destructive" {
  if (phase === "ERROR") return "destructive";
  if (phase === "DONE" || phase === "MANIFEST") return "default";
  return "secondary";
}

/** Parsed JSON when possible so the viewer pretty-prints it; the raw text otherwise. */
function parseBody(body: string): unknown {
  try {
    return JSON.parse(body);
  } catch {
    return body;
  }
}

export function PlayByPlay({ batchId }: { batchId: string; jobName?: string }) {
  const [steps, setSteps] = useState<CrawlStep[]>([]);
  const [done, setDone] = useState(false);
  const [viewing, setViewing] = useState<CrawlStep | null>(null);
  const [lines, setLines] = useState<ProgressLines>(new Map());
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    setSteps([]);
    setDone(false);
    setLines(new Map());
    const source = new EventSource(
      `${getApiBaseUrl()}/api/crawl/${batchId}/stream`,
    );

    // Fires on every (re)connect, and the server replays the full timeline plus one line
    // per genuinely active track on each subscribe. Without this reset a reconnect (server
    // restart, proxy idle timeout) duplicates the replayed steps and strands progress lines
    // whose settled resolution was broadcast while the connection was down.
    source.addEventListener("open", () => {
      setSteps([]);
      setLines(new Map());
    });

    source.addEventListener("step", (event) => {
      const step = JSON.parse((event as MessageEvent).data) as CrawlStep;
      setSteps((prev) => [...prev, step]);
      // A persisted step means the previously announced operation finished.
      setLines((prev) => applyProgressEvent(prev, { kind: "step", step }));
    });
    source.addEventListener("progress", (event) => {
      const step = JSON.parse((event as MessageEvent).data) as CrawlStep;
      const nowMs = Date.now();
      setLines((prev) =>
        applyProgressEvent(prev, { kind: "progress", step, now: nowMs }),
      );
      setNow(nowMs);
    });
    source.addEventListener("complete", () => {
      setDone(true);
      setLines((prev) => applyProgressEvent(prev, { kind: "complete" }));
      source.close();
    });

    return () => source.close();
  }, [batchId]);

  // Tick once a second while an operation is in flight so its elapsed time updates.
  useEffect(() => {
    if (lines.size === 0) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [lines]);

  return (
    <div className="space-y-1.5">
      <p className="text-sm text-muted-foreground">
        Step-by-step timeline of this run.
      </p>
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        {done ? (
          <CheckCircle2 className="h-3.5 w-3.5 text-success" />
        ) : (
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
        )}
        {done ? "Complete" : "Streaming..."}
        <span>({steps.length} steps)</span>
      </div>

      {steps.length === 0 && lines.size === 0 ? (
        <p className="text-sm text-muted-foreground">Waiting for steps...</p>
      ) : (
        <ol className="space-y-1">
          {steps.map((step) => (
            <li
              key={step.seq}
              className="flex items-start gap-2 py-1 pl-2 text-sm odd:bg-muted/30"
            >
              <span className="w-6 shrink-0 text-right tabular-nums text-xs text-muted-foreground">
                {step.seq}
              </span>
              <Badge variant={phaseVariant(step.phase)} className="shrink-0">
                {step.phase}
              </Badge>
              <span className="min-w-0">
                <span className="block">{step.message}</span>
                {step.method && (
                  <code className="block break-all text-xs text-muted-foreground">
                    {step.method} {step.url}
                  </code>
                )}
                <span className="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground tabular-nums">
                  {step.status != null && <span>HTTP {step.status}</span>}
                  {step.ms != null && <span>{step.ms} ms</span>}
                  {step.count != null && (
                    <span>{step.count.toLocaleString()} resources</span>
                  )}
                  {step.errorBody && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="h-6 cursor-pointer gap-1 px-2 text-xs text-destructive"
                      onClick={() => setViewing(step)}
                    >
                      <FileWarning className="h-3 w-3" />
                      Response
                    </Button>
                  )}
                </span>
              </span>
            </li>
          ))}
        </ol>
      )}

      {lines.size > 0 && !done && (
        <div className="divide-y divide-border rounded-md border bg-muted/30">
          {Array.from(lines.values())
            .sort((a, b) => a.track.localeCompare(b.track))
            .map((line) => (
              <div
                key={line.track}
                className="flex items-start gap-2 py-1 pl-2 text-sm"
              >
                <span className="w-6 shrink-0" />
                <Badge variant="secondary" className="shrink-0">
                  {line.phase}
                </Badge>
                <span className="min-w-0">
                  <span className="flex items-center gap-2">
                    {/* Track labels only add value once more than one line is active;
                        a single active line always renders untracked, even if the
                        backend stamped it with a type track. */}
                    {lines.size > 1 && line.track && (
                      <span className="shrink-0 text-xs font-medium text-muted-foreground">
                        {line.track}
                      </span>
                    )}
                    <span className="text-muted-foreground">
                      {line.message}
                    </span>
                    <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-muted-foreground" />
                    <span className="text-xs tabular-nums text-muted-foreground">
                      {Math.max(0, Math.round((now - line.at) / 1000))}s
                    </span>
                  </span>
                  {line.url && (
                    <code className="block break-all text-xs text-muted-foreground">
                      {line.method} {line.url}
                    </code>
                  )}
                </span>
              </div>
            ))}
        </div>
      )}

      {viewing?.errorBody && (
        <JsonViewerDialog
          data={parseBody(viewing.errorBody)}
          title={
            viewing.status != null
              ? `Server response (HTTP ${viewing.status})`
              : "Server response"
          }
          description={viewing.url ?? undefined}
          onClose={() => setViewing(null)}
        />
      )}
    </div>
  );
}

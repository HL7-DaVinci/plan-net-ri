import { CheckCircle2, FileWarning, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { JsonViewerDialog } from "@/components/json-viewer-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { CrawlStep } from "@/lib/api/types";
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
  const [current, setCurrent] = useState<{
    phase: string;
    message: string;
    at: number;
  } | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    setSteps([]);
    setDone(false);
    setCurrent(null);
    const source = new EventSource(
      `${getApiBaseUrl()}/api/crawl/${batchId}/stream`,
    );

    source.addEventListener("step", (event) => {
      const step = JSON.parse((event as MessageEvent).data) as CrawlStep;
      setSteps((prev) => [...prev, step]);
      // A persisted step means the previously announced operation finished.
      setCurrent(null);
    });
    source.addEventListener("progress", (event) => {
      const step = JSON.parse((event as MessageEvent).data) as CrawlStep;
      setCurrent({ phase: step.phase, message: step.message, at: Date.now() });
      setNow(Date.now());
    });
    source.addEventListener("complete", () => {
      setDone(true);
      setCurrent(null);
      source.close();
    });

    return () => source.close();
  }, [batchId]);

  // Tick once a second while an operation is in flight so its elapsed time updates.
  useEffect(() => {
    if (!current) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [current]);

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

      {steps.length === 0 && !current ? (
        <p className="text-sm text-muted-foreground">Waiting for steps...</p>
      ) : (
        <ol className="space-y-1">
          {steps.map((step) => (
            <li
              key={step.seq}
              className="flex items-start gap-2 rounded-md border-l-2 border-muted py-1 pl-2 text-sm"
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

      {current && !done && (
        <div className="flex items-center gap-2 rounded-md border-l-2 border-primary/50 py-1 pl-2 text-sm">
          <span className="w-6 shrink-0" />
          <Badge variant="secondary" className="shrink-0">
            {current.phase}
          </Badge>
          <span className="min-w-0 text-muted-foreground">
            {current.message}
          </span>
          <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-muted-foreground" />
          <span className="text-xs tabular-nums text-muted-foreground">
            {Math.max(0, Math.round((now - current.at) / 1000))}s
          </span>
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

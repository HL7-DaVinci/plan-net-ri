import { CheckCircle2, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import type { CrawlStep } from "@/lib/api/types";
import { getApiBaseUrl } from "@/lib/fhir-config";

function phaseVariant(phase: string): "default" | "secondary" | "destructive" {
  if (phase === "ERROR") return "destructive";
  if (phase === "DONE" || phase === "MANIFEST") return "default";
  return "secondary";
}

export function PlayByPlay({ batchId }: { batchId: string }) {
  const [steps, setSteps] = useState<CrawlStep[]>([]);
  const [done, setDone] = useState(false);

  useEffect(() => {
    setSteps([]);
    setDone(false);
    const source = new EventSource(
      `${getApiBaseUrl()}/api/crawl/${batchId}/stream`,
    );

    source.addEventListener("step", (event) => {
      const step = JSON.parse((event as MessageEvent).data) as CrawlStep;
      setSteps((prev) => [...prev, step]);
    });
    source.addEventListener("complete", () => {
      setDone(true);
      source.close();
    });
    source.onerror = () => {
      // Stream closed (or server gone); stop listening.
      setDone(true);
      source.close();
    };

    return () => source.close();
  }, [batchId]);

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        {done ? (
          <CheckCircle2 className="h-3.5 w-3.5 text-success" />
        ) : (
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
        )}
        {done ? "Complete" : "Streaming..."}
        <span>({steps.length} steps)</span>
      </div>

      {steps.length === 0 ? (
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
                <span className="flex flex-wrap gap-x-3 text-xs text-muted-foreground tabular-nums">
                  {step.status != null && <span>HTTP {step.status}</span>}
                  {step.ms != null && <span>{step.ms} ms</span>}
                  {step.count != null && (
                    <span>{step.count.toLocaleString()} resources</span>
                  )}
                </span>
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

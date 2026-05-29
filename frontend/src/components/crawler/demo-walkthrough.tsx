import {
  Check,
  ChevronDown,
  ChevronRight,
  Loader2,
  Play,
  RotateCcw,
  Sparkles,
} from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import type { UseDemoWalkthroughResult } from "@/hooks/use-demo-walkthrough";

interface DemoWalkthroughProps {
  demo: UseDemoWalkthroughResult;
  disabled?: boolean;
}

export function DemoWalkthrough({ demo, disabled }: DemoWalkthroughProps) {
  const { steps, currentStep, results, runningStep, runStep, reset } = demo;
  const allDone = currentStep >= steps.length;
  const [open, setOpen] = useState(true);

  const progressLabel = allDone
    ? "complete"
    : `step ${currentStep + 1} of ${steps.length}`;

  return (
    <Card>
      <Collapsible open={open} onOpenChange={setOpen}>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between gap-3">
            <CollapsibleTrigger className="flex flex-1 cursor-pointer items-center gap-2 text-left">
              {open ? (
                <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
              ) : (
                <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
              )}
              <Sparkles className="h-4 w-4 shrink-0 text-primary" />
              <CardTitle className="text-base">
                Guided demo: keep resources in sync
              </CardTitle>
              <span className="text-xs font-normal text-muted-foreground">
                ({progressLabel})
              </span>
            </CollapsibleTrigger>
            <Button
              variant="outline"
              size="sm"
              onClick={() => reset()}
              disabled={runningStep !== null || disabled}
            >
              <RotateCcw className="h-3.5 w-3.5 mr-1" />
              Reset demo
            </Button>
          </div>
        </CardHeader>
        <CollapsibleContent>
          <CardContent className="space-y-2">
            {steps.map((step, index) => {
              const isComplete = index < currentStep;
              const isCurrent = index === currentStep;
              const isRunning = runningStep === index;
              const result = results[index];

              return (
                <div
                  key={step.id}
                  className={`flex gap-3 rounded-lg border p-3 transition-colors ${
                    isCurrent
                      ? "border-primary/40 bg-primary/5"
                      : isComplete
                        ? "border-border bg-muted/30"
                        : "border-border/60 opacity-70"
                  }`}
                >
                  <div
                    className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-medium ${
                      isComplete
                        ? "bg-success text-white"
                        : isCurrent
                          ? "bg-primary text-primary-foreground"
                          : "bg-muted text-muted-foreground"
                    }`}
                  >
                    {isComplete ? <Check className="h-4 w-4" /> : index + 1}
                  </div>
                  <div className="min-w-0 flex-1 space-y-1">
                    <div className="flex items-center justify-between gap-3">
                      <span className="text-sm font-medium">{step.title}</span>
                      {isCurrent && (
                        <Button
                          size="sm"
                          className="h-7 shrink-0"
                          onClick={() => runStep(index)}
                          disabled={isRunning || disabled}
                        >
                          {isRunning ? (
                            <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />
                          ) : (
                            <Play className="h-3.5 w-3.5 mr-1" />
                          )}
                          {step.action}
                        </Button>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {step.description}
                    </p>
                    {result && (
                      <p className="text-xs font-medium text-foreground bg-muted/50 rounded px-2 py-1">
                        {result}
                      </p>
                    )}
                  </div>
                </div>
              );
            })}
            {allDone && (
              <p className="text-sm text-success font-medium pt-1">
                Demo complete. The local directory was kept in sync using only
                incremental updates and history-based deletion detection.
              </p>
            )}
          </CardContent>
        </CollapsibleContent>
      </Collapsible>
    </Card>
  );
}

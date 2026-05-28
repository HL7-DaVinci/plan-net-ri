import { Activity, Database, Loader2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";

interface ServerStatusBarProps {
  isLoading: boolean;
  isConnected: boolean;
  latency: number | undefined;
  fhirVersion: string | undefined;
  softwareName: string | undefined;
  softwareVersion: string | undefined;
  resourceCount: number;
}

export function ServerStatusBar({
  isLoading,
  isConnected,
  latency,
  fhirVersion,
  softwareName,
  softwareVersion,
  resourceCount,
}: ServerStatusBarProps) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 rounded-lg border bg-muted/30">
      <StatusIndicator isLoading={isLoading} isConnected={isConnected} />

      <Separator orientation="vertical" className="h-5 hidden sm:block" />

      <div className="flex items-center gap-2">
        <Activity className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-sm text-muted-foreground">
          {latency ? (
            <span className="metric-value font-medium text-foreground">
              {latency}ms
            </span>
          ) : (
            "-"
          )}
        </span>
      </div>

      <Separator orientation="vertical" className="h-5 hidden sm:block" />

      <div className="flex items-center gap-2">
        <Badge
          variant="outline"
          className="h-5 text-xs font-medium bg-primary/10 text-primary border-primary/20"
        >
          {fhirVersion || "-"}
        </Badge>
        {softwareName && (
          <span className="text-sm text-muted-foreground">
            {softwareName}
            {softwareVersion && (
              <span className="text-xs ml-1">v{softwareVersion}</span>
            )}
          </span>
        )}
      </div>

      <Separator orientation="vertical" className="h-5 hidden sm:block" />

      <div className="flex items-center gap-2">
        <Database className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-sm">
          <span className="metric-value font-medium">{resourceCount}</span>
          <span className="text-muted-foreground ml-1">types</span>
        </span>
      </div>
    </div>
  );
}

function StatusIndicator({
  isLoading,
  isConnected,
}: {
  isLoading: boolean;
  isConnected: boolean;
}) {
  if (isLoading) {
    return (
      <div className="flex items-center gap-2">
        <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" />
        <span className="text-sm text-muted-foreground">Connecting...</span>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2">
      <span
        className={cn(
          "status-dot",
          isConnected ? "bg-success" : "bg-destructive",
        )}
      />
      <span
        className={cn(
          "text-sm font-medium",
          isConnected ? "text-success" : "text-destructive",
        )}
      >
        {isConnected ? "Connected" : "Disconnected"}
      </span>
    </div>
  );
}

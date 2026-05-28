import { Loader2 } from "lucide-react";
import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useCapabilityStatement, useServerStatus } from "@/hooks/use-fhir-api";
import { useFhirServer } from "@/hooks/use-fhir-server";
import { cn } from "@/lib/utils";

interface ServerStatusProps {
  className?: string;
  showLatency?: boolean;
  showFhirVersion?: boolean;
}

export function ServerStatus({
  className,
  showLatency = true,
  showFhirVersion = true,
}: ServerStatusProps) {
  const { serverUrl } = useFhirServer();
  const { isConnected, isLoading, latency, error } = useServerStatus(serverUrl);
  const { data: capability } = useCapabilityStatement(serverUrl);

  // Track previous connection state to show toast notifications
  const prevConnected = useRef<boolean | null>(null);

  useEffect(() => {
    // Skip initial render
    if (prevConnected.current === null) {
      prevConnected.current = isConnected;
      return;
    }

    // Don't show toasts while loading
    if (isLoading) return;

    // Connection state changed
    if (isConnected && prevConnected.current === false) {
      toast.success("Connected to FHIR server", {
        description: latency ? `Response time: ${latency}ms` : undefined,
      });
    } else if (!isConnected && prevConnected.current === true) {
      toast.error("Disconnected from FHIR server", {
        description: error instanceof Error ? error.message : "Connection lost",
      });
    }

    prevConnected.current = isConnected;
  }, [isConnected, isLoading, latency, error]);

  const getStatusColor = () => {
    if (isLoading) return "bg-warning animate-pulse";
    if (isConnected) return "bg-success";
    return "bg-destructive";
  };

  const getStatusText = () => {
    if (isLoading) return "Connecting...";
    if (isConnected) return "Connected";
    return "Disconnected";
  };

  const getTooltipContent = () => {
    if (isLoading) return "Connecting to FHIR server...";
    if (isConnected) {
      return `Connected to FHIR server${latency ? ` (${latency}ms)` : ""}`;
    }
    return error instanceof Error
      ? `Connection failed: ${error.message}`
      : "Failed to connect to FHIR server";
  };

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div
          className={cn(
            "flex items-center gap-2 text-sm cursor-default",
            className,
          )}
        >
          {isLoading ? (
            <Loader2 className="h-3 w-3 animate-spin text-muted-foreground" />
          ) : (
            <span className={cn("status-dot", getStatusColor())} />
          )}
          <span
            className={cn(
              "text-muted-foreground",
              isConnected && "text-foreground",
            )}
          >
            {getStatusText()}
          </span>
          {showLatency && isConnected && latency && (
            <span className="text-muted-foreground text-xs metric-value">
              {latency}ms
            </span>
          )}
          {showFhirVersion && isConnected && capability?.fhirVersion && (
            <Badge
              variant="outline"
              className="h-5 text-xs font-medium bg-primary/10 text-primary border-primary/20"
            >
              {capability.fhirVersion}
            </Badge>
          )}
        </div>
      </TooltipTrigger>
      <TooltipContent side="bottom">
        <p>{getTooltipContent()}</p>
      </TooltipContent>
    </Tooltip>
  );
}

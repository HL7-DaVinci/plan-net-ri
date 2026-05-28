import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowRight, Database, Loader2, Server, XCircle } from "lucide-react";
import { useId, useMemo } from "react";
import { ServerStatusBar } from "@/components/server-status-bar";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  getResourceTypes,
  useCapabilityStatement,
  useResourceCounts,
  useServerStatus,
} from "@/hooks/use-fhir-api";
import { useFhirServer, useServerSelection } from "@/hooks/use-fhir-server";
import {
  getResourceIcon,
  groupResourcesByCategory,
} from "@/lib/resource-icons";

export const Route = createFileRoute("/")({
  component: Dashboard,
});

const categoryOrder = [
  "Individuals",
  "Entities",
  "Clinical",
  "Medications",
  "Workflow",
  "Financial",
  "Documents",
  "Questionnaires",
  "Conformance",
  "Other",
];

function Dashboard() {
  const { serverUrl, presetServers, setServerUrl, isCustomServer } =
    useFhirServer();
  const { isConnected, isDisconnected, isLoading, latency, error, refetch } =
    useServerStatus(serverUrl);
  const { data: capability } = useCapabilityStatement(serverUrl);
  const {
    customUrl,
    setCustomUrl,
    showCustomInput,
    isEditing,
    handleServerChange,
    handleCustomUrlSubmit,
  } = useServerSelection(setServerUrl, isCustomServer, serverUrl);

  const resourceTypes = getResourceTypes(capability);
  const { data: resourceCounts = {} } = useResourceCounts(
    serverUrl,
    isConnected,
  );
  const fhirServerId = useId();

  const categorizedResources = useMemo(
    () => groupResourcesByCategory(resourceTypes),
    [resourceTypes],
  );

  const sortedCategories = useMemo(() => {
    return categoryOrder.filter((cat) => categorizedResources[cat]?.length > 0);
  }, [categorizedResources]);

  return (
    <div className="p-6 space-y-6 max-w-6xl">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base flex items-center gap-2">
            <Server className="h-4 w-4" />
            Server Configuration
          </CardTitle>
          {isConnected && capability?.implementation?.description && (
            <CardDescription>
              {capability.implementation.description}
            </CardDescription>
          )}
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col sm:flex-row gap-3">
            <div className="flex-1 space-y-1.5">
              <label
                htmlFor={fhirServerId}
                className="text-xs font-medium text-muted-foreground"
              >
                FHIR Server
              </label>
              <Select
                value={isCustomServer ? "custom" : serverUrl}
                onValueChange={handleServerChange}
              >
                <SelectTrigger id={fhirServerId} className="h-9">
                  <SelectValue placeholder="Select a server" />
                </SelectTrigger>
                <SelectContent>
                  {presetServers.map((s) => (
                    <SelectItem key={s.url} value={s.url}>
                      {s.name}
                    </SelectItem>
                  ))}
                  <SelectItem value="custom">Custom URL...</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <Button
              variant="outline"
              size="sm"
              className="h-9 self-end"
              onClick={() => refetch()}
              disabled={isLoading}
            >
              {isLoading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                "Test Connection"
              )}
            </Button>
          </div>

          {showCustomInput && (
            <div className="flex gap-2">
              <Input
                placeholder="https://your-fhir-server.com/fhir"
                value={customUrl}
                onChange={(e) => setCustomUrl(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    handleCustomUrlSubmit();
                  }
                }}
                className="h-9"
              />
              {isEditing && (
                <Button
                  size="sm"
                  className="h-9"
                  onClick={handleCustomUrlSubmit}
                >
                  Connect
                </Button>
              )}
            </div>
          )}

          {isDisconnected && error && (
            <div className="flex items-start gap-2 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
              <XCircle className="h-4 w-4 mt-0.5 shrink-0" />
              <span>
                {error instanceof Error
                  ? error.message
                  : "Failed to connect to FHIR server"}
              </span>
            </div>
          )}
        </CardContent>
      </Card>

      <ServerStatusBar
        isLoading={isLoading}
        isConnected={isConnected}
        latency={latency}
        fhirVersion={capability?.fhirVersion}
        softwareName={capability?.software?.name}
        softwareVersion={capability?.software?.version}
        resourceCount={resourceTypes.length}
      />

      {isConnected && resourceTypes.length > 0 && (
        <Card className="relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-linear-to-bl from-primary/5 to-transparent rounded-bl-full pointer-events-none" />

          <CardHeader className="pb-3 relative">
            <div className="flex items-center justify-between">
              <div>
                <CardTitle className="text-base flex items-center gap-2">
                  <Database className="h-4 w-4 text-primary" />
                  Resource Types
                </CardTitle>
                <CardDescription className="text-xs">
                  Click to browse and explore FHIR resources
                </CardDescription>
              </div>
              <Button variant="outline" size="sm" asChild>
                <Link to="/resources" search={{}}>
                  View all
                  <ArrowRight className="h-4 w-4 ml-1" />
                </Link>
              </Button>
            </div>
          </CardHeader>
          <CardContent className="relative space-y-6">
            {sortedCategories.map((category) => {
              const resources = categorizedResources[category];
              if (!resources?.length) return null;

              return (
                <div key={category}>
                  <h3 className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-3">
                    {category}
                  </h3>
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
                    {resources.map((type) => {
                      const Icon = getResourceIcon(type);
                      return (
                        <Link
                          key={type}
                          to="/resources"
                          search={{ type }}
                          className="group flex items-center gap-2.5 p-2.5 rounded-lg border bg-card hover:bg-primary/5 hover:border-primary/20 transition-all text-sm"
                        >
                          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-muted/50 group-hover:bg-primary/10 transition-colors">
                            <Icon className="h-3.5 w-3.5 text-muted-foreground group-hover:text-primary transition-colors" />
                          </div>
                          <span className="min-w-0 truncate font-medium">
                            {type}
                          </span>
                          {resourceCounts[type] !== undefined &&
                            resourceCounts[type] > 0 && (
                              <span className="ml-auto tabular-nums text-xs text-muted-foreground">
                                {resourceCounts[type].toLocaleString()}
                              </span>
                            )}
                        </Link>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

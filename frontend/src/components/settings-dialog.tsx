import { CheckCircle, Loader2, Server, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { useServerStatus } from "@/hooks/use-fhir-api";
import { useFhirServer, useServerSelection } from "@/hooks/use-fhir-server";

interface SettingsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function SettingsDialog({ open, onOpenChange }: SettingsDialogProps) {
  const { serverUrl, presetServers, setServerUrl, isCustomServer } =
    useFhirServer();
  const { isConnected, isLoading, latency, error, refetch } =
    useServerStatus(serverUrl);
  const {
    customUrl,
    setCustomUrl,
    showCustomInput,
    isEditing,
    handleServerChange,
    handleCustomUrlSubmit,
  } = useServerSelection(setServerUrl, isCustomServer, serverUrl);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Server className="h-5 w-5" />
            Settings
          </DialogTitle>
          <DialogDescription>
            Configure your FHIR server connection
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <Label htmlFor="server-select">FHIR Server</Label>
            <Select
              value={isCustomServer ? "custom" : serverUrl}
              onValueChange={handleServerChange}
            >
              <SelectTrigger id="server-select">
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

          {showCustomInput && (
            <div className="space-y-2">
              <Label htmlFor="custom-url">Custom Server URL</Label>
              <div className="flex gap-2">
                <Input
                  id="custom-url"
                  placeholder="https://your-fhir-server.com/fhir"
                  value={customUrl}
                  onChange={(e) => setCustomUrl(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      handleCustomUrlSubmit();
                    }
                  }}
                  className="flex-1"
                />
                {isEditing && (
                  <Button onClick={handleCustomUrlSubmit} size="sm">
                    Connect
                  </Button>
                )}
              </div>
            </div>
          )}

          <Separator />

          <div className="space-y-2">
            <Label>Connection Status</Label>
            <div className="flex items-center justify-between p-3 rounded-md border bg-muted/50">
              <div className="flex items-center gap-2">
                {isLoading ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin text-warning" />
                    <span className="text-sm">Connecting...</span>
                  </>
                ) : isConnected ? (
                  <>
                    <CheckCircle className="h-4 w-4 text-success" />
                    <span className="text-sm text-success">Connected</span>
                    {latency && (
                      <span className="text-xs text-muted-foreground">
                        ({latency}ms)
                      </span>
                    )}
                  </>
                ) : (
                  <>
                    <XCircle className="h-4 w-4 text-destructive" />
                    <span className="text-sm text-destructive">
                      Disconnected
                    </span>
                  </>
                )}
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => refetch()}
                disabled={isLoading}
              >
                Test
              </Button>
            </div>

            {!isConnected && !isLoading && error && (
              <p className="text-xs text-destructive">
                {error instanceof Error
                  ? error.message
                  : "Failed to connect to FHIR server"}
              </p>
            )}

            <div className="text-xs text-muted-foreground">
              <span className="font-medium">URL:</span>{" "}
              <code className="bg-muted px-1 py-0.5 rounded text-xs break-all">
                {serverUrl}
              </code>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

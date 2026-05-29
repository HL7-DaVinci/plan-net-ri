import { Plus, Server } from "lucide-react";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { normalizeServerKey } from "@/lib/crawler/db";
import { formatTimestamp } from "@/lib/crawler/format";
import type { CrawlMeta, ScopeServer } from "@/lib/crawler/types";
import { FHIR_SERVERS } from "@/lib/fhir-config";

interface ServerScopeSelectorProps {
  scope: ScopeServer[];
  setScope: (scope: ScopeServer[]) => void;
  metas: Record<string, CrawlMeta>;
  disabled?: boolean;
}

export function ServerScopeSelector({
  scope,
  setScope,
  metas,
  disabled,
}: ServerScopeSelectorProps) {
  const [customUrl, setCustomUrl] = useState("");

  // Union of preset servers and any custom servers already in scope.
  const candidates = useMemo(() => {
    const map = new Map<string, ScopeServer>();
    for (const preset of FHIR_SERVERS) {
      const serverKey = normalizeServerKey(preset.url);
      map.set(serverKey, {
        serverKey,
        serverLabel: preset.name,
        url: serverKey,
      });
    }
    for (const srv of scope) {
      if (!map.has(srv.serverKey)) map.set(srv.serverKey, srv);
    }
    return [...map.values()];
  }, [scope]);

  const selectedKeys = new Set(scope.map((s) => s.serverKey));

  const toggle = (candidate: ScopeServer, checked: boolean) => {
    if (checked) {
      if (!selectedKeys.has(candidate.serverKey)) {
        setScope([...scope, candidate]);
      }
    } else {
      setScope(scope.filter((s) => s.serverKey !== candidate.serverKey));
    }
  };

  const addCustom = () => {
    const trimmed = customUrl.trim();
    if (!trimmed) return;
    const serverKey = normalizeServerKey(trimmed);
    if (selectedKeys.has(serverKey)) {
      setCustomUrl("");
      return;
    }
    setScope([...scope, { serverKey, serverLabel: serverKey, url: serverKey }]);
    setCustomUrl("");
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base flex items-center gap-2">
          <Server className="h-4 w-4" />
          Servers to crawl
        </CardTitle>
        <CardDescription>
          Select one or more Plan-Net servers. Data from every selected server
          is aggregated together.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-2">
          {candidates.map((candidate) => {
            const meta = metas[candidate.serverKey];
            return (
              <label
                key={candidate.serverKey}
                htmlFor={`scope-${candidate.serverKey}`}
                className="flex items-center gap-3 rounded-lg border p-2.5 cursor-pointer hover:bg-muted/40"
              >
                <Checkbox
                  id={`scope-${candidate.serverKey}`}
                  checked={selectedKeys.has(candidate.serverKey)}
                  onCheckedChange={(checked) =>
                    toggle(candidate, checked === true)
                  }
                  disabled={disabled}
                />
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium truncate">
                    {candidate.serverLabel}
                  </div>
                  <div className="text-xs text-muted-foreground truncate">
                    {candidate.url}
                  </div>
                </div>
                <div className="text-xs shrink-0 text-right">
                  <div className="text-muted-foreground">
                    {meta?.lastCrawlServerTime
                      ? `Last: ${formatTimestamp(meta.lastCrawlServerTime)}`
                      : "Not crawled"}
                  </div>
                  {meta?.historySupported === false && (
                    <div
                      className="text-amber-500"
                      title="Server does not support system-level _history; deletions cannot be detected"
                    >
                      no _history
                    </div>
                  )}
                </div>
              </label>
            );
          })}
        </div>

        <div className="flex gap-2">
          <Input
            placeholder="Add another server URL (e.g. https://example.org/fhir)"
            value={customUrl}
            onChange={(e) => setCustomUrl(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") addCustom();
            }}
            className="h-9"
            disabled={disabled}
          />
          <Button
            variant="outline"
            size="sm"
            className="h-9"
            onClick={addCustom}
            disabled={disabled || !customUrl.trim()}
          >
            <Plus className="h-4 w-4 mr-1" />
            Add
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

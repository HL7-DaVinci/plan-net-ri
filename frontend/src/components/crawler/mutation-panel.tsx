import type { FhirResource } from "fhir/r4";
import { Pencil, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { JsonEditorDialog } from "@/components/json-editor-dialog";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { getResourcesByServers } from "@/lib/crawler/db";
import type { ScopeServer, StoredResource } from "@/lib/crawler/types";
import { PLAN_NET_RESOURCE_TYPES } from "@/lib/plan-net-types";

interface MutationPanelProps {
  scope: ScopeServer[];
  mutate: (stored: StoredResource, next: FhirResource) => Promise<unknown>;
  remove: (stored: StoredResource) => Promise<void>;
  onChanged: () => void;
  refreshKey: number;
  disabled?: boolean;
}

function resourceLabel(stored: StoredResource): string {
  const name = (stored.resource as { name?: unknown }).name;
  const text =
    typeof name === "string"
      ? name
      : Array.isArray(name)
        ? "named record"
        : undefined;
  const base = `${stored.id}${text ? ` - ${text}` : ""}`;
  return stored.serverLabel ? `${base} (${stored.serverLabel})` : base;
}

export function MutationPanel({
  scope,
  mutate,
  remove,
  onChanged,
  refreshKey,
  disabled,
}: MutationPanelProps) {
  const [type, setType] = useState<string>("Organization");
  const [resources, setResources] = useState<StoredResource[]>([]);
  const [selectedKey, setSelectedKey] = useState<string>("");
  const [editing, setEditing] = useState<StoredResource | null>(null);
  const [busy, setBusy] = useState(false);

  const serverKeys = scope.map((s) => s.serverKey).join(",");

  // biome-ignore lint/correctness/useExhaustiveDependencies: refreshKey forces a reload after each crawl
  useEffect(() => {
    let cancelled = false;
    getResourcesByServers(serverKeys ? serverKeys.split(",") : [], type).then(
      (items) => {
        if (cancelled) return;
        setResources(items.slice(0, 200));
        setSelectedKey("");
      },
    );
    return () => {
      cancelled = true;
    };
  }, [serverKeys, type, refreshKey]);

  const selected = resources.find((r) => r.key === selectedKey);

  const handleSave = async (data: unknown) => {
    if (!selected) return;
    setBusy(true);
    try {
      await mutate(selected, data as FhirResource);
      toast.success(`Updated ${selected.resourceType}/${selected.id}`, {
        description: "Run an incremental crawl to see it picked up.",
      });
      setEditing(null);
      onChanged();
    } catch (error) {
      toast.error("Update failed", {
        description: error instanceof Error ? error.message : String(error),
      });
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!selected) return;
    if (
      !window.confirm(
        `Delete ${selected.resourceType}/${selected.id} on ${selected.serverLabel}? This removes it from the server.`,
      )
    ) {
      return;
    }
    setBusy(true);
    try {
      await remove(selected);
      toast.success(`Deleted ${selected.resourceType}/${selected.id}`, {
        description: "Run an incremental crawl to detect the deletion.",
      });
      setSelectedKey("");
      onChanged();
    } catch (error) {
      toast.error("Delete failed", {
        description: error instanceof Error ? error.message : String(error),
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base flex items-center gap-2">
          <Pencil className="h-4 w-4" />
          Change server data
        </CardTitle>
        <CardDescription>
          Update or delete a resource on the server, then run an incremental
          crawl to watch the change flow into the local directory. Tip: the
          server blocks deleting referenced resources, so delete leaf types like
          PractitionerRole.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-col sm:flex-row gap-2">
          <Select value={type} onValueChange={setType} disabled={disabled}>
            <SelectTrigger className="h-9 sm:w-48">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PLAN_NET_RESOURCE_TYPES.map((t) => (
                <SelectItem key={t} value={t}>
                  {t}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select
            value={selectedKey}
            onValueChange={setSelectedKey}
            disabled={disabled || resources.length === 0}
          >
            <SelectTrigger className="h-9 flex-1">
              <SelectValue
                placeholder={
                  resources.length === 0
                    ? "No resources, crawl first"
                    : "Select a resource"
                }
              />
            </SelectTrigger>
            <SelectContent>
              {resources.map((r) => (
                <SelectItem key={r.key} value={r.key}>
                  {resourceLabel(r)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={() => selected && setEditing(selected)}
            disabled={!selected || busy || disabled}
          >
            <Pencil className="h-4 w-4 mr-1" />
            Edit &amp; update
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="text-destructive hover:text-destructive"
            onClick={handleDelete}
            disabled={!selected || busy || disabled}
          >
            <Trash2 className="h-4 w-4 mr-1" />
            Delete
          </Button>
        </div>
      </CardContent>

      {editing && (
        <JsonEditorDialog
          data={editing.resource}
          title={`Edit ${editing.resourceType}/${editing.id}`}
          description="Saving sends a PUT to the server and bumps _lastUpdated."
          onClose={() => setEditing(null)}
          onSave={handleSave}
        />
      )}
    </Card>
  );
}

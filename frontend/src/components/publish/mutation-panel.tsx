import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import {
  JsonEditorDialog,
  useJsonEditor,
} from "@/components/json-editor-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { fhirFetch, fhirSend } from "@/hooks/use-fhir-api";
import { isOperationOutcome } from "@/lib/fhir-types";
import {
  PLAN_NET_RESOURCE_TYPES,
  type PlanNetResourceType,
} from "@/lib/plan-net-types";
import { SAMPLE_RESOURCES } from "@/lib/publish/sample-resources";

interface CreatedResource {
  id?: string;
}

const HAPI_STORAGE_RESPONSE_SYSTEM =
  "https://hapifhir.io/fhir/CodeSystem/hapi-fhir-storage-response-code";

const DELETE_NOOP_CODES = new Set([
  "SUCCESSFUL_DELETE_NOT_FOUND",
  "SUCCESSFUL_DELETE_ALREADY_DELETED",
]);

/**
 * HAPI returns 200 with one of these outcome codes when a delete took no
 * action (target never existed or was already deleted). Returns the issue's
 * diagnostics for display, or null when the delete actually happened.
 */
function deleteNoOpMessage(outcome: unknown): string | null {
  if (!isOperationOutcome(outcome) || !Array.isArray(outcome.issue)) {
    return null;
  }
  for (const issue of outcome.issue) {
    const noOp = issue.details?.coding?.find(
      (coding) =>
        coding.system === HAPI_STORAGE_RESPONSE_SYSTEM &&
        DELETE_NOOP_CODES.has(coding.code ?? ""),
    );
    if (noOp) {
      return issue.diagnostics ?? noOp.display ?? "Nothing was deleted";
    }
  }
  return null;
}

export function PublishMutationPanel({ serverUrl }: { serverUrl: string }) {
  const [type, setType] = useState<PlanNetResourceType>("Organization");
  const [resourceId, setResourceId] = useState("");
  const [hint, setHint] = useState<string | null>(null);
  const { editorData, openEditor, closeEditor } = useJsonEditor();

  const afterMutation = (action: string, id: string | undefined) => {
    setHint(
      `${action} ${type}/${id ?? "?"}. Watch the live watcher: the next publish should indicate the ${type} type has been re-exported.`,
    );
  };

  const handleCreate = () => {
    openEditor(
      SAMPLE_RESOURCES[type](),
      `Create ${type}`,
      async (data) => {
        try {
          const created = await fhirSend<CreatedResource>(
            `${serverUrl}/${type}`,
            "POST",
            data,
          );
          closeEditor();
          if (created?.id) {
            setResourceId(created.id);
          }
          afterMutation("Created", created?.id);
        } catch (e) {
          toast.error(e instanceof Error ? e.message : "Create failed");
        }
      },
      "Edit the sample resource, then Save to POST it to the server.",
    );
  };

  const handleEdit = async () => {
    const id = resourceId.trim();
    if (!id) {
      toast.error("Enter a resource id first");
      return;
    }
    try {
      const resource = await fhirFetch<Record<string, unknown>>(
        `${serverUrl}/${type}/${id}`,
      );
      openEditor(
        resource,
        `Update ${type}/${id}`,
        async (data) => {
          try {
            await fhirSend(`${serverUrl}/${type}/${id}`, "PUT", data);
            closeEditor();
            afterMutation("Updated", id);
          } catch (e) {
            toast.error(e instanceof Error ? e.message : "Update failed");
          }
        },
        "Edit the resource, then Save to PUT it back.",
      );
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Fetch failed");
    }
  };

  const handleDelete = async () => {
    const id = resourceId.trim();
    if (!id) {
      toast.error("Enter a resource id first");
      return;
    }
    try {
      const outcome = await fhirSend(`${serverUrl}/${type}/${id}`, "DELETE");
      const noOpMessage = deleteNoOpMessage(outcome);
      if (noOpMessage) {
        toast.error(noOpMessage);
        return;
      }
      afterMutation("Deleted", id);
      setResourceId("");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Delete failed");
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Trigger a Change</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          Create, update, or delete a Plan-Net resource on the selected server
          via standard FHIR REST, then watch the change flow into the next
          published snapshot.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={type}
            onValueChange={(value) => setType(value as PlanNetResourceType)}
          >
            <SelectTrigger className="w-56">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PLAN_NET_RESOURCE_TYPES.map((resourceType) => (
                <SelectItem key={resourceType} value={resourceType}>
                  {resourceType}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button size="sm" onClick={handleCreate}>
            <Plus className="h-4 w-4 mr-1" />
            Create sample
          </Button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Input
            className="w-56"
            placeholder="Resource id"
            value={resourceId}
            onChange={(e) => setResourceId(e.target.value)}
          />
          <Button variant="outline" size="sm" onClick={handleEdit}>
            <Pencil className="h-4 w-4 mr-1" />
            Load and edit
          </Button>
          <Button variant="destructive" size="sm" onClick={handleDelete}>
            <Trash2 className="h-4 w-4 mr-1" />
            Delete
          </Button>
        </div>
        {hint && <p className="text-sm">{hint}</p>}
        {editorData && (
          <JsonEditorDialog
            data={editorData.data}
            title={editorData.title}
            description={editorData.description}
            onClose={closeEditor}
            onSave={editorData.onSave}
          />
        )}
      </CardContent>
    </Card>
  );
}

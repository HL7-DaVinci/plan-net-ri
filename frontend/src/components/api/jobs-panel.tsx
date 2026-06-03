import { Loader2, Pencil, Play, Plus, Trash2, X } from "lucide-react";
import { useState } from "react";
import { StrategyInfo } from "@/components/api/strategy-info";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  useCreateJob,
  useDeleteJob,
  useJobs,
  useRunJob,
  useUpdateJob,
} from "@/hooks/use-api";
import type {
  CrawlStrategy,
  JobRequest,
  JobResponse,
  ServerScope,
} from "@/lib/api/types";

const STRATEGY_LABELS: Record<CrawlStrategy, string> = {
  SEARCH: "Incremental search",
  BULK_EXPORT: "Bulk $export",
  HISTORY: "History paging",
};

// Spring 6-field cron expressions (second minute hour day month day-of-week).
const CRON_PRESETS: { label: string; value: string }[] = [
  { label: "Every 15 min", value: "0 0/15 * * * *" },
  { label: "Hourly", value: "0 0 * * * *" },
  { label: "Daily 2 AM", value: "0 0 2 * * *" },
  { label: "Weekdays 6 AM", value: "0 0 6 * * MON-FRI" },
  { label: "Weekly (Sun 2 AM)", value: "0 0 2 * * SUN" },
  { label: "Manual only", value: "" },
];

interface FormState {
  id: string | null;
  name: string;
  serversText: string;
  strategy: CrawlStrategy;
  cronExpression: string;
  enabled: boolean;
}

const EMPTY_FORM: FormState = {
  id: null,
  name: "",
  serversText: "",
  strategy: "SEARCH",
  cronExpression: "",
  enabled: true,
};

function hostLabel(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

function parseServers(text: string): ServerScope[] {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .map((url) => ({ serverLabel: hostLabel(url), url }));
}

function serversToText(servers: ServerScope[]): string {
  return servers.map((s) => s.url).join("\n");
}

interface JobsPanelProps {
  selectedJobId: string | null;
  onSelectJob: (jobId: string) => void;
  onRunTriggered?: (batchId: string) => void;
}

export function JobsPanel({
  selectedJobId,
  onSelectJob,
  onRunTriggered,
}: JobsPanelProps) {
  const { data: jobs, isLoading } = useJobs();
  const createJob = useCreateJob();
  const updateJob = useUpdateJob();
  const deleteJob = useDeleteJob();
  const runJob = useRunJob();

  const [form, setForm] = useState<FormState | null>(null);

  const startCreate = () => setForm({ ...EMPTY_FORM });
  const startEdit = (job: JobResponse) =>
    setForm({
      id: job.id,
      name: job.name,
      serversText: serversToText(job.servers),
      strategy: job.strategy,
      cronExpression: job.cronExpression ?? "",
      enabled: job.enabled,
    });

  const submit = () => {
    if (!form) return;
    const servers = parseServers(form.serversText);
    if (!form.name.trim() || servers.length === 0) return;
    const body: JobRequest = {
      name: form.name.trim(),
      servers,
      strategy: form.strategy,
      cronExpression: form.cronExpression.trim() || null,
      enabled: form.enabled,
    };
    if (form.id) {
      updateJob.mutate(
        { id: form.id, body },
        { onSuccess: () => setForm(null) },
      );
    } else {
      createJob.mutate(body, { onSuccess: () => setForm(null) });
    }
  };

  const saving = createJob.isPending || updateJob.isPending;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          Define one or more crawl jobs. Each acquires a directory snapshot and
          publishes a manifest.
        </p>
        {!form && (
          <Button size="sm" onClick={startCreate} className="cursor-pointer">
            <Plus className="h-4 w-4 mr-1" />
            New job
          </Button>
        )}
      </div>

      {form && (
        <div className="rounded-lg border bg-card p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold">
              {form.id ? "Edit job" : "New job"}
            </h3>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setForm(null)}
              className="cursor-pointer"
            >
              <X className="h-4 w-4" />
            </Button>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="job-name">Name</Label>
            <Input
              id="job-name"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="Nightly directory sync"
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="job-servers">Server URLs (one per line)</Label>
            <Textarea
              id="job-servers"
              value={form.serversText}
              onChange={(e) =>
                setForm({ ...form, serversText: e.target.value })
              }
              placeholder="http://localhost:8080/fhir"
              rows={3}
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label>Strategy</Label>
              <Select
                value={form.strategy}
                onValueChange={(v) =>
                  setForm({ ...form, strategy: v as CrawlStrategy })
                }
              >
                <SelectTrigger className="cursor-pointer">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {(Object.keys(STRATEGY_LABELS) as CrawlStrategy[]).map(
                    (s) => (
                      <SelectItem key={s} value={s}>
                        {STRATEGY_LABELS[s]}
                      </SelectItem>
                    ),
                  )}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="job-cron">Cron (optional)</Label>
              <Input
                id="job-cron"
                value={form.cronExpression}
                onChange={(e) =>
                  setForm({ ...form, cronExpression: e.target.value })
                }
                placeholder="0 0 2 * * *"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <span className="text-xs text-muted-foreground">
              Schedule presets (click to fill the cron field)
            </span>
            <div className="flex flex-wrap gap-1.5">
              {CRON_PRESETS.map((preset) => (
                <Button
                  key={preset.label}
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    setForm({ ...form, cronExpression: preset.value })
                  }
                  className="cursor-pointer"
                >
                  {preset.label}
                </Button>
              ))}
            </div>
          </div>

          <StrategyInfo strategy={form.strategy} />

          <div className="flex items-center gap-2">
            <Checkbox
              id="job-enabled"
              checked={form.enabled}
              onCheckedChange={(checked) =>
                setForm({ ...form, enabled: checked === true })
              }
            />
            <Label htmlFor="job-enabled" className="cursor-pointer">
              Enabled (scheduled when a cron is set)
            </Label>
          </div>

          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => setForm(null)}
              className="cursor-pointer"
            >
              Cancel
            </Button>
            <Button
              onClick={submit}
              disabled={saving}
              className="cursor-pointer"
            >
              {saving && <Loader2 className="h-4 w-4 mr-1 animate-spin" />}
              {form.id ? "Save" : "Create"}
            </Button>
          </div>
        </div>
      )}

      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading jobs...</p>
      ) : !jobs || jobs.length === 0 ? (
        <p className="text-sm text-muted-foreground">No crawl jobs yet.</p>
      ) : (
        <div className="space-y-2">
          {jobs.map((job) => (
            <button
              key={job.id}
              type="button"
              onClick={() => onSelectJob(job.id)}
              className={`w-full cursor-pointer rounded-lg border p-3 text-left transition-colors hover:bg-accent/50 ${
                selectedJobId === job.id ? "border-primary bg-accent/40" : ""
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium truncate">{job.name}</span>
                    {job.running && (
                      <Badge variant="default" className="gap-1">
                        <Loader2 className="h-3 w-3 animate-spin" />
                        running
                      </Badge>
                    )}
                    {!job.enabled && (
                      <Badge variant="secondary">disabled</Badge>
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <Badge variant="outline">
                      {STRATEGY_LABELS[job.strategy]}
                    </Badge>
                    <span>
                      {job.servers.length} server
                      {job.servers.length === 1 ? "" : "s"}
                    </span>
                    {job.cronExpression && (
                      <span>cron: {job.cronExpression}</span>
                    )}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-1">
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Run now"
                    disabled={runJob.isPending}
                    onClick={(e) => {
                      e.stopPropagation();
                      runJob.mutate(job.id, {
                        onSuccess: (data) => onRunTriggered?.(data.batchId),
                      });
                    }}
                    className="cursor-pointer"
                  >
                    <Play className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Edit"
                    onClick={(e) => {
                      e.stopPropagation();
                      startEdit(job);
                    }}
                    className="cursor-pointer"
                  >
                    <Pencil className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Delete"
                    onClick={(e) => {
                      e.stopPropagation();
                      deleteJob.mutate(job.id);
                    }}
                    className="cursor-pointer text-destructive"
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

import {
  CirclePlay,
  Loader2,
  Pause,
  Pencil,
  Play,
  Plus,
  Trash2,
  X,
} from "lucide-react";
import { useState } from "react";
import { StrategyInfo } from "@/components/api/strategy-info";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
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
import { Textarea } from "@/components/ui/textarea";
import {
  useCreateJob,
  useDeleteJob,
  useJobs,
  usePauseJob,
  useResumeJob,
  useRunJob,
  useUpdateJob,
} from "@/hooks/use-api";
import type {
  CrawlStrategy,
  JobRequest,
  JobResponse,
  ServerScope,
} from "@/lib/api/types";
import { getStoredServerUrl } from "@/lib/fhir-config";

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
  runNow: boolean;
}

const EMPTY_FORM: FormState = {
  id: null,
  name: "",
  serversText: "",
  strategy: "SEARCH",
  cronExpression: "",
  enabled: true,
  runNow: true,
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
}

export function JobsPanel({ selectedJobId, onSelectJob }: JobsPanelProps) {
  const { data: jobs, isLoading } = useJobs();
  const createJob = useCreateJob();
  const updateJob = useUpdateJob();
  const deleteJob = useDeleteJob();
  const runJob = useRunJob();
  const pauseJob = usePauseJob();
  const resumeJob = useResumeJob();

  const [form, setForm] = useState<FormState | null>(null);
  const [jobToDelete, setJobToDelete] = useState<JobResponse | null>(null);

  // Pre-fill with the configured FHIR server so a new job is ready to run as-is.
  const startCreate = () =>
    setForm({ ...EMPTY_FORM, serversText: getStoredServerUrl() });
  const startEdit = (job: JobResponse) =>
    setForm({
      id: job.id,
      name: job.name,
      serversText: serversToText(job.servers),
      strategy: job.strategy,
      cronExpression: job.cronExpression ?? "",
      enabled: job.enabled,
      runNow: false,
    });

  const submit = () => {
    if (!form) return;
    const servers = parseServers(form.serversText);
    if (!form.name.trim() || servers.length === 0) return;
    const cronExpression = form.cronExpression.trim() || null;
    const body: JobRequest = {
      name: form.name.trim(),
      servers,
      strategy: form.strategy,
      cronExpression,
      // Enabled only gates the scheduler, so a manual-only job is always submitted enabled.
      enabled: cronExpression ? form.enabled : true,
    };
    if (form.id) {
      updateJob.mutate(
        { id: form.id, body },
        { onSuccess: () => setForm(null) },
      );
    } else {
      const runNow = !body.cronExpression && form.runNow;
      createJob.mutate(body, {
        onSuccess: (created) => {
          setForm(null);
          if (runNow) {
            // Select the new job so the live play-by-play panel follows its run.
            onSelectJob(created.id);
            runJob.mutate(created.id);
          }
        },
      });
    }
  };

  const saving = createJob.isPending || updateJob.isPending;

  // Resolve against the live list so a job that started running after the dialog
  // opened is reflected here, not the stale snapshot captured on click.
  const pendingDeleteJob = jobToDelete
    ? (jobs?.find((j) => j.id === jobToDelete.id) ?? jobToDelete)
    : null;

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

          {form.cronExpression.trim() !== "" && (
            <div className="flex items-center gap-2">
              <Checkbox
                id="job-enabled"
                checked={form.enabled}
                onCheckedChange={(checked) =>
                  setForm({ ...form, enabled: checked === true })
                }
              />
              <Label htmlFor="job-enabled" className="cursor-pointer">
                Enabled (runs on the schedule)
              </Label>
            </div>
          )}

          {!form.id && !form.cronExpression.trim() && (
            <div className="flex items-center gap-2">
              <Checkbox
                id="job-run-now"
                checked={form.runNow}
                onCheckedChange={(checked) =>
                  setForm({ ...form, runNow: checked === true })
                }
              />
              <Label htmlFor="job-run-now" className="cursor-pointer">
                Run immediately after creating
              </Label>
            </div>
          )}

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
            <div
              key={job.id}
              className={`rounded-lg border transition-colors hover:bg-accent/50 ${
                selectedJobId === job.id ? "border-primary bg-accent/40" : ""
              }`}
            >
              <div className="flex items-start justify-between gap-3 p-3">
                <button
                  type="button"
                  onClick={() => onSelectJob(job.id)}
                  className="min-w-0 flex-1 cursor-pointer space-y-1 text-left"
                >
                  <div className="flex items-center gap-2">
                    <span className="font-medium truncate">{job.name}</span>
                    {job.running && (
                      <Badge variant="default" className="gap-1">
                        <Loader2 className="h-3 w-3 animate-spin" />
                        running
                      </Badge>
                    )}
                    {!job.enabled && job.cronExpression && (
                      <Badge variant="secondary">paused</Badge>
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
                    {job.cronExpression ? (
                      <span>cron: {job.cronExpression}</span>
                    ) : (
                      <Badge
                        variant="outline"
                        title="No schedule; runs only when triggered with the Run button"
                      >
                        manual only
                      </Badge>
                    )}
                  </div>
                </button>
                <div className="flex shrink-0 items-center gap-1">
                  <Button
                    variant="ghost"
                    size="icon"
                    title={
                      job.running ? "A crawl is already running" : "Run now"
                    }
                    disabled={runJob.isPending || job.running}
                    onClick={(e) => {
                      e.stopPropagation();
                      // Select the job so the live play-by-play panel follows its run.
                      onSelectJob(job.id);
                      runJob.mutate(job.id);
                    }}
                    className="cursor-pointer"
                  >
                    <Play className="h-4 w-4" />
                  </Button>
                  {/* Pause and resume only make sense for jobs with a schedule. */}
                  {job.cronExpression &&
                    (job.enabled ? (
                      <Button
                        variant="ghost"
                        size="icon"
                        title="Pause schedule"
                        disabled={pauseJob.isPending}
                        onClick={(e) => {
                          e.stopPropagation();
                          pauseJob.mutate(job.id);
                        }}
                        className="cursor-pointer"
                      >
                        <Pause className="h-4 w-4" />
                      </Button>
                    ) : (
                      <Button
                        variant="ghost"
                        size="icon"
                        title="Resume schedule"
                        disabled={resumeJob.isPending}
                        onClick={(e) => {
                          e.stopPropagation();
                          resumeJob.mutate(job.id);
                        }}
                        className="cursor-pointer"
                      >
                        <CirclePlay className="h-4 w-4" />
                      </Button>
                    ))}
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
                      setJobToDelete(job);
                    }}
                    className="cursor-pointer text-destructive"
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      <Dialog
        open={jobToDelete !== null}
        onOpenChange={(open) => {
          if (!open) setJobToDelete(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete this crawl job?</DialogTitle>
            <DialogDescription>
              This permanently removes{" "}
              <span className="font-medium text-foreground">
                {pendingDeleteJob?.name}
              </span>{" "}
              along with all of its run history and published manifests. This
              cannot be undone.
            </DialogDescription>
          </DialogHeader>
          {pendingDeleteJob?.running && (
            <p className="text-sm text-destructive">
              This job is currently running. Deleting it will stop the active
              crawl.
            </p>
          )}
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setJobToDelete(null)}
              className="cursor-pointer"
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteJob.isPending}
              onClick={() => {
                if (!pendingDeleteJob) return;
                deleteJob.mutate(pendingDeleteJob.id, {
                  onSuccess: () => setJobToDelete(null),
                });
              }}
              className="cursor-pointer"
            >
              {deleteJob.isPending && (
                <Loader2 className="h-4 w-4 mr-1 animate-spin" />
              )}
              Delete job
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

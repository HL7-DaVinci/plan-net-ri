import { useJobStats } from "@/hooks/use-api";

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)} s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${Math.round(seconds % 60)}s`;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
        {label}
      </div>
      <div className="truncate font-semibold tabular-nums">{value}</div>
    </div>
  );
}

export function JobStats({ jobId }: { jobId: string | null }) {
  const { data, isLoading } = useJobStats(jobId ?? undefined);

  if (!jobId) {
    return (
      <p className="text-sm text-muted-foreground">
        Select a job to see its stats.
      </p>
    );
  }
  if (isLoading) {
    return <p className="text-sm text-muted-foreground">Loading stats...</p>;
  }
  if (!data) {
    return null;
  }

  const hasManifests = data.manifestCount > 0;
  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-4">
      <Stat
        label="Manifests built"
        value={data.manifestCount.toLocaleString()}
      />
      <Stat
        label="Total build time"
        value={formatDuration(data.totalBuildMs)}
      />
      <Stat
        label="Avg build time"
        value={hasManifests ? formatDuration(data.avgBuildMs) : "-"}
      />
      <Stat
        label="Last build"
        value={hasManifests ? formatDuration(data.lastBuildMs) : "-"}
      />
      <Stat
        label="Runs (ok / err)"
        value={`${data.completedRuns} / ${data.erroredRuns}`}
      />
      <Stat
        label="Latest snapshot"
        value={`${data.latestTotalResources.toLocaleString()} res`}
      />
      <Stat
        label="Records crawled"
        value={data.totalRecords.toLocaleString()}
      />
      <Stat label="Data fetched" value={formatBytes(data.totalBytes)} />
    </div>
  );
}

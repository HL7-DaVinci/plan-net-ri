import { useOverallStats } from "@/hooks/use-api";

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

export function OverallStats() {
  const { data, isLoading } = useOverallStats();

  if (isLoading) {
    return (
      <p className="text-sm text-muted-foreground">Loading statistics...</p>
    );
  }
  if (!data) {
    return null;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-4">
        <Stat
          label="Resources tracked"
          value={data.totalResources.toLocaleString()}
        />
        <Stat label="Servers" value={data.serverCount.toLocaleString()} />
        <Stat label="Jobs" value={data.jobCount.toLocaleString()} />
        <Stat label="Manifests" value={data.manifestCount.toLocaleString()} />
      </div>

      {data.byType.length > 0 && (
        <div>
          <div className="mb-1 text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
            Resources by type
          </div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1 sm:grid-cols-3">
            {data.byType.map((t) => (
              <div
                key={t.type}
                className="flex items-center justify-between gap-2 text-sm"
              >
                <span className="truncate text-muted-foreground">{t.type}</span>
                <span className="shrink-0 font-medium tabular-nums">
                  {t.count.toLocaleString()}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

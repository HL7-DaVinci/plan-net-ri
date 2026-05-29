/** Format a byte count into a human-readable string (e.g. "1.2 MB"). */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}`;
}

/** Format a duration in milliseconds (e.g. "1.2s" or "340ms"). */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

/** Format crawl throughput, or an empty string when not meaningful. */
export function formatRate(records: number, durationMs: number): string {
  if (records <= 0 || durationMs <= 0) return "";
  const perSecond = Math.round(records / (durationMs / 1000));
  return `${perSecond.toLocaleString()} records/s`;
}

/** Format an ISO timestamp as a locale string, or a dash when missing. */
export function formatTimestamp(iso: string | undefined): string {
  if (!iso) return "-";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleString();
}

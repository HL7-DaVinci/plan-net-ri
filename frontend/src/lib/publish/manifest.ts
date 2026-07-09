/** The $bulk-publish manifest body served at {fhirBase}/$bulk-publish. */
export interface PublishManifest {
  manifestType: string;
  transactionTime: string;
  updateCadence?: string;
  requiresAccessToken?: boolean;
  output: PublishOutputEntry[];
}

export interface PublishOutputEntry {
  type: string;
  url: string;
  count: number;
  fileSize: number;
}

/** One entry from GET /api/publish/snapshots (plan-net-ri deployments only). */
export interface SnapshotListing {
  id: string;
  transactionTime: string;
  current: boolean;
  files: SnapshotFile[];
}

export interface SnapshotFile {
  type: string;
  count: number;
  fileSize: number;
  /** The snapshot directory that physically holds this file; differs when reused. */
  snapshotId: string;
}

const ISO_DURATION =
  /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/i;

export function parseIso8601DurationMs(
  value: string | undefined | null,
): number | null {
  if (!value) return null;
  const match = ISO_DURATION.exec(value.trim());
  if (!match || (!match[1] && !match[2] && !match[3])) return null;
  const hours = Number(match[1] ?? 0);
  const minutes = Number(match[2] ?? 0);
  const seconds = Number(match[3] ?? 0);
  return Math.round((hours * 3600 + minutes * 60 + seconds) * 1000);
}

const POLL_FLOOR_MS = 5_000;
const POLL_DEFAULT_MS = 30_000;

/** Watcher poll delay: the advertised cadence with a floor, or a default when unusable. */
export function pollDelayMs(updateCadence: string | undefined): number {
  const parsed = parseIso8601DurationMs(updateCadence);
  if (parsed === null || parsed <= 0) return POLL_DEFAULT_MS;
  return Math.max(parsed, POLL_FLOOR_MS);
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

/** A curl command reproducing the watcher's manifest fetch. */
export function buildManifestCurl(url: string, etag?: string | null): string {
  const parts = ["curl -i", "-H 'Accept: application/json'"];
  if (etag) {
    parts.push(`-H ${shellQuote(`If-None-Match: ${etag}`)}`);
  }
  parts.push(shellQuote(url));
  return parts.join(" ");
}

/** Origin of a plan-net-ri deployment's /api endpoints, derived from its FHIR base URL. */
export function publishApiBase(serverUrl: string): string {
  return serverUrl.replace(/\/fhir\/?$/, "").replace(/\/+$/, "");
}

/** Whether a snapshot exported this file itself or reuses an earlier snapshot's file. */
export function fileOrigin(
  snapshotId: string,
  file: SnapshotFile,
): "exported" | "reused" {
  return file.snapshotId === snapshotId ? "exported" : "reused";
}

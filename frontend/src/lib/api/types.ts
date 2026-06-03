export type CrawlStrategy = "BULK_EXPORT" | "HISTORY" | "SEARCH";

export interface ServerScope {
  serverKey?: string | null;
  serverLabel: string;
  url: string;
}

export interface JobRequest {
  name: string;
  servers: ServerScope[];
  strategy: CrawlStrategy;
  cronExpression?: string | null;
  enabled?: boolean;
}

export interface JobResponse {
  id: string;
  name: string;
  servers: ServerScope[];
  strategy: CrawlStrategy;
  cronExpression: string | null;
  enabled: boolean;
  running: boolean;
  lastRunAt: string | null;
  nextRunAt: string | null;
  createdAt: string | null;
}

export interface RunResponse {
  id: string;
  jobId: string;
  batchId: string;
  serverKey: string;
  serverLabel: string;
  mode: string;
  startedAt: string;
  serverTimeAtStart: string | null;
  durationMs: number;
  status: string;
  added: number;
  updated: number;
  deleted: number;
  records: number;
  bytes: number;
  requests: number;
  pages: number;
  historySupported: boolean | null;
  error: string | null;
}

export interface RunPage {
  runs: RunResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ManifestSummary {
  id: string;
  jobId: string;
  jobName: string | null;
  batchId: string;
  transactionTime: string;
  generatedAt: string;
  totalResources: number;
  windowSince: string | null;
  buildDurationMs: number;
}

export interface JobStats {
  jobId: string;
  manifestCount: number;
  totalBuildMs: number;
  avgBuildMs: number;
  lastBuildMs: number;
  runCount: number;
  completedRuns: number;
  erroredRuns: number;
  totalRecords: number;
  totalBytes: number;
  lastRunAt: string | null;
  latestTotalResources: number;
}

export interface ManifestOutputEntry {
  type: string;
  url: string;
  count: number;
}

export interface ManifestJson {
  transactionTime: string;
  // Omitted for SEARCH (no single kick-off URL); deprecated in the Bulk Data IG.
  request?: string;
  requiresAccessToken: boolean;
  output: ManifestOutputEntry[];
  error: unknown[];
}

export interface RunTriggerResponse {
  batchId: string;
}

export interface CrawlStep {
  seq: number;
  phase: string;
  message: string;
  method: string | null;
  url: string | null;
  status: number | null;
  ms: number | null;
  bytes: number | null;
  count: number | null;
  serverKey: string | null;
  at: string;
}

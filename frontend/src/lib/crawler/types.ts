import type { FhirResource } from "fhir/r4";

/** A single resource as persisted in the local aggregate store. */
export interface StoredResource {
  /** Primary key: `${serverKey}|${resourceType}/${id}` */
  key: string;
  /** Normalized server URL (trailing slash stripped) */
  serverKey: string;
  /** Friendly label for the source server (for the Source column) */
  serverLabel: string;
  resourceType: string;
  id: string;
  versionId?: string;
  lastUpdated?: string;
  resource: FhirResource;
}

export type CrawlMode = "full" | "incremental";

/** A server selected for crawling. */
export interface ScopeServer {
  serverKey: string;
  serverLabel: string;
  /** Normalized base URL (no trailing slash) */
  url: string;
}

export type CrawlScope = ScopeServer[];

export interface ServerTimeResult {
  iso: string;
  source: "date-header" | "bundle-meta" | "client-fallback";
}

/** One row in the live FHIR request log. */
export interface RequestLogEntry {
  id: string;
  serverKey: string;
  serverLabel: string;
  method: string;
  url: string;
  status: number;
  ms: number;
  bytes: number;
  at: string;
  /** Request payload for writes: the sent resource (PUT) or affected resource (DELETE). */
  requestBody?: unknown;
  /** Server response body for writes (the stored resource or OperationOutcome). */
  responseBody?: unknown;
}

export interface PerTypeDiff {
  added: number;
  updated: number;
  deleted: number;
}

export interface CrawlDiff {
  added: number;
  updated: number;
  deleted: number;
  /** Keys actually removed from the store (verifiable deletion count) */
  deletedKeys: string[];
  perType: Record<string, PerTypeDiff>;
}

/** Per-server crawl metadata persisted between sessions. */
export interface CrawlMeta {
  serverKey: string;
  serverLabel?: string;
  /** Authoritative server time captured at the start of the last successful crawl */
  lastCrawlServerTime?: string;
  lastCrawlMode?: CrawlMode;
  lastCrawlAt?: string;
  historySupported?: boolean;
}

export interface RunMetrics {
  /** Resources returned by search in this run */
  records: number;
  /** Total response bytes across all requests in this run */
  bytes: number;
  requests: number;
  pages: number;
}

export interface CrawlRun {
  id: string;
  /** Groups the per-server runs produced by a single crawl operation. */
  batchId?: string;
  serverKey: string;
  serverLabel: string;
  mode: CrawlMode;
  startedAt: string;
  serverTimeAtStart?: string;
  durationMs: number;
  metrics: RunMetrics;
  diff: CrawlDiff;
  status: "completed" | "aborted" | "error";
  error?: string;
}

/** Combined totals across all per-server runs of the latest crawl operation. */
export interface CrawlBatchSummary {
  batchId?: string;
  startedAt: string;
  serverTimeAtStart?: string;
  mode: CrawlMode | "mixed";
  hasIncremental: boolean;
  status: "completed" | "aborted" | "error";
  serverCount: number;
  added: number;
  updated: number;
  deleted: number;
  records: number;
  durationMs: number;
  pages: number;
  requests: number;
}

export interface AggregateStats {
  total: number;
  perType: Record<string, number>;
  /** serverKey -> resource count */
  perServer: Record<string, number>;
}

export interface CrawlProgress {
  serverKey: string;
  serverLabel: string;
  phase:
    | "starting"
    | "resources"
    | "history"
    | "applying"
    | "done"
    | "aborted"
    | "error";
  currentType?: string;
  perType: Record<string, { fetched: number; pages: number; done: boolean }>;
  totalFetched: number;
  requests: number;
  message?: string;
}

export interface DeletionEntry {
  resourceType: string;
  id: string;
}

/**
 * Raw output of a single-server crawl, before diffing against the store.
 * The engine stays free of IndexedDB; diff + persistence happen in the hook.
 */
export interface RawCrawlResult {
  serverKey: string;
  serverLabel: string;
  mode: CrawlMode;
  startedAt: string;
  serverTime: ServerTimeResult;
  durationMs: number;
  metrics: RunMetrics;
  fetched: StoredResource[];
  deletions: DeletionEntry[];
  historySupported: boolean;
  status: "completed" | "aborted" | "error";
  error?: string;
}

export interface EfficiencySide {
  records: number;
  bytes: number;
  requests: number;
}

export interface EfficiencyComparison {
  full?: EfficiencySide;
  incremental?: EfficiencySide;
  /** Byte-based savings of incremental vs full, 0..100 */
  savingsPct?: number;
}

export interface DuplicateMember {
  serverKey: string;
  serverLabel: string;
  id: string;
  key: string;
}

export interface DuplicateGroup {
  identifierSystem: string;
  identifierValue: string;
  resourceType: string;
  members: DuplicateMember[];
}

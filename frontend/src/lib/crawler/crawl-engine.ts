import type { Bundle, FhirResource } from "fhir/r4";
import { PLAN_NET_RESOURCE_TYPES } from "@/lib/plan-net-types";
import { normalizeServerKey, resourceKey } from "./db";
import { HistoryUnsupportedError, scanDeletions } from "./deletion-scan";
import { type TimedResult, timedFhirFetch } from "./http";
import { getServerTime } from "./server-time";
import type {
  CrawlMode,
  CrawlProgress,
  DeletionEntry,
  RawCrawlResult,
  RequestLogEntry,
  StoredResource,
} from "./types";

/** Hard cap on pages per type to guard against pagination loops. */
const MAX_PAGES_PER_TYPE = 1000;

export interface RunCrawlOptions {
  serverUrl: string;
  serverLabel: string;
  mode: CrawlMode;
  /** Required for incremental mode: the server timestamp of the last crawl */
  since?: string;
  types?: readonly string[];
  pageSize?: number;
  signal: AbortSignal;
  onProgress?: (progress: CrawlProgress) => void;
  onRequest?: (entry: RequestLogEntry) => void;
}

function makeStored(
  resource: FhirResource,
  serverKey: string,
  serverLabel: string,
): StoredResource | null {
  if (!resource.id) return null;
  return {
    key: resourceKey(serverKey, resource.resourceType, resource.id),
    serverKey,
    serverLabel,
    resourceType: resource.resourceType,
    id: resource.id,
    versionId: resource.meta?.versionId,
    lastUpdated: resource.meta?.lastUpdated,
    resource,
  };
}

class AbortedError extends Error {}

/**
 * Crawl a single server for the given resource types (full or incremental) and
 * collect deletions from system history (incremental only). Stays free of
 * IndexedDB: diffing and persistence are the caller's responsibility.
 */
export async function runCrawl(
  options: RunCrawlOptions,
): Promise<RawCrawlResult> {
  const { serverUrl, serverLabel, mode, since, signal, onProgress, onRequest } =
    options;
  const pageSize = options.pageSize ?? 200;
  const types = options.types ?? PLAN_NET_RESOURCE_TYPES;
  const serverKey = normalizeServerKey(serverUrl);
  const startedAt = new Date().toISOString();
  const start = performance.now();

  const perType: CrawlProgress["perType"] = {};
  for (const type of types) {
    perType[type] = { fetched: 0, pages: 0, done: false };
  }
  let totalRequests = 0;
  let totalBytes = 0;
  let totalPages = 0;
  let totalFetched = 0;

  const emit = (phase: CrawlProgress["phase"], currentType?: string) => {
    onProgress?.({
      serverKey,
      serverLabel,
      phase,
      currentType,
      perType: { ...perType },
      totalFetched,
      requests: totalRequests,
    });
  };

  const log = (
    method: string,
    url: string,
    status: number,
    ms: number,
    bytes: number,
  ) => {
    onRequest?.({
      id: crypto.randomUUID(),
      serverKey,
      serverLabel,
      method,
      url,
      status,
      ms,
      bytes,
      at: new Date().toISOString(),
    });
  };

  emit("starting");
  const serverTime = await getServerTime(serverUrl, signal);

  const crawlType = async (type: string): Promise<StoredResource[]> => {
    const collected: StoredResource[] = [];
    const seen = new Set<string>();
    const params = new URLSearchParams();
    params.set("_count", String(pageSize));
    params.set("_sort", "_lastUpdated");
    if (mode === "incremental" && since) {
      params.set("_lastUpdated", `gt${since}`);
    }
    let nextUrl: string | undefined =
      `${serverUrl}/${type}?${params.toString()}`;

    while (nextUrl) {
      if (signal.aborted) throw new AbortedError();
      if (seen.has(nextUrl)) break;
      if (perType[type].pages >= MAX_PAGES_PER_TYPE) break;
      seen.add(nextUrl);

      const result: TimedResult<Bundle> = await timedFhirFetch<Bundle>(
        nextUrl,
        { signal },
      );
      totalRequests += 1;
      totalBytes += result.bytes;
      totalPages += 1;
      perType[type].pages += 1;
      log("GET", nextUrl, result.status, result.ms, result.bytes);

      const bundle = result.data;
      for (const entry of bundle.entry ?? []) {
        const resource = entry.resource as FhirResource | undefined;
        if (!resource?.resourceType) continue;
        const stored = makeStored(resource, serverKey, serverLabel);
        if (stored) {
          collected.push(stored);
          perType[type].fetched += 1;
          totalFetched += 1;
        }
      }

      emit("resources", type);
      nextUrl = bundle.link?.find((link) => link.relation === "next")?.url;
    }

    perType[type].done = true;
    emit("resources", type);
    return collected;
  };

  const settled = await Promise.allSettled(
    types.map((type) => crawlType(type)),
  );

  const fetched: StoredResource[] = [];
  let aborted = false;
  let errored = false;
  let errorMessage: string | undefined;
  for (const outcome of settled) {
    if (outcome.status === "fulfilled") {
      fetched.push(...outcome.value);
    } else if (outcome.reason instanceof AbortedError || signal.aborted) {
      aborted = true;
    } else {
      errored = true;
      errorMessage =
        outcome.reason instanceof Error
          ? outcome.reason.message
          : String(outcome.reason);
    }
  }

  let deletions: DeletionEntry[] = [];
  let historySupported = true;
  if (mode === "incremental" && since && !aborted) {
    emit("history");
    try {
      const scan = await scanDeletions(serverUrl, since, {
        pageSize,
        signal,
        onRequest: (req) => {
          totalRequests += 1;
          totalBytes += req.bytes;
          totalPages += req.method === "GET" ? 1 : 0;
          log("GET", req.url, req.status, req.ms, req.bytes);
        },
      });
      deletions = scan.deletions;
    } catch (error) {
      if (error instanceof HistoryUnsupportedError) {
        historySupported = false;
      } else if (error instanceof AbortedError || signal.aborted) {
        aborted = true;
      } else {
        errored = true;
        errorMessage = error instanceof Error ? error.message : String(error);
      }
    }
  }

  const durationMs = Math.round(performance.now() - start);
  const status = aborted ? "aborted" : errored ? "error" : "completed";
  emit(aborted ? "aborted" : errored ? "error" : "done");

  return {
    serverKey,
    serverLabel,
    mode,
    startedAt,
    serverTime,
    durationMs,
    metrics: {
      records: fetched.length,
      bytes: totalBytes,
      requests: totalRequests,
      pages: totalPages,
    },
    fetched,
    deletions,
    historySupported,
    status,
    error: errorMessage,
  };
}

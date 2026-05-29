import type { FhirResource } from "fhir/r4";
import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { useFhirServer } from "@/hooks/use-fhir-server";
import { runCrawl } from "@/lib/crawler/crawl-engine";
import {
  type AggregateCounts,
  appendCrawlRun,
  applyChanges,
  clearServer,
  clearServerResources,
  countByServers,
  getCrawlRuns,
  getMeta,
  getResourcesByServers,
  normalizeServerKey,
  parseResourceKey,
  putResources,
  setMeta,
} from "@/lib/crawler/db";
import {
  applyDeletions,
  computeDiff,
  type ExistingIndex,
} from "@/lib/crawler/diff";
import { findDuplicates } from "@/lib/crawler/duplicates";
import { computeEfficiency } from "@/lib/crawler/efficiency";
import { fhirDelete, fhirPut } from "@/lib/crawler/fhir-write";
import type { TimedResult } from "@/lib/crawler/http";
import { summarizeLatestBatch } from "@/lib/crawler/summary";
import type {
  CrawlBatchSummary,
  CrawlDiff,
  CrawlMeta,
  CrawlMode,
  CrawlProgress,
  CrawlRun,
  DuplicateGroup,
  EfficiencyComparison,
  PerTypeDiff,
  RawCrawlResult,
  RequestLogEntry,
  ScopeServer,
  StoredResource,
} from "@/lib/crawler/types";

const REQUEST_LOG_CAP = 300;
const EMPTY_AGGREGATE: AggregateCounts = {
  total: 0,
  perType: {},
  perServer: {},
};

function toScopeServer(url: string, label?: string): ScopeServer {
  const serverKey = normalizeServerKey(url);
  return { serverKey, serverLabel: label ?? serverKey, url: serverKey };
}

const SCOPE_STORAGE_KEY = "crawler-scope";

function isScopeServer(value: unknown): value is ScopeServer {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as ScopeServer).serverKey === "string" &&
    typeof (value as ScopeServer).serverLabel === "string" &&
    typeof (value as ScopeServer).url === "string"
  );
}

/** Load the saved crawl scope, falling back to the active server. */
function loadScope(fallback: ScopeServer): ScopeServer[] {
  if (typeof window === "undefined") return [fallback];
  try {
    const raw = localStorage.getItem(SCOPE_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (
        Array.isArray(parsed) &&
        parsed.length > 0 &&
        parsed.every(isScopeServer)
      ) {
        return parsed;
      }
    }
  } catch {
    // ignore malformed storage
  }
  return [fallback];
}

function saveScope(scope: ScopeServer[]): void {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(SCOPE_STORAGE_KEY, JSON.stringify(scope));
  } catch {
    // ignore quota/serialization errors
  }
}

function buildIndex(resources: StoredResource[]): ExistingIndex {
  const index: ExistingIndex = new Map();
  for (const resource of resources) {
    index.set(resource.key, {
      versionId: resource.versionId,
      lastUpdated: resource.lastUpdated,
    });
  }
  return index;
}

function emptyDiff(): CrawlDiff {
  return { added: 0, updated: 0, deleted: 0, deletedKeys: [], perType: {} };
}

function bumpPerType(
  perType: Record<string, PerTypeDiff>,
  resourceType: string,
  field: keyof PerTypeDiff,
): void {
  const entry = perType[resourceType] ?? { added: 0, updated: 0, deleted: 0 };
  entry[field] += 1;
  perType[resourceType] = entry;
}

function buildDiff(
  added: StoredResource[],
  updated: StoredResource[],
  deletedKeys: string[],
): CrawlDiff {
  const perType: Record<string, PerTypeDiff> = {};
  for (const resource of added)
    bumpPerType(perType, resource.resourceType, "added");
  for (const resource of updated)
    bumpPerType(perType, resource.resourceType, "updated");
  for (const key of deletedKeys) {
    const parsed = parseResourceKey(key);
    if (parsed) bumpPerType(perType, parsed.resourceType, "deleted");
  }
  return {
    added: added.length,
    updated: updated.length,
    deleted: deletedKeys.length,
    deletedKeys,
    perType,
  };
}

export interface UseCrawlerResult {
  scope: ScopeServer[];
  setScope: (scope: ScopeServer[]) => void;
  aggregate: AggregateCounts;
  metas: Record<string, CrawlMeta>;
  runs: CrawlRun[];
  latestCrawl?: CrawlBatchSummary;
  duplicateGroups: DuplicateGroup[];
  efficiency: EfficiencyComparison;
  requestLog: RequestLogEntry[];
  progress: CrawlProgress | null;
  isCrawling: boolean;
  canIncremental: boolean;
  startFullCrawl: () => Promise<void>;
  startIncrementalCrawl: () => Promise<void>;
  cancel: () => void;
  clearStore: () => Promise<void>;
  mutateResource: (
    stored: StoredResource,
    next: FhirResource,
  ) => Promise<TimedResult<FhirResource>>;
  deleteResource: (stored: StoredResource) => Promise<void>;
  loadResources: (resourceType?: string) => Promise<StoredResource[]>;
}

export function useCrawler(): UseCrawlerResult {
  const { serverUrl, server } = useFhirServer();

  const [scope, setScopeState] = useState<ScopeServer[]>(() =>
    loadScope(toScopeServer(serverUrl, server?.name)),
  );
  const [aggregate, setAggregate] = useState<AggregateCounts>(EMPTY_AGGREGATE);
  const [metas, setMetas] = useState<Record<string, CrawlMeta>>({});
  const [runs, setRuns] = useState<CrawlRun[]>([]);
  const [latestCrawl, setLatestCrawl] = useState<CrawlBatchSummary | undefined>(
    undefined,
  );
  const [duplicateGroups, setDuplicateGroups] = useState<DuplicateGroup[]>([]);
  const [efficiency, setEfficiency] = useState<EfficiencyComparison>({});
  const [requestLog, setRequestLog] = useState<RequestLogEntry[]>([]);
  const [progress, setProgress] = useState<CrawlProgress | null>(null);
  const [isCrawling, setIsCrawling] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const reloadFromDb = useCallback(async (currentScope: ScopeServer[]) => {
    const serverKeys = currentScope.map((s) => s.serverKey);
    const [counts, allRuns] = await Promise.all([
      countByServers(serverKeys),
      getCrawlRuns(serverKeys),
    ]);
    const metaList = await Promise.all(serverKeys.map((key) => getMeta(key)));
    const metaMap: Record<string, CrawlMeta> = {};
    serverKeys.forEach((key, i) => {
      const meta = metaList[i];
      if (meta) metaMap[key] = meta;
    });

    let dups: DuplicateGroup[] = [];
    if (currentScope.length >= 2) {
      const all = await getResourcesByServers(serverKeys);
      dups = findDuplicates(all);
    }

    setAggregate(counts);
    setRuns(allRuns);
    setLatestCrawl(summarizeLatestBatch(allRuns));
    setMetas(metaMap);
    setDuplicateGroups(dups);
    setEfficiency(computeEfficiency(allRuns));
  }, []);

  useEffect(() => {
    void reloadFromDb(scope);
  }, [scope, reloadFromDb]);

  const setScope = useCallback((next: ScopeServer[]) => {
    saveScope(next);
    setScopeState(next);
  }, []);

  const pushLog = useCallback((entry: RequestLogEntry) => {
    setRequestLog((prev) => {
      const next = [entry, ...prev];
      return next.length > REQUEST_LOG_CAP
        ? next.slice(0, REQUEST_LOG_CAP)
        : next;
    });
  }, []);

  const persistRun = useCallback(
    async (raw: RawCrawlResult, mode: CrawlMode, batchId: string) => {
      let diff = emptyDiff();

      if (raw.status === "completed") {
        const existing = await getResourcesByServers([raw.serverKey]);
        const existingIndex = buildIndex(existing);

        if (mode === "full") {
          const { added, updated } = computeDiff(raw.fetched, existingIndex);
          const fetchedKeys = new Set(raw.fetched.map((r) => r.key));
          const deletedKeys = [...existingIndex.keys()].filter(
            (key) => !fetchedKeys.has(key),
          );
          await clearServerResources(raw.serverKey);
          await putResources(raw.fetched);
          diff = buildDiff(added, updated, deletedKeys);
        } else {
          const { added, updated } = computeDiff(raw.fetched, existingIndex);
          const deletedKeys = applyDeletions(
            raw.deletions,
            raw.serverKey,
            existingIndex,
          );
          await applyChanges([...added, ...updated], deletedKeys);
          diff = buildDiff(added, updated, deletedKeys);
        }

        await setMeta({
          serverKey: raw.serverKey,
          serverLabel: raw.serverLabel,
          lastCrawlServerTime: raw.serverTime.iso,
          lastCrawlMode: mode,
          lastCrawlAt: raw.startedAt,
          historySupported: raw.historySupported,
        });
      }

      const run: CrawlRun = {
        id: crypto.randomUUID(),
        batchId,
        serverKey: raw.serverKey,
        serverLabel: raw.serverLabel,
        mode,
        startedAt: raw.startedAt,
        serverTimeAtStart: raw.serverTime.iso,
        durationMs: raw.durationMs,
        metrics: raw.metrics,
        diff,
        status: raw.status,
        error: raw.error,
      };
      await appendCrawlRun(run);
    },
    [],
  );

  const runScope = useCallback(
    async (mode: CrawlMode) => {
      if (isCrawling) return;
      const controller = new AbortController();
      abortRef.current = controller;
      const batchId = crypto.randomUUID();
      setIsCrawling(true);
      setProgress(null);

      try {
        for (const srv of scope) {
          if (controller.signal.aborted) break;
          const meta = await getMeta(srv.serverKey);
          const effectiveMode: CrawlMode =
            mode === "incremental" && !meta?.lastCrawlServerTime
              ? "full"
              : mode;

          const raw = await runCrawl({
            serverUrl: srv.url,
            serverLabel: srv.serverLabel,
            mode: effectiveMode,
            since: meta?.lastCrawlServerTime,
            signal: controller.signal,
            onProgress: setProgress,
            onRequest: pushLog,
          });
          await persistRun(raw, effectiveMode, batchId);

          if (raw.status === "error") {
            toast.error(`Crawl failed on ${srv.serverLabel}`, {
              description: raw.error,
            });
          } else if (effectiveMode === "incremental" && !raw.historySupported) {
            toast.warning(
              `Deletion detection unavailable on ${srv.serverLabel}`,
              {
                description:
                  "The server does not support system-level _history, so deletions cannot be detected.",
              },
            );
          }
        }
      } finally {
        const wasAborted = controller.signal.aborted;
        abortRef.current = null;
        setIsCrawling(false);
        setProgress(null);
        await reloadFromDb(scope);
        if (wasAborted) {
          toast.info("Crawl cancelled");
        }
      }
    },
    [isCrawling, scope, pushLog, persistRun, reloadFromDb],
  );

  const startFullCrawl = useCallback(() => runScope("full"), [runScope]);
  const startIncrementalCrawl = useCallback(
    () => runScope("incremental"),
    [runScope],
  );

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const clearStore = useCallback(async () => {
    for (const srv of scope) {
      await clearServer(srv.serverKey);
    }
    setRequestLog([]);
    await reloadFromDb(scope);
  }, [scope, reloadFromDb]);

  const mutateResource = useCallback(
    async (stored: StoredResource, next: FhirResource) => {
      const url = `${stored.serverKey}/${stored.resourceType}/${stored.id}`;
      const result = await fhirPut(url, next);
      pushLog({
        id: crypto.randomUUID(),
        serverKey: stored.serverKey,
        serverLabel: stored.serverLabel,
        method: "PUT",
        url,
        status: result.status,
        ms: result.ms,
        bytes: result.bytes,
        at: new Date().toISOString(),
        requestBody: next,
        responseBody: result.data,
      });
      return result;
    },
    [pushLog],
  );

  const deleteResource = useCallback(
    async (stored: StoredResource) => {
      const url = `${stored.serverKey}/${stored.resourceType}/${stored.id}`;
      const result = await fhirDelete(url);
      pushLog({
        id: crypto.randomUUID(),
        serverKey: stored.serverKey,
        serverLabel: stored.serverLabel,
        method: "DELETE",
        url,
        status: result.status,
        ms: result.ms,
        bytes: result.bytes,
        at: new Date().toISOString(),
        // DELETE has no request body; only the server's response is meaningful.
        responseBody: result.data,
      });
    },
    [pushLog],
  );

  const loadResources = useCallback(
    (resourceType?: string) =>
      getResourcesByServers(
        scope.map((s) => s.serverKey),
        resourceType,
      ),
    [scope],
  );

  const canIncremental = scope.some(
    (srv) => metas[srv.serverKey]?.lastCrawlServerTime,
  );

  return {
    scope,
    setScope,
    aggregate,
    metas,
    runs,
    latestCrawl,
    duplicateGroups,
    efficiency,
    requestLog,
    progress,
    isCrawling,
    canIncremental,
    startFullCrawl,
    startIncrementalCrawl,
    cancel,
    clearStore,
    mutateResource,
    deleteResource,
    loadResources,
  };
}

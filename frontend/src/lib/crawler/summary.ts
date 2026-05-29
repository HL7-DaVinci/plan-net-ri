import type { CrawlBatchSummary, CrawlMode, CrawlRun } from "./types";

/**
 * Combine the per-server runs of the most recent crawl operation into one
 * summary. Runs from a single operation share a batchId; legacy runs without
 * one are treated as standalone (just the latest run).
 */
export function summarizeLatestBatch(
  runs: CrawlRun[],
): CrawlBatchSummary | undefined {
  if (runs.length === 0) return undefined;

  const sorted = [...runs].sort((a, b) =>
    b.startedAt.localeCompare(a.startedAt),
  );
  const latest = sorted[0];
  const batch = latest.batchId
    ? sorted.filter((run) => run.batchId === latest.batchId)
    : [latest];

  const totals = {
    added: 0,
    updated: 0,
    deleted: 0,
    records: 0,
    durationMs: 0,
    pages: 0,
    requests: 0,
  };
  const modes = new Set<CrawlMode>();
  let status: CrawlBatchSummary["status"] = "completed";

  for (const run of batch) {
    totals.added += run.diff.added;
    totals.updated += run.diff.updated;
    totals.deleted += run.diff.deleted;
    totals.records += run.metrics.records;
    totals.durationMs += run.durationMs;
    totals.pages += run.metrics.pages;
    totals.requests += run.metrics.requests;
    modes.add(run.mode);
    if (run.status === "error") {
      status = "error";
    } else if (run.status === "aborted" && status !== "error") {
      status = "aborted";
    }
  }

  const modeList = [...modes];
  return {
    batchId: latest.batchId,
    startedAt: latest.startedAt,
    serverTimeAtStart: latest.serverTimeAtStart,
    mode: modeList.length === 1 ? modeList[0] : "mixed",
    hasIncremental: batch.some((run) => run.mode === "incremental"),
    status,
    serverCount: batch.length,
    ...totals,
  };
}

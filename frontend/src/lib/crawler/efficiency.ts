import type { CrawlRun, EfficiencyComparison, EfficiencySide } from "./types";

function emptySide(): EfficiencySide {
  return { records: 0, bytes: 0, requests: 0 };
}

function addRun(side: EfficiencySide, run: CrawlRun): EfficiencySide {
  return {
    records: side.records + run.metrics.records,
    bytes: side.bytes + run.metrics.bytes,
    requests: side.requests + run.metrics.requests,
  };
}

/**
 * Derive the efficiency comparison by summing, per server, the most recent
 * completed full crawl and the most recent completed incremental crawl. This
 * frames the value of incremental sync (full pulled everything; incremental
 * pulled only the delta).
 */
export function computeEfficiency(runs: CrawlRun[]): EfficiencyComparison {
  const sorted = [...runs].sort((a, b) =>
    b.startedAt.localeCompare(a.startedAt),
  );

  const latestByServer = new Map<
    string,
    { full?: CrawlRun; incremental?: CrawlRun }
  >();
  for (const run of sorted) {
    if (run.status !== "completed") continue;
    const entry = latestByServer.get(run.serverKey) ?? {};
    if (run.mode === "full" && !entry.full) entry.full = run;
    if (run.mode === "incremental" && !entry.incremental)
      entry.incremental = run;
    latestByServer.set(run.serverKey, entry);
  }

  let full: EfficiencySide | undefined;
  let incremental: EfficiencySide | undefined;
  for (const entry of latestByServer.values()) {
    if (entry.full) full = addRun(full ?? emptySide(), entry.full);
    if (entry.incremental) {
      incremental = addRun(incremental ?? emptySide(), entry.incremental);
    }
  }

  const result: EfficiencyComparison = { full, incremental };
  if (full && incremental && full.bytes > 0) {
    result.savingsPct = Math.max(
      0,
      Math.round((1 - incremental.bytes / full.bytes) * 100),
    );
  }
  return result;
}

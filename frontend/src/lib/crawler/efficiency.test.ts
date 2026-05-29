import { describe, expect, it } from "vitest";
import { computeEfficiency } from "./efficiency";
import type { CrawlDiff, CrawlRun, RunMetrics } from "./types";

const emptyDiff: CrawlDiff = {
  added: 0,
  updated: 0,
  deleted: 0,
  deletedKeys: [],
  perType: {},
};

function run(
  serverKey: string,
  mode: CrawlRun["mode"],
  startedAt: string,
  metrics: RunMetrics,
): CrawlRun {
  return {
    id: `${serverKey}-${mode}-${startedAt}`,
    serverKey,
    serverLabel: serverKey,
    mode,
    startedAt,
    durationMs: 100,
    metrics,
    diff: emptyDiff,
    status: "completed",
  };
}

describe("computeEfficiency", () => {
  it("derives byte-based savings from the latest full vs incremental", () => {
    const result = computeEfficiency([
      run("s1", "full", "2026-01-01T00:00:00Z", {
        records: 1000,
        bytes: 100000,
        requests: 30,
        pages: 30,
      }),
      run("s1", "incremental", "2026-01-02T00:00:00Z", {
        records: 2,
        bytes: 1000,
        requests: 9,
        pages: 9,
      }),
    ]);

    expect(result.full?.records).toBe(1000);
    expect(result.incremental?.records).toBe(2);
    expect(result.savingsPct).toBe(99);
  });

  it("sums the latest run per server across the scope", () => {
    const result = computeEfficiency([
      run("s1", "full", "2026-01-01T00:00:00Z", {
        records: 100,
        bytes: 1000,
        requests: 5,
        pages: 5,
      }),
      // older full for s1 should be ignored
      run("s1", "full", "2025-12-01T00:00:00Z", {
        records: 50,
        bytes: 500,
        requests: 3,
        pages: 3,
      }),
      run("s2", "full", "2026-01-01T00:00:00Z", {
        records: 200,
        bytes: 3000,
        requests: 8,
        pages: 8,
      }),
    ]);

    expect(result.full).toEqual({ records: 300, bytes: 4000, requests: 13 });
    expect(result.incremental).toBeUndefined();
    expect(result.savingsPct).toBeUndefined();
  });
});

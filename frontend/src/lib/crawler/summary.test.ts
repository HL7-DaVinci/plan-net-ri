import { describe, expect, it } from "vitest";
import { summarizeLatestBatch } from "./summary";
import type { CrawlDiff, CrawlRun } from "./types";

function diff(added: number, updated: number, deleted: number): CrawlDiff {
  return { added, updated, deleted, deletedKeys: [], perType: {} };
}

function run(overrides: Partial<CrawlRun>): CrawlRun {
  return {
    id: Math.random().toString(36).slice(2),
    serverKey: "s",
    serverLabel: "s",
    mode: "incremental",
    startedAt: "2026-01-01T00:00:00Z",
    durationMs: 100,
    metrics: { records: 0, bytes: 0, requests: 0, pages: 0 },
    diff: diff(0, 0, 0),
    status: "completed",
    ...overrides,
  };
}

describe("summarizeLatestBatch", () => {
  it("returns undefined when there are no runs", () => {
    expect(summarizeLatestBatch([])).toBeUndefined();
  });

  it("sums the per-server runs of the most recent batch", () => {
    const summary = summarizeLatestBatch([
      run({
        batchId: "b2",
        serverKey: "a",
        startedAt: "2026-02-01T00:00:05Z",
        diff: diff(2, 1, 1),
        metrics: { records: 3, bytes: 0, requests: 9, pages: 9 },
        durationMs: 200,
      }),
      run({
        batchId: "b2",
        serverKey: "b",
        startedAt: "2026-02-01T00:00:00Z",
        diff: diff(5, 0, 2),
        metrics: { records: 7, bytes: 0, requests: 9, pages: 9 },
        durationMs: 300,
      }),
      // older batch must be ignored
      run({
        batchId: "b1",
        startedAt: "2026-01-01T00:00:00Z",
        diff: diff(99, 99, 99),
      }),
    ]);

    expect(summary).toMatchObject({
      added: 7,
      updated: 1,
      deleted: 3,
      records: 10,
      requests: 18,
      pages: 18,
      durationMs: 500,
      serverCount: 2,
      mode: "incremental",
      hasIncremental: true,
      status: "completed",
    });
  });

  it("marks the batch as error or aborted if any run was", () => {
    const summary = summarizeLatestBatch([
      run({
        batchId: "b",
        serverKey: "a",
        diff: diff(1, 0, 0),
        status: "completed",
      }),
      run({ batchId: "b", serverKey: "b", status: "error" }),
    ]);
    expect(summary?.status).toBe("error");
  });

  it("reports mixed mode and a single latest run for legacy runs without a batchId", () => {
    const summary = summarizeLatestBatch([
      run({
        serverKey: "a",
        startedAt: "2026-03-01T00:00:00Z",
        mode: "full",
        diff: diff(10, 0, 0),
      }),
      run({
        serverKey: "b",
        startedAt: "2026-02-01T00:00:00Z",
        mode: "incremental",
        diff: diff(5, 0, 0),
      }),
    ]);
    // No batchId: only the single most recent run is summarized.
    expect(summary).toMatchObject({ added: 10, serverCount: 1, mode: "full" });
  });
});

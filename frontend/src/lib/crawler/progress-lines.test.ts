import { describe, expect, it } from "vitest";
import type { CrawlStep } from "@/lib/api/types";
import {
  applyProgressEvent,
  isSettled,
  type ProgressLines,
} from "./progress-lines";

function step(overrides: Partial<CrawlStep>): CrawlStep {
  return {
    seq: 1,
    phase: "FETCH",
    message: "Fetching",
    method: null,
    url: null,
    status: null,
    ms: null,
    bytes: null,
    count: null,
    errorBody: null,
    serverKey: "server",
    track: null,
    at: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("applyProgressEvent", () => {
  it("upserts a progress line keyed by track", () => {
    const lines = applyProgressEvent(new Map(), {
      kind: "progress",
      step: step({ track: "Patient", message: "Fetching page 1" }),
      now: 1000,
    });
    expect(lines.get("Patient")).toEqual({
      track: "Patient",
      phase: "FETCH",
      message: "Fetching page 1",
      method: null,
      url: null,
      status: null,
      ms: null,
      count: null,
      at: 1000,
    });
  });

  it("marks a marker carrying an HTTP result as settled", () => {
    const settled = applyProgressEvent(new Map(), {
      kind: "progress",
      step: step({
        track: "Location [1/25]",
        status: 200,
        ms: 1234,
        count: 500,
      }),
      now: 1000,
    }).get("Location [1/25]");
    const inFlight = applyProgressEvent(new Map(), {
      kind: "progress",
      step: step({ track: "Location [2/25]" }),
      now: 1000,
    }).get("Location [2/25]");

    expect(settled && isSettled(settled)).toBe(true);
    expect(inFlight && isSettled(inFlight)).toBe(false);
  });

  it("keys an untracked progress line by the empty string", () => {
    const lines = applyProgressEvent(new Map(), {
      kind: "progress",
      step: step({ track: null }),
      now: 1000,
    });
    expect(lines.has("")).toBe(true);
  });

  it("keeps separate lines for separate tracks", () => {
    let lines: ProgressLines = new Map();
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Patient" }),
      now: 1000,
    });
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Practitioner" }),
      now: 1001,
    });
    expect(Array.from(lines.keys()).sort()).toEqual([
      "Patient",
      "Practitioner",
    ]);
  });

  it("overwrites the same track's line with the latest progress", () => {
    let lines: ProgressLines = new Map();
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Patient", message: "Page 1" }),
      now: 1000,
    });
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Patient", message: "Page 2" }),
      now: 2000,
    });
    expect(lines.size).toBe(1);
    expect(lines.get("Patient")?.message).toBe("Page 2");
    expect(lines.get("Patient")?.at).toBe(2000);
  });

  it("a step with a track clears only that track's line", () => {
    let lines: ProgressLines = new Map();
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Patient" }),
      now: 1000,
    });
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Practitioner" }),
      now: 1000,
    });
    lines = applyProgressEvent(lines, {
      kind: "step",
      step: step({ track: "Patient" }),
    });
    expect(Array.from(lines.keys())).toEqual(["Practitioner"]);
  });

  it("a step with a null track clears every line", () => {
    let lines: ProgressLines = new Map();
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Patient" }),
      now: 1000,
    });
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Practitioner" }),
      now: 1000,
    });
    lines = applyProgressEvent(lines, {
      kind: "step",
      step: step({ track: null }),
    });
    expect(lines.size).toBe(0);
  });

  it("complete clears every line", () => {
    let lines: ProgressLines = new Map();
    lines = applyProgressEvent(lines, {
      kind: "progress",
      step: step({ track: "Patient" }),
      now: 1000,
    });
    lines = applyProgressEvent(lines, { kind: "complete" });
    expect(lines.size).toBe(0);
  });

  it("a step for a track with no active line is a no-op", () => {
    const empty: ProgressLines = new Map();
    const lines = applyProgressEvent(empty, {
      kind: "step",
      step: step({ track: "Patient" }),
    });
    expect(lines).toBe(empty);
  });
});

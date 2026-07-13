import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import type { CrawlStep } from "@/lib/api/types";
import { PlayByPlay } from "./play-by-play";

/**
 * EventSource auto-reconnects after a dropped connection, and the server replays the full
 * step timeline plus one line per still-active track on every subscribe. These tests pin the
 * reset-on-open behavior: without it a reconnect duplicates steps and strands progress lines
 * whose settled resolution was broadcast while the connection was down.
 */
class FakeEventSource {
  static latest: FakeEventSource | null = null;
  private listeners = new Map<string, Set<(event: MessageEvent) => void>>();

  constructor(public url: string) {
    FakeEventSource.latest = this;
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(listener);
  }

  close() {}

  emit(type: string, data?: unknown) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: JSON.stringify(data) } as MessageEvent);
    }
  }
}

beforeEach(() => {
  vi.stubGlobal("EventSource", FakeEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  FakeEventSource.latest = null;
});

function step(overrides: Partial<CrawlStep>): CrawlStep {
  return {
    seq: 1,
    phase: "SEARCH",
    message: "",
    method: null,
    url: null,
    status: null,
    ms: null,
    bytes: null,
    count: null,
    errorBody: null,
    serverKey: null,
    track: null,
    at: new Date().toISOString(),
    ...overrides,
  };
}

test("a reconnect drops lines whose settle event fell in the gap and does not duplicate replayed steps", () => {
  render(<PlayByPlay batchId="b1" />);
  const source = FakeEventSource.latest;
  if (!source) throw new Error("EventSource was not opened");

  act(() => {
    source.emit("open");
    source.emit(
      "step",
      step({ seq: 1, phase: "STARTING", message: "Crawl started" }),
    );
    source.emit(
      "progress",
      step({ seq: 0, track: "Location [1/2]", message: "Searching window 1" }),
    );
    source.emit(
      "progress",
      step({ seq: 0, track: "Location [2/2]", message: "Searching window 2" }),
    );
  });

  expect(screen.getByText("Searching window 1")).toBeInTheDocument();
  expect(screen.getByText("Searching window 2")).toBeInTheDocument();

  // Window 1 settled while the connection was down, so the reconnect's replay
  // repeats the persisted step but carries only window 2's still-active line.
  act(() => {
    source.emit("open");
    source.emit(
      "step",
      step({ seq: 1, phase: "STARTING", message: "Crawl started" }),
    );
    source.emit(
      "progress",
      step({ seq: 0, track: "Location [2/2]", message: "Searching window 2" }),
    );
  });

  expect(screen.queryByText("Searching window 1")).not.toBeInTheDocument();
  expect(screen.getByText("Searching window 2")).toBeInTheDocument();
  expect(screen.getAllByText("Crawl started")).toHaveLength(1);
});

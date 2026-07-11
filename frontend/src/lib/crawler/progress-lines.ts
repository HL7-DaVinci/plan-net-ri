import type { CrawlStep } from "@/lib/api/types";

export interface ProgressLine {
  track: string;
  phase: string;
  message: string;
  method: string | null;
  url: string | null;
  status: number | null;
  ms: number | null;
  count: number | null;
  at: number;
}

/** A resolution marker carries its HTTP result; the operation is finished, not in flight. */
export function isSettled(line: ProgressLine): boolean {
  return line.status != null;
}

export type ProgressLines = Map<string, ProgressLine>;

export type ProgressLineEvent =
  | { kind: "progress"; step: CrawlStep; now: number }
  | { kind: "step"; step: CrawlStep }
  | { kind: "complete" };

/**
 * Reduces play-by-play SSE events into the set of currently active progress lines,
 * one per track. Mirrors the backend clearing rule: a step with a null track clears
 * every line (the whole batch's progress went stale), while a step with a track
 * clears only that track's line.
 */
export function applyProgressEvent(
  lines: ProgressLines,
  event: ProgressLineEvent,
): ProgressLines {
  switch (event.kind) {
    case "progress": {
      const track = event.step.track ?? "";
      const next = new Map(lines);
      next.set(track, {
        track,
        phase: event.step.phase,
        message: event.step.message,
        method: event.step.method,
        url: event.step.url,
        status: event.step.status,
        ms: event.step.ms,
        count: event.step.count,
        at: event.now,
      });
      return next;
    }
    case "step": {
      if (event.step.track == null) {
        return lines.size === 0 ? lines : new Map();
      }
      if (!lines.has(event.step.track)) return lines;
      const next = new Map(lines);
      next.delete(event.step.track);
      return next;
    }
    case "complete":
      return lines.size === 0 ? lines : new Map();
  }
}

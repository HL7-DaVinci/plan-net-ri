import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  type PublishManifest,
  pollDelayMs,
  publishApiBase,
  type SnapshotListing,
} from "@/lib/publish/manifest";

export type ManifestStatus =
  | "loading"
  | "ready"
  | "no-snapshot"
  | "unsupported"
  | "unreachable";

export interface WatchLogEntry {
  at: string;
  status: number | "error";
  etag: string | null;
  cacheControl: string | null;
  durationMs: number;
  detail?: string;
}

const LOG_LIMIT = 50;

/**
 * Polls {serverUrl}/$bulk-publish with If-None-Match at the manifest's advertised cadence,
 * recording every attempt. A 304 leaves the manifest untouched; a 200 replaces it.
 */
export function useBulkPublishWatcher(serverUrl: string) {
  const [manifest, setManifest] = useState<PublishManifest | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [status, setStatus] = useState<ManifestStatus>("loading");
  const [log, setLog] = useState<WatchLogEntry[]>([]);
  const [paused, setPaused] = useState(false);
  const etagRef = useRef<string | null>(null);
  const generationRef = useRef(0);

  const checkNow = useCallback(async () => {
    const generation = generationRef.current;
    const started = Date.now();
    let entry: WatchLogEntry;
    let nextStatus: ManifestStatus;
    let body: PublishManifest | null = null;
    let newEtag: string | null = null;
    try {
      const headers: Record<string, string> = { Accept: "application/json" };
      if (etagRef.current) {
        headers["If-None-Match"] = etagRef.current;
      }
      const response = await fetch(`${serverUrl}/$bulk-publish`, { headers });
      entry = {
        at: new Date().toISOString(),
        status: response.status,
        etag: response.headers.get("ETag"),
        cacheControl: response.headers.get("Cache-Control"),
        durationMs: Date.now() - started,
      };
      if (response.status === 200) {
        body = (await response.json()) as PublishManifest;
        newEtag = response.headers.get("ETag");
        nextStatus = "ready";
      } else if (response.status === 304) {
        nextStatus = "ready";
      } else if (response.status === 503) {
        nextStatus = "no-snapshot";
        entry.detail = await response.text().catch(() => "");
      } else {
        nextStatus = "unsupported";
        entry.detail = await response.text().catch(() => "");
      }
    } catch (e) {
      entry = {
        at: new Date().toISOString(),
        status: "error",
        etag: null,
        cacheControl: null,
        durationMs: Date.now() - started,
        detail: e instanceof Error ? e.message : String(e),
      };
      nextStatus = "unreachable";
    }
    // A response from a previously selected server must not touch current state.
    if (generation !== generationRef.current) {
      return;
    }
    if (body) {
      setManifest(body);
      etagRef.current = newEtag;
      setEtag(newEtag);
    }
    setStatus(nextStatus);
    setLog((prev) => [entry, ...prev].slice(0, LOG_LIMIT));
  }, [serverUrl]);

  // Reset all state and fetch immediately when the selected server changes.
  useEffect(() => {
    generationRef.current += 1;
    setManifest(null);
    setEtag(null);
    etagRef.current = null;
    setLog([]);
    setStatus("loading");
    setPaused(false);
    void checkNow();
  }, [checkNow]);

  // Schedule the next poll after each completed attempt (log changes on every attempt).
  useEffect(() => {
    if (paused || log.length === 0) {
      return;
    }
    const timer = setTimeout(
      () => void checkNow(),
      pollDelayMs(manifest?.updateCadence),
    );
    return () => clearTimeout(timer);
  }, [paused, log, manifest, checkNow]);

  return { manifest, etag, status, log, paused, setPaused, checkNow };
}

/** Snapshot internals; only answers on plan-net-ri deployments, so isError gates the timeline. */
export function usePublishSnapshots(serverUrl: string) {
  return useQuery({
    queryKey: ["publish", "snapshots", serverUrl],
    queryFn: async () => {
      const response = await fetch(
        `${publishApiBase(serverUrl)}/api/publish/snapshots`,
      );
      if (!response.ok) {
        throw new Error(`Snapshot listing failed: ${response.status}`);
      }
      return (await response.json()) as SnapshotListing[];
    },
    retry: 0,
    staleTime: 5_000,
  });
}

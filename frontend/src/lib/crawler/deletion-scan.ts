import type { Bundle } from "fhir/r4";
import { type FhirError, timedFhirFetch } from "./http";
import type { DeletionEntry } from "./types";

/** Thrown when the server does not support system-level _history. */
export class HistoryUnsupportedError extends Error {
  constructor(message = "Server does not support system-level _history") {
    super(message);
    this.name = "HistoryUnsupportedError";
  }
}

export type RequestObserver = (req: {
  method: string;
  url: string;
  status: number;
  ms: number;
  bytes: number;
}) => void;

export interface DeletionScanResult {
  deletions: DeletionEntry[];
  requests: number;
  pages: number;
  bytes: number;
}

/** Extract {resourceType, id} from a relative or absolute reference. */
function parseReference(ref: string | undefined): DeletionEntry | null {
  if (!ref) return null;
  // Strip any query string and trailing _history/<version> segments.
  const clean = ref.split("?")[0].replace(/\/_history\/.*$/, "");
  const parts = clean.split("/").filter(Boolean);
  if (parts.length < 2) return null;
  const id = parts[parts.length - 1];
  const resourceType = parts[parts.length - 2];
  if (!resourceType || !id) return null;
  return { resourceType, id };
}

/**
 * Pull deleted resources out of a history Bundle. Deleted entries have
 * request.method === "DELETE" and no resource body.
 */
export function extractDeletions(bundle: Bundle): DeletionEntry[] {
  const deletions: DeletionEntry[] = [];
  for (const entry of bundle.entry ?? []) {
    if (entry.request?.method === "DELETE" && !entry.resource) {
      const parsed =
        parseReference(entry.request.url) ?? parseReference(entry.fullUrl);
      if (parsed) deletions.push(parsed);
    }
  }
  return deletions;
}

/**
 * Walk system-level history since the given timestamp and collect deleted
 * resources. Deleted entries have request.method === "DELETE" and no resource
 * body. Creates/updates in history are ignored here (covered by _lastUpdated
 * search). Throws HistoryUnsupportedError if the first request is rejected.
 */
export async function scanDeletions(
  serverUrl: string,
  since: string,
  opts: {
    pageSize?: number;
    signal?: AbortSignal;
    onRequest?: RequestObserver;
  } = {},
): Promise<DeletionScanResult> {
  const pageSize = opts.pageSize ?? 200;
  const deletions: DeletionEntry[] = [];
  const seenUrls = new Set<string>();

  let nextUrl: string | undefined =
    `${serverUrl}/_history?_since=${encodeURIComponent(
      since,
    )}&_count=${pageSize}`;
  let requests = 0;
  let pages = 0;
  let bytes = 0;
  let firstRequest = true;

  while (nextUrl) {
    if (opts.signal?.aborted) break;
    if (seenUrls.has(nextUrl)) break;
    seenUrls.add(nextUrl);

    let bundle: Bundle;
    try {
      const result = await timedFhirFetch<Bundle>(nextUrl, {
        signal: opts.signal,
      });
      bundle = result.data;
      requests += 1;
      pages += 1;
      bytes += result.bytes;
      opts.onRequest?.({
        method: "GET",
        url: nextUrl,
        status: result.status,
        ms: result.ms,
        bytes: result.bytes,
      });
    } catch (error) {
      const status = (error as FhirError).status;
      if (firstRequest && status && status >= 400 && status < 500) {
        throw new HistoryUnsupportedError();
      }
      throw error;
    }
    firstRequest = false;

    deletions.push(...extractDeletions(bundle));

    nextUrl = bundle.link?.find((link) => link.relation === "next")?.url;
  }

  return { deletions, requests, pages, bytes };
}

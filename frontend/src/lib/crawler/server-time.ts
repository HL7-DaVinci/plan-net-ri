import type { ServerTimeResult } from "./types";

/**
 * Resolve an authoritative timestamp from the server to anchor incremental
 * crawls, avoiding client/server clock skew. Strategy:
 *   1. HTTP `Date` response header (the server's own clock)
 *   2. `meta.lastUpdated` of the returned resource
 *   3. client time (last resort, may be skewed)
 *
 * The same value is later used for both `_lastUpdated=gt` and `_history?_since`.
 */
export async function getServerTime(
  serverUrl: string,
  signal?: AbortSignal,
): Promise<ServerTimeResult> {
  try {
    const response = await fetch(`${serverUrl}/metadata?_summary=true`, {
      headers: { Accept: "application/fhir+json" },
      signal,
    });

    const dateHeader = response.headers.get("date");
    if (dateHeader) {
      const parsed = new Date(dateHeader);
      if (!Number.isNaN(parsed.getTime())) {
        return { iso: parsed.toISOString(), source: "date-header" };
      }
    }

    try {
      const body = (await response.json()) as
        | { meta?: { lastUpdated?: string } }
        | undefined;
      const lastUpdated = body?.meta?.lastUpdated;
      if (lastUpdated) {
        return { iso: lastUpdated, source: "bundle-meta" };
      }
    } catch {
      // fall through to client fallback
    }
  } catch {
    // network/abort: fall through to client fallback
  }

  return { iso: new Date().toISOString(), source: "client-fallback" };
}

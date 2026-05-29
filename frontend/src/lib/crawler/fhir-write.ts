import type { FhirResource } from "fhir/r4";
import { type TimedResult, timedFhirFetch } from "./http";

/**
 * Update a resource via PUT. The server assigns a new versionId and bumps
 * meta.lastUpdated, which is what the incremental crawl detects.
 */
export function fhirPut(
  url: string,
  resource: FhirResource,
  opts?: { ifMatch?: string; signal?: AbortSignal },
): Promise<TimedResult<FhirResource>> {
  return timedFhirFetch<FhirResource>(url, {
    method: "PUT",
    headers: {
      "Content-Type": "application/fhir+json",
      ...(opts?.ifMatch ? { "If-Match": opts.ifMatch } : {}),
    },
    body: JSON.stringify(resource),
    signal: opts?.signal,
  });
}

/** Delete a resource. Shows up in system history as a DELETE entry. */
export function fhirDelete(
  url: string,
  opts?: { signal?: AbortSignal },
): Promise<TimedResult<unknown>> {
  return timedFhirFetch<unknown>(url, {
    method: "DELETE",
    signal: opts?.signal,
  });
}

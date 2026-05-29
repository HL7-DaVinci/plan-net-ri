import type { OperationOutcome } from "fhir/r4";
import { isOperationOutcome } from "@/lib/fhir-types";

export interface FhirError extends Error {
  status?: number;
  operationOutcome?: OperationOutcome;
  bytes?: number;
  ms?: number;
}

export interface TimedResult<T> {
  data: T;
  status: number;
  /** UTF-8 byte size of the response body */
  bytes: number;
  ms: number;
}

/**
 * Single instrumented FHIR fetch used for every crawl/mutation request so that
 * timing and payload size can be measured consistently. Reads the body as text
 * to measure bytes, then parses JSON. Throws a FhirError on non-2xx responses.
 */
export async function timedFhirFetch<T>(
  url: string,
  init?: RequestInit,
): Promise<TimedResult<T>> {
  const start = performance.now();
  const headers: Record<string, string> = {
    Accept: "application/fhir+json",
    ...((init?.headers as Record<string, string>) ?? {}),
  };

  const response = await fetch(url, { ...init, headers });
  const text = await response.text();
  const bytes = new TextEncoder().encode(text).length;
  const ms = Math.round(performance.now() - start);

  let body: unknown;
  try {
    body = text ? JSON.parse(text) : undefined;
  } catch {
    body = undefined;
  }

  if (!response.ok) {
    const error: FhirError = new Error(
      `FHIR request failed: ${response.status} ${response.statusText}`,
    );
    error.status = response.status;
    error.bytes = bytes;
    error.ms = ms;
    if (isOperationOutcome(body)) {
      error.operationOutcome = body;
      error.message =
        body.issue[0]?.diagnostics ||
        body.issue[0]?.details?.text ||
        error.message;
    }
    throw error;
  }

  return { data: body as T, status: response.status, bytes, ms };
}

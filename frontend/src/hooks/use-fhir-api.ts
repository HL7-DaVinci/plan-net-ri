import { useQuery } from "@tanstack/react-query";
import type {
  Bundle,
  CapabilityStatement,
  FhirResource,
  OperationOutcome,
  Parameters,
} from "fhir/r4";
import { useMemo } from "react";
import { isOperationOutcome } from "@/lib/fhir-types";

interface FhirError extends Error {
  status?: number;
  operationOutcome?: OperationOutcome;
}

export async function fhirFetch<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    headers: {
      Accept: "application/fhir+json",
    },
  });

  if (!response.ok) {
    const error: FhirError = new Error(
      `FHIR request failed: ${response.status} ${response.statusText}`,
    );
    error.status = response.status;
    try {
      const body = await response.json();
      if (isOperationOutcome(body)) {
        error.operationOutcome = body;
        error.message =
          body.issue[0]?.diagnostics ||
          body.issue[0]?.details?.text ||
          error.message;
      }
    } catch {
      // Ignore JSON parse errors
    }
    throw error;
  }

  return response.json();
}

export function useCapabilityStatement(serverUrl: string) {
  return useQuery({
    queryKey: ["fhir", "metadata", serverUrl],
    queryFn: () => fhirFetch<CapabilityStatement>(`${serverUrl}/metadata`),
    staleTime: 5 * 60 * 1000, // 5 minutes
    retry: 1,
    enabled: !!serverUrl,
  });
}

export function getResourceTypes(
  capability: CapabilityStatement | undefined,
): string[] {
  if (!capability?.rest) {
    return [];
  }

  const serverRest = capability.rest.find((r) => r.mode === "server");
  if (!serverRest?.resource) {
    return [];
  }

  return serverRest.resource
    .map((r) => r.type)
    .filter((type): type is string => !!type)
    .sort();
}

export function useServerStatus(serverUrl: string) {
  const query = useQuery({
    queryKey: ["fhir", "status", serverUrl],
    queryFn: async () => {
      const start = Date.now();
      const capability = await fhirFetch<CapabilityStatement>(
        `${serverUrl}/metadata`,
      );
      const latency = Date.now() - start;
      return {
        connected: true,
        latency,
        capability,
      };
    },
    staleTime: 30 * 1000, // 30 seconds
    retry: 0,
    enabled: !!serverUrl,
  });

  return {
    ...query,
    isConnected: query.isSuccess,
    isDisconnected: query.isError,
    latency: query.data?.latency,
  };
}

export function useResourceCounts(serverUrl: string, enabled: boolean) {
  return useQuery({
    queryKey: ["fhir", "resource-counts", serverUrl],
    queryFn: async () => {
      const counts: Record<string, number> = {};

      const params = await fhirFetch<Parameters>(
        `${serverUrl}/$get-resource-counts`,
      );

      for (const param of params.parameter || []) {
        if (param.name && param.valueInteger !== undefined) {
          counts[param.name] = param.valueInteger;
        }
      }
      return counts;
    },
    staleTime: 60 * 1000,
    retry: 0,
    enabled: !!serverUrl && enabled,
  });
}

export function getPaginationLinks(bundle: Bundle | undefined) {
  if (!bundle?.link) {
    return {
      self: undefined,
      previous: undefined,
      next: undefined,
    };
  }

  const findLink = (relation: string) =>
    bundle.link?.find((l) => l.relation === relation)?.url;

  return {
    self: findLink("self"),
    previous: findLink("previous") || findLink("prev"),
    next: findLink("next"),
  };
}

export function useResourceSearchWithParams(
  serverUrl: string,
  resourceType: string,
  searchParams: Record<string, string>,
  pageUrl?: string,
  count: number = 50,
) {
  const url = useMemo(() => {
    if (pageUrl) return pageUrl;

    const params = new URLSearchParams();
    params.set("_count", count.toString());
    params.set("_total", "accurate");

    const sortedEntries = Object.entries(searchParams).sort(([a], [b]) =>
      a.localeCompare(b),
    );
    for (const [key, value] of sortedEntries) {
      if (value) {
        params.set(key, value);
      }
    }

    return `${serverUrl}/${resourceType}?${params.toString()}`;
  }, [serverUrl, resourceType, searchParams, pageUrl, count]);

  const searchParamsKey = useMemo(
    () => JSON.stringify(Object.entries(searchParams).sort()),
    [searchParams],
  );

  return useQuery({
    queryKey: ["fhir", "search", serverUrl, resourceType, searchParamsKey, url],
    queryFn: () => fhirFetch<Bundle<FhirResource>>(url),
    staleTime: 30 * 1000, // 30 seconds
    retry: 1,
    enabled: !!serverUrl && !!resourceType,
  });
}

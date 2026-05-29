import { afterEach, describe, expect, it, vi } from "vitest";
import { getServerTime } from "./server-time";

function mockResponse(options: {
  date?: string | null;
  body?: unknown;
}): Response {
  return {
    headers: {
      get: (name: string) => (name === "date" ? (options.date ?? null) : null),
    },
    json: async () => options.body,
  } as unknown as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("getServerTime", () => {
  it("prefers the HTTP Date header", async () => {
    const date = "Mon, 01 Jun 2026 12:00:00 GMT";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(mockResponse({ date, body: {} })),
    );

    const result = await getServerTime("http://localhost:8080/fhir");
    expect(result.source).toBe("date-header");
    expect(result.iso).toBe(new Date(date).toISOString());
  });

  it("falls back to bundle meta.lastUpdated when no Date header", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        mockResponse({
          date: null,
          body: { meta: { lastUpdated: "2026-06-01T00:00:00.000Z" } },
        }),
      ),
    );

    const result = await getServerTime("http://localhost:8080/fhir");
    expect(result.source).toBe("bundle-meta");
    expect(result.iso).toBe("2026-06-01T00:00:00.000Z");
  });

  it("falls back to client time when the request fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new Error("network down")),
    );

    const result = await getServerTime("http://localhost:8080/fhir");
    expect(result.source).toBe("client-fallback");
    expect(Number.isNaN(new Date(result.iso).getTime())).toBe(false);
  });
});

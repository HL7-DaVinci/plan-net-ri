import { describe, expect, it } from "vitest";
import { resourceKey } from "./db";
import { applyDeletions, computeDiff, type ExistingIndex } from "./diff";
import type { StoredResource } from "./types";

function stored(
  serverKey: string,
  resourceType: string,
  id: string,
  versionId?: string,
  lastUpdated?: string,
): StoredResource {
  return {
    key: resourceKey(serverKey, resourceType, id),
    serverKey,
    serverLabel: serverKey,
    resourceType,
    id,
    versionId,
    lastUpdated,
    resource: { resourceType, id } as StoredResource["resource"],
  };
}

const SERVER = "http://localhost:8080/fhir";

describe("computeDiff", () => {
  it("classifies added, updated, and unchanged by versionId", () => {
    const existing: ExistingIndex = new Map([
      [resourceKey(SERVER, "Organization", "a"), { versionId: "1" }],
      [resourceKey(SERVER, "Organization", "b"), { versionId: "1" }],
    ]);

    const incoming = [
      stored(SERVER, "Organization", "a", "1"), // unchanged
      stored(SERVER, "Organization", "b", "2"), // updated
      stored(SERVER, "Organization", "c", "1"), // added
    ];

    const result = computeDiff(incoming, existing);
    expect(result.unchanged.map((r) => r.id)).toEqual(["a"]);
    expect(result.updated.map((r) => r.id)).toEqual(["b"]);
    expect(result.added.map((r) => r.id)).toEqual(["c"]);
  });

  it("falls back to lastUpdated when versionId is absent", () => {
    const existing: ExistingIndex = new Map([
      [
        resourceKey(SERVER, "Location", "x"),
        { lastUpdated: "2026-01-01T00:00:00Z" },
      ],
    ]);
    const unchanged = stored(
      SERVER,
      "Location",
      "x",
      undefined,
      "2026-01-01T00:00:00Z",
    );
    const updated = stored(
      SERVER,
      "Location",
      "x",
      undefined,
      "2026-02-01T00:00:00Z",
    );

    expect(computeDiff([unchanged], existing).unchanged).toHaveLength(1);
    expect(computeDiff([updated], existing).updated).toHaveLength(1);
  });
});

describe("applyDeletions", () => {
  it("returns only keys that currently exist in the store", () => {
    const existing: ExistingIndex = new Map([
      [resourceKey(SERVER, "Practitioner", "p1"), { versionId: "1" }],
    ]);

    const keys = applyDeletions(
      [
        { resourceType: "Practitioner", id: "p1" }, // present -> removed
        { resourceType: "Practitioner", id: "ghost" }, // absent -> ignored
      ],
      SERVER,
      existing,
    );

    expect(keys).toEqual([resourceKey(SERVER, "Practitioner", "p1")]);
  });
});

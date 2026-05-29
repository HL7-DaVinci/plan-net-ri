import type { FhirResource } from "fhir/r4";
import { describe, expect, it } from "vitest";
import { resourceKey } from "./db";
import { buildNdjsonByType, createZip } from "./export-ndjson";
import type { StoredResource } from "./types";

function stored(
  type: string,
  id: string,
  extra: Record<string, unknown> = {},
): StoredResource {
  return {
    key: resourceKey("s", type, id),
    serverKey: "s",
    serverLabel: "s",
    resourceType: type,
    id,
    resource: { resourceType: type, id, ...extra } as FhirResource,
  };
}

describe("buildNdjsonByType", () => {
  it("groups resources into one NDJSON document per type", () => {
    const files = buildNdjsonByType([
      stored("Organization", "a"),
      stored("Organization", "b"),
      stored("Practitioner", "p1"),
    ]);

    expect([...files.keys()].sort()).toEqual(["Organization", "Practitioner"]);

    const orgLines = files.get("Organization")?.trimEnd().split("\n") ?? [];
    expect(orgLines).toHaveLength(2);
    // Each line is a parseable single resource.
    expect(JSON.parse(orgLines[0]).resourceType).toBe("Organization");
    expect(JSON.parse(orgLines[1]).id).toBe("b");

    // File ends with a trailing newline (NDJSON convention).
    expect(files.get("Practitioner")?.endsWith("\n")).toBe(true);
  });
});

describe("createZip", () => {
  it("produces a non-empty zip blob with the PK signature", async () => {
    const blob = createZip([
      {
        name: "Organization.ndjson",
        content: '{"resourceType":"Organization"}\n',
      },
    ]);
    expect(blob.type).toBe("application/zip");
    expect(blob.size).toBeGreaterThan(0);

    const bytes = new Uint8Array(await blob.arrayBuffer());
    // Local file header signature: 0x50 0x4b 0x03 0x04 ("PK..")
    expect([bytes[0], bytes[1], bytes[2], bytes[3]]).toEqual([
      0x50, 0x4b, 0x03, 0x04,
    ]);
  });
});

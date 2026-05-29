import type { FhirResource } from "fhir/r4";
import { describe, expect, it } from "vitest";
import { resourceKey } from "./db";
import { findDuplicates } from "./duplicates";
import type { StoredResource } from "./types";

const NPI = "http://hl7.org/fhir/sid/us-npi";

function practitioner(
  serverKey: string,
  id: string,
  npi: string,
): StoredResource {
  return {
    key: resourceKey(serverKey, "Practitioner", id),
    serverKey,
    serverLabel: serverKey,
    resourceType: "Practitioner",
    id,
    resource: {
      resourceType: "Practitioner",
      id,
      identifier: [{ system: NPI, value: npi }],
    } as FhirResource,
  };
}

const SERVER_A = "http://a/fhir";
const SERVER_B = "http://b/fhir";

describe("findDuplicates", () => {
  it("flags a shared NPI across two servers", () => {
    const groups = findDuplicates([
      practitioner(SERVER_A, "1", "1234567890"),
      practitioner(SERVER_B, "2", "1234567890"),
      practitioner(SERVER_A, "3", "9999999999"),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].identifierValue).toBe("1234567890");
    expect(groups[0].members.map((m) => m.serverKey).sort()).toEqual([
      SERVER_A,
      SERVER_B,
    ]);
  });

  it("does not flag the same NPI repeated on a single server", () => {
    const groups = findDuplicates([
      practitioner(SERVER_A, "1", "1234567890"),
      practitioner(SERVER_A, "2", "1234567890"),
    ]);
    expect(groups).toHaveLength(0);
  });

  it("ignores resources without identifiers", () => {
    const noId: StoredResource = {
      key: resourceKey(SERVER_A, "Location", "L1"),
      serverKey: SERVER_A,
      serverLabel: SERVER_A,
      resourceType: "Location",
      id: "L1",
      resource: { resourceType: "Location", id: "L1" } as FhirResource,
    };
    expect(findDuplicates([noId])).toHaveLength(0);
  });
});

import type { Bundle } from "fhir/r4";
import { describe, expect, it } from "vitest";
import { extractDeletions } from "./deletion-scan";

describe("extractDeletions", () => {
  it("collects DELETE entries and ignores creates/updates", () => {
    const bundle: Bundle = {
      resourceType: "Bundle",
      type: "history",
      entry: [
        {
          fullUrl: "http://host/fhir/Organization/123",
          request: { method: "DELETE", url: "Organization/123" },
        },
        {
          // an update should be ignored here
          resource: { resourceType: "Organization", id: "456" },
          request: { method: "PUT", url: "Organization/456" },
        },
        {
          // a create should be ignored here
          resource: { resourceType: "Practitioner", id: "789" },
          request: { method: "POST", url: "Practitioner" },
        },
      ],
    };

    expect(extractDeletions(bundle)).toEqual([
      { resourceType: "Organization", id: "123" },
    ]);
  });

  it("parses an absolute fullUrl when request.url is missing", () => {
    const bundle: Bundle = {
      resourceType: "Bundle",
      type: "history",
      entry: [
        {
          fullUrl: "https://example.org/fhir/HealthcareService/abc",
          request: { method: "DELETE", url: "" },
        },
      ],
    };

    expect(extractDeletions(bundle)).toEqual([
      { resourceType: "HealthcareService", id: "abc" },
    ]);
  });

  it("returns an empty list for a bundle with no deletions", () => {
    expect(
      extractDeletions({ resourceType: "Bundle", type: "history" }),
    ).toEqual([]);
  });
});

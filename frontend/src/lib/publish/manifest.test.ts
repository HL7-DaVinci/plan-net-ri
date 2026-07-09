import { describe, expect, it } from "vitest";
import {
  buildManifestCurl,
  fileOrigin,
  parseIso8601DurationMs,
  pollDelayMs,
  publishApiBase,
} from "./manifest";

describe("parseIso8601DurationMs", () => {
  it("parses seconds, minutes, hours, and combinations", () => {
    expect(parseIso8601DurationMs("PT30S")).toBe(30_000);
    expect(parseIso8601DurationMs("PT1M")).toBe(60_000);
    expect(parseIso8601DurationMs("PT1M30S")).toBe(90_000);
    expect(parseIso8601DurationMs("PT2H")).toBe(7_200_000);
    expect(parseIso8601DurationMs("PT0.5S")).toBe(500);
  });

  it("returns null for absent or unparsable values", () => {
    expect(parseIso8601DurationMs(undefined)).toBeNull();
    expect(parseIso8601DurationMs(null)).toBeNull();
    expect(parseIso8601DurationMs("")).toBeNull();
    expect(parseIso8601DurationMs("PT")).toBeNull();
    expect(parseIso8601DurationMs("1 minute")).toBeNull();
  });
});

describe("pollDelayMs", () => {
  it("uses the advertised cadence", () => {
    expect(pollDelayMs("PT1M")).toBe(60_000);
  });

  it("clamps to a 5 second floor", () => {
    expect(pollDelayMs("PT2S")).toBe(5_000);
  });

  it("defaults to 30 seconds when absent or unparsable", () => {
    expect(pollDelayMs(undefined)).toBe(30_000);
    expect(pollDelayMs("garbage")).toBe(30_000);
  });
});

describe("buildManifestCurl", () => {
  it("builds a plain fetch without an etag", () => {
    expect(buildManifestCurl("http://localhost:8080/fhir/$bulk-publish")).toBe(
      "curl -i -H 'Accept: application/json' 'http://localhost:8080/fhir/$bulk-publish'",
    );
  });

  it("adds If-None-Match when an etag is known", () => {
    expect(
      buildManifestCurl("http://localhost:8080/fhir/$bulk-publish", '"abc"'),
    ).toBe(
      "curl -i -H 'Accept: application/json' -H 'If-None-Match: \"abc\"' 'http://localhost:8080/fhir/$bulk-publish'",
    );
  });
});

describe("publishApiBase", () => {
  it("strips a trailing /fhir from the server base", () => {
    expect(publishApiBase("http://localhost:8080/fhir")).toBe(
      "http://localhost:8080",
    );
    expect(publishApiBase("https://example.org/fhir/")).toBe(
      "https://example.org",
    );
  });

  it("only strips trailing slashes when the base does not end in /fhir", () => {
    expect(publishApiBase("https://example.org/r4/")).toBe(
      "https://example.org/r4",
    );
  });
});

describe("fileOrigin", () => {
  it("classifies a file owned by the snapshot as exported", () => {
    expect(
      fileOrigin("snap-a", {
        type: "Organization",
        count: 1,
        fileSize: 10,
        snapshotId: "snap-a",
      }),
    ).toBe("exported");
  });

  it("classifies a file owned by an earlier snapshot as reused", () => {
    expect(
      fileOrigin("snap-b", {
        type: "Location",
        count: 1,
        fileSize: 10,
        snapshotId: "snap-a",
      }),
    ).toBe("reused");
  });
});

import { describe, expect, it } from "vitest";
import { PLAN_NET_RESOURCE_TYPES } from "@/lib/plan-net-types";
import { SAMPLE_RESOURCES } from "./sample-resources";

describe("SAMPLE_RESOURCES", () => {
  it("provides a template for every Plan-Net type with a matching resourceType", () => {
    for (const type of PLAN_NET_RESOURCE_TYPES) {
      const sample = SAMPLE_RESOURCES[type]();
      expect(sample.resourceType).toBe(type);
    }
  });

  it("returns a fresh object on each call", () => {
    expect(SAMPLE_RESOURCES.Organization()).not.toBe(
      SAMPLE_RESOURCES.Organization(),
    );
  });
});

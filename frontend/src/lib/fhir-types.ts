import type { OperationOutcome } from "fhir/r4";

export function isOperationOutcome(
  resource: unknown,
): resource is OperationOutcome {
  return (
    typeof resource === "object" &&
    resource !== null &&
    "resourceType" in resource &&
    resource.resourceType === "OperationOutcome"
  );
}

import type { Medication } from "fhir/r4";
import { codeableConceptColumn, statusBadgeColumn } from "./helpers";
import type { ResourceColumnTemplate } from "./types";

export const medicationTemplate: ResourceColumnTemplate = {
  resourceType: "Medication",
  columns: [
    codeableConceptColumn<Medication>("code", "Medication", (row) => row.code, {
      size: 250,
      enableSorting: true,
      sortParam: "code",
    }),
    statusBadgeColumn<Medication>("status", "Status", (row) => row.status, {
      size: 100,
      enableSorting: true,
      sortParam: "status",
    }),
  ],
};

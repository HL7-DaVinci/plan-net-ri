import type { Observation } from "fhir/r4";
import { codeableConceptColumn, statusBadgeColumn } from "./helpers";
import type { ResourceColumnTemplate } from "./types";

export const observationTemplate: ResourceColumnTemplate = {
  resourceType: "Observation",
  columns: [
    codeableConceptColumn<Observation>("code", "Code", (row) => row.code, {
      size: 200,
      enableSorting: true,
      sortParam: "code",
    }),
    statusBadgeColumn<Observation>("status", "Status", (row) => row.status, {
      size: 100,
      enableSorting: true,
      sortParam: "status",
    }),
  ],
};

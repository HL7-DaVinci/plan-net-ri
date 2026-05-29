import type { InsurancePlan } from "fhir/r4";
import {
  codeableConceptColumn,
  statusBadgeColumn,
  textColumn,
} from "./helpers";
import type { ResourceColumnTemplate } from "./types";

export const insurancePlanTemplate: ResourceColumnTemplate = {
  resourceType: "InsurancePlan",
  columns: [
    textColumn<InsurancePlan>("name", "Name", (row) => row.name, {
      size: 250,
      enableSorting: true,
      sortParam: "name",
    }),
    codeableConceptColumn<InsurancePlan>(
      "type",
      "Type",
      (row) => row.type?.[0],
      { size: 160 },
    ),
    statusBadgeColumn<InsurancePlan>("status", "Status", (row) => row.status, {
      size: 100,
      enableSorting: true,
      sortParam: "status",
    }),
  ],
};

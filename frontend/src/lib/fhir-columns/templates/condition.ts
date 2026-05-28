import type { Condition } from "fhir/r4";
import { codeableConceptColumn, statusBadgeColumn } from "./helpers";
import type { ResourceColumnTemplate } from "./types";

export const conditionTemplate: ResourceColumnTemplate = {
  resourceType: "Condition",
  columns: [
    codeableConceptColumn<Condition>("code", "Condition", (row) => row.code, {
      size: 250,
      enableSorting: true,
      sortParam: "code",
    }),
    statusBadgeColumn<Condition>(
      "clinicalStatus",
      "Clinical Status",
      (row) => row.clinicalStatus?.coding?.[0]?.code,
      {
        size: 120,
        enableSorting: true,
        sortParam: "clinical-status",
      },
    ),
  ],
};

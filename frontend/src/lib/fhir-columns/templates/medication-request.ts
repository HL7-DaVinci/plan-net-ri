import type { MedicationRequest } from "fhir/r4";
import { codeableConceptColumn, statusBadgeColumn } from "./helpers";
import type { ResourceColumnTemplate } from "./types";

export const medicationRequestTemplate: ResourceColumnTemplate = {
  resourceType: "MedicationRequest",
  columns: [
    codeableConceptColumn<MedicationRequest>(
      "medication",
      "Medication",
      (row) => row.medicationCodeableConcept,
      {
        size: 250,
        enableSorting: true,
        sortParam: "medication",
      },
    ),
    statusBadgeColumn<MedicationRequest>(
      "status",
      "Status",
      (row) => row.status,
      {
        size: 100,
        enableSorting: true,
        sortParam: "status",
      },
    ),
  ],
};

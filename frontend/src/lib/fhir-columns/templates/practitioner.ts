import type { Practitioner } from "fhir/r4";
import { activeBadgeColumn, humanNameColumn } from "./helpers";
import type { ResourceColumnTemplate } from "./types";

export const practitionerTemplate: ResourceColumnTemplate = {
  resourceType: "Practitioner",
  columns: [
    humanNameColumn<Practitioner>("name", "Name", (row) => row.name, {
      size: 200,
      enableSorting: true,
      sortParam: "family,given",
    }),
    activeBadgeColumn<Practitioner>("active", "Status", (row) => row.active, {
      size: 80,
      enableSorting: true,
      sortParam: "active",
    }),
  ],
};

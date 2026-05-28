import type { Organization } from "fhir/r4";
import { activeBadgeColumn, textColumn } from "./helpers";
import type { ResourceColumnTemplate } from "./types";

export const organizationTemplate: ResourceColumnTemplate = {
  resourceType: "Organization",
  columns: [
    textColumn<Organization>("name", "Name", (row) => row.name, {
      size: 250,
      enableSorting: true,
      sortParam: "name",
    }),
    activeBadgeColumn<Organization>("active", "Status", (row) => row.active, {
      size: 80,
      enableSorting: true,
      sortParam: "active",
    }),
  ],
};

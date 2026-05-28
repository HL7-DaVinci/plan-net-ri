import type { Patient } from "fhir/r4";
import { Badge } from "@/components/ui/badge";
import { dateColumn, humanNameColumn } from "./helpers";
import type { FhirColumnMeta, ResourceColumnTemplate } from "./types";

export const patientTemplate: ResourceColumnTemplate = {
  resourceType: "Patient",
  columns: [
    humanNameColumn<Patient>("name", "Name", (row) => row.name, {
      size: 200,
      enableSorting: true,
      sortParam: "family,given",
    }),
    dateColumn<Patient>("birthDate", "Birth Date", (row) => row.birthDate, {
      size: 100,
      enableSorting: true,
      sortParam: "birthdate",
    }),
    {
      id: "gender",
      header: "Gender",
      size: 80,
      enableSorting: true,
      meta: { sortParam: "gender" } satisfies FhirColumnMeta,
      accessorFn: (row) => (row as Patient).gender,
      cell: ({ getValue }) => (
        <Badge variant="outline" className="text-xs">
          {(getValue() as string) || "-"}
        </Badge>
      ),
    },
  ],
};

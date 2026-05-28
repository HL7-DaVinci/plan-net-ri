import type { ColumnDef } from "@tanstack/react-table";
import type { Encounter, FhirResource } from "fhir/r4";
import { Badge } from "@/components/ui/badge";
import type { FhirColumnMeta, ResourceColumnTemplate } from "./types";

export const encounterTemplate: ResourceColumnTemplate = {
  resourceType: "Encounter",
  columns: [
    {
      id: "class",
      header: "Class",
      size: 120,
      enableSorting: true,
      meta: { sortParam: "class" } satisfies FhirColumnMeta,
      accessorFn: (row) => {
        const encounter = row as Encounter;
        // R4 has class as Coding, R4B+ has class as array
        const cls = encounter.class;
        if (Array.isArray(cls)) {
          return cls[0]?.display || cls[0]?.code || "-";
        }
        return cls?.display || cls?.code || "-";
      },
    } satisfies ColumnDef<FhirResource, unknown>,
    {
      id: "status",
      header: "Status",
      size: 100,
      enableSorting: true,
      meta: { sortParam: "status" } satisfies FhirColumnMeta,
      accessorFn: (row) => (row as Encounter).status,
      cell: ({ getValue }) => (
        <Badge variant="outline">{(getValue() as string) || "-"}</Badge>
      ),
    } satisfies ColumnDef<FhirResource, unknown>,
  ],
};

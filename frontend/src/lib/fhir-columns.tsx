import type { ColumnDef } from "@tanstack/react-table";
import type { FhirResource } from "fhir/r4";
import { Code } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getTemplate } from "@/lib/fhir-columns/templates";
import { dateColumn, textColumn } from "./fhir-columns/templates/helpers";

export function getColumnsForResourceType(
  resourceType: string,
  onViewJson: (resource: FhirResource) => void,
): ColumnDef<FhirResource, unknown>[] {
  const baseColumns: ColumnDef<FhirResource, unknown>[] = [
    textColumn<FhirResource>("id", "ID", (row) => row.id, {
      size: 100,
      enableSorting: true,
      sortParam: "_id",
    }),
  ];

  const template = getTemplate(resourceType);

  const metaColumns: ColumnDef<FhirResource, unknown>[] = [
    dateColumn<FhirResource>(
      "lastUpdated",
      "Last Updated",
      (row) => row.meta?.lastUpdated,
      { size: 120, enableSorting: true, sortParam: "_lastUpdated" },
    ),
    {
      id: "actions",
      header: "",
      size: 80,
      enableSorting: false,
      enableResizing: false,
      cell: ({ row }) => (
        <Button
          variant="ghost"
          size="sm"
          className="h-7 px-2"
          onClick={(e) => {
            e.stopPropagation();
            onViewJson(row.original);
          }}
        >
          <Code className="h-3.5 w-3.5 mr-1" />
          JSON
        </Button>
      ),
    },
  ];

  return [...baseColumns, ...template.columns, ...metaColumns];
}

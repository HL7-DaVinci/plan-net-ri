import { Badge } from "@/components/ui/badge";
import type { ResourceColumnTemplate } from "./types";

/**
 * Default template used when no resource-specific template is found.
 * Shows the resource type as the only additional column.
 */
export const defaultTemplate: ResourceColumnTemplate = {
  resourceType: "_default",
  columns: [
    {
      id: "resourceType",
      accessorKey: "resourceType",
      header: "Type",
      size: 150,
      enableSorting: false,
      cell: ({ getValue }) => (
        <Badge variant="secondary">{getValue() as string}</Badge>
      ),
    },
  ],
};

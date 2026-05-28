import type { ColumnDef } from "@tanstack/react-table";
import type { CodeableConcept, FhirResource, HumanName } from "fhir/r4";
import { Badge } from "@/components/ui/badge";
import type { FhirColumnMeta } from "./types";

/** Common options for column helper functions */
interface ColumnOptions {
  /** Column width in pixels */
  size?: number;
  /** Enable sorting on this column (requires sortParam for server-side sorting) */
  enableSorting?: boolean;
  /** FHIR search parameter name for server-side sorting (e.g., "family", "date") */
  sortParam?: string;
}

/**
 * Creates a column that displays text from a CodeableConcept field.
 * Tries: text -> coding[0].display -> fallback
 */
export function codeableConceptColumn<T extends FhirResource>(
  id: string,
  header: string,
  accessor: (row: T) => CodeableConcept | undefined,
  options: ColumnOptions = {},
): ColumnDef<FhirResource, unknown> {
  const { size = 200, enableSorting = false, sortParam } = options;
  return {
    id,
    header,
    size,
    enableSorting: enableSorting && !!sortParam,
    meta: { sortParam } satisfies FhirColumnMeta,
    accessorFn: (row) => {
      const concept = accessor(row as T);
      return concept?.text || concept?.coding?.[0]?.display || "-";
    },
  };
}

/**
 * Creates a column that displays a status field as a badge.
 */
export function statusBadgeColumn<T extends FhirResource>(
  id: string,
  header: string,
  accessor: (row: T) => string | undefined,
  options: ColumnOptions = {},
): ColumnDef<FhirResource, unknown> {
  const { size = 100, enableSorting = false, sortParam } = options;
  return {
    id,
    header,
    size,
    enableSorting: enableSorting && !!sortParam,
    meta: { sortParam } satisfies FhirColumnMeta,
    accessorFn: (row) => accessor(row as T),
    cell: ({ getValue }) => (
      <Badge variant="outline">{(getValue() as string) || "-"}</Badge>
    ),
  };
}

/**
 * Creates a column that displays an active/inactive status as a badge.
 */
export function activeBadgeColumn<T extends FhirResource>(
  id: string,
  header: string,
  accessor: (row: T) => boolean | undefined,
  options: ColumnOptions = {},
): ColumnDef<FhirResource, unknown> {
  const { size = 80, enableSorting = false, sortParam } = options;
  return {
    id,
    header,
    size,
    enableSorting: enableSorting && !!sortParam,
    meta: { sortParam } satisfies FhirColumnMeta,
    accessorFn: (row) => accessor(row as T),
    cell: ({ getValue }) => (
      <Badge variant={getValue() ? "default" : "secondary"}>
        {getValue() ? "Active" : "Inactive"}
      </Badge>
    ),
  };
}

/**
 * Creates a column that displays a HumanName (given + family).
 */
export function humanNameColumn<T extends FhirResource>(
  id: string,
  header: string,
  accessor: (row: T) => HumanName[] | undefined,
  options: ColumnOptions = {},
): ColumnDef<FhirResource, unknown> {
  const { size = 200, enableSorting = false, sortParam } = options;
  return {
    id,
    header,
    size,
    enableSorting: enableSorting && !!sortParam,
    meta: { sortParam } satisfies FhirColumnMeta,
    accessorFn: (row) => {
      const names = accessor(row as T);
      const name = names?.[0];
      if (!name) return "-";
      const given = name.given?.join(" ") || "";
      return `${given} ${name.family || ""}`.trim() || "-";
    },
  };
}

/**
 * Creates a simple text column with optional accessor.
 */
export function textColumn<T extends FhirResource>(
  id: string,
  header: string,
  accessor: (row: T) => string | undefined,
  options: ColumnOptions = {},
): ColumnDef<FhirResource, unknown> {
  const { size = 150, enableSorting = false, sortParam } = options;
  return {
    id,
    header,
    size,
    enableSorting: enableSorting && !!sortParam,
    meta: { sortParam } satisfies FhirColumnMeta,
    accessorFn: (row) => accessor(row as T),
    cell: ({ getValue }) => (getValue() as string) || "-",
  };
}

/**
 * Creates a date column that formats the date string.
 */
export function dateColumn<T extends FhirResource>(
  id: string,
  header: string,
  accessor: (row: T) => string | undefined,
  options: ColumnOptions = {},
): ColumnDef<FhirResource, unknown> {
  const { size = 120, enableSorting = false, sortParam } = options;
  return {
    id,
    header,
    size,
    enableSorting: enableSorting && !!sortParam,
    meta: { sortParam } satisfies FhirColumnMeta,
    accessorFn: (row) => accessor(row as T),
    cell: ({ getValue }) => {
      const dateStr = getValue() as string | undefined;
      if (!dateStr) return "-";
      return new Date(dateStr).toLocaleDateString();
    },
  };
}

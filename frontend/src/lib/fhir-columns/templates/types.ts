import type { ColumnDef } from "@tanstack/react-table";
import type { FhirResource } from "fhir/r4";

/**
 * Extended column meta for FHIR-specific column configuration.
 * Use this to specify the FHIR search parameter name for server-side sorting.
 */
export interface FhirColumnMeta {
  /** The FHIR search parameter name used for sorting (e.g., "family", "date", "_lastUpdated") */
  sortParam?: string;
}

/**
 * Template definition for resource-specific columns.
 * Each resource type can define its own template to customize
 * which columns appear in the data table.
 */
export interface ResourceColumnTemplate {
  /** The FHIR resource type this template applies to (e.g., "Patient", "Observation") */
  resourceType: string;
  /** Column definitions specific to this resource type */
  columns: ColumnDef<FhirResource, unknown>[];
}

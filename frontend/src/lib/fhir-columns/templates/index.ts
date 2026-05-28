import { conditionTemplate } from "./condition";
import { defaultTemplate } from "./default";
import { encounterTemplate } from "./encounter";
import { medicationTemplate } from "./medication";
import { medicationRequestTemplate } from "./medication-request";
import { observationTemplate } from "./observation";
import { organizationTemplate } from "./organization";
import { patientTemplate } from "./patient";
import { practitionerTemplate } from "./practitioner";
import type { ResourceColumnTemplate } from "./types";

/**
 * Registry of all resource column templates.
 * To add a new resource type:
 * 1. Create a new file in templates/ (e.g., `allergy-intolerance.ts`)
 * 2. Export a template object implementing ResourceColumnTemplate
 * 3. Import and add it to this array
 */
const templates: ResourceColumnTemplate[] = [
  conditionTemplate,
  encounterTemplate,
  medicationTemplate,
  medicationRequestTemplate,
  observationTemplate,
  organizationTemplate,
  patientTemplate,
  practitionerTemplate,
];

/** Map for O(1) lookup by resource type */
const templateMap = new Map<string, ResourceColumnTemplate>(
  templates.map((t) => [t.resourceType, t]),
);

/**
 * Get the column template for a specific resource type.
 * Returns the default template if no specific template exists.
 */
export function getTemplate(resourceType: string): ResourceColumnTemplate {
  return templateMap.get(resourceType) ?? defaultTemplate;
}

export { defaultTemplate } from "./default";
export type { FhirColumnMeta, ResourceColumnTemplate } from "./types";

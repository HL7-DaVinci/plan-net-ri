/**
 * The curated set of FHIR resource types defined by the Da Vinci PDex Plan-Net
 * Implementation Guide. These are the types the directory crawler walks.
 *
 * Note: "Network" is represented as an Organization with the plannet-Network
 * profile, so it is covered by the Organization type below.
 */
export const PLAN_NET_RESOURCE_TYPES = [
  "Endpoint",
  "HealthcareService",
  "InsurancePlan",
  "Location",
  "Organization",
  "OrganizationAffiliation",
  "Practitioner",
  "PractitionerRole",
] as const;

export type PlanNetResourceType = (typeof PLAN_NET_RESOURCE_TYPES)[number];

/** The NPI identifier system used to flag the same provider across directories. */
export const NPI_IDENTIFIER_SYSTEM = "http://hl7.org/fhir/sid/us-npi";

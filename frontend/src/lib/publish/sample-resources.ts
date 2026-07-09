import type { PlanNetResourceType } from "@/lib/plan-net-types";

/** Minimal starting points for the demo mutation panel; edited before submission. */
export const SAMPLE_RESOURCES: Record<
  PlanNetResourceType,
  () => Record<string, unknown>
> = {
  Endpoint: () => ({
    resourceType: "Endpoint",
    status: "active",
    connectionType: {
      system: "http://terminology.hl7.org/CodeSystem/endpoint-connection-type",
      code: "hl7-fhir-rest",
    },
    payloadType: [
      {
        coding: [
          {
            system:
              "http://terminology.hl7.org/CodeSystem/endpoint-payload-type",
            code: "any",
          },
        ],
      },
    ],
    address: "https://demo.example.org/fhir",
    name: "Demo Endpoint",
  }),
  HealthcareService: () => ({
    resourceType: "HealthcareService",
    active: true,
    name: "Demo Healthcare Service",
  }),
  InsurancePlan: () => ({
    resourceType: "InsurancePlan",
    status: "active",
    name: "Demo Insurance Plan",
  }),
  Location: () => ({
    resourceType: "Location",
    status: "active",
    name: "Demo Location",
  }),
  Organization: () => ({
    resourceType: "Organization",
    active: true,
    name: "Demo Organization",
  }),
  OrganizationAffiliation: () => ({
    resourceType: "OrganizationAffiliation",
    active: true,
  }),
  Practitioner: () => ({
    resourceType: "Practitioner",
    active: true,
    name: [{ family: "Demo", given: ["Pat"] }],
  }),
  PractitionerRole: () => ({
    resourceType: "PractitionerRole",
    active: true,
  }),
};

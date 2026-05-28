import {
  Activity,
  Beaker,
  Building2,
  CalendarClock,
  Clipboard,
  ClipboardList,
  FileJson,
  FileText,
  Heart,
  HeartPulse,
  Hospital,
  type LucideIcon,
  Microscope,
  Pill,
  Receipt,
  Shield,
  Stethoscope,
  Syringe,
  TestTube2,
  User,
  UserCheck,
  Users,
} from "lucide-react";

export const resourceIconMap: Record<string, LucideIcon> = {
  Patient: User,
  Practitioner: Stethoscope,
  PractitionerRole: UserCheck,
  RelatedPerson: Users,
  Person: User,

  Organization: Building2,
  Location: Hospital,
  HealthcareService: HeartPulse,
  Endpoint: Activity,

  Condition: Activity,
  Procedure: Clipboard,
  Observation: TestTube2,
  DiagnosticReport: Microscope,
  CarePlan: ClipboardList,
  CareTeam: Users,
  Goal: Heart,
  AllergyIntolerance: HeartPulse,
  FamilyMemberHistory: Users,
  ClinicalImpression: FileText,
  DetectedIssue: Activity,
  RiskAssessment: Activity,
  ImagingStudy: Microscope,
  Specimen: Beaker,
  BodyStructure: User,

  Medication: Pill,
  MedicationRequest: Pill,
  MedicationAdministration: Syringe,
  MedicationDispense: Pill,
  MedicationStatement: Pill,
  Immunization: Syringe,
  ImmunizationEvaluation: Syringe,
  ImmunizationRecommendation: Syringe,

  Coverage: Shield,
  Claim: Receipt,
  ClaimResponse: Receipt,
  ExplanationOfBenefit: FileText,
  PaymentNotice: Receipt,
  PaymentReconciliation: Receipt,
  Account: Receipt,
  ChargeItem: Receipt,
  ChargeItemDefinition: Receipt,
  Contract: FileText,
  InsurancePlan: Shield,

  Encounter: CalendarClock,
  EpisodeOfCare: CalendarClock,
  Appointment: CalendarClock,
  AppointmentResponse: CalendarClock,
  Schedule: CalendarClock,
  Slot: CalendarClock,

  Device: Activity,
  DeviceDefinition: Activity,
  DeviceMetric: Activity,
  DeviceRequest: Activity,
  DeviceUseStatement: Activity,

  DocumentReference: FileText,
  DocumentManifest: FileText,
  Binary: FileJson,
  Composition: FileText,

  ServiceRequest: ClipboardList,
  NutritionOrder: ClipboardList,
  VisionPrescription: ClipboardList,
  SupplyRequest: ClipboardList,
  SupplyDelivery: ClipboardList,

  Questionnaire: ClipboardList,
  QuestionnaireResponse: ClipboardList,
  Communication: FileText,
  CommunicationRequest: FileText,
  Consent: FileText,

  MedicinalProduct: Pill,
  MedicinalProductIngredient: Beaker,
  MedicinalProductPackaged: Pill,
  Substance: Beaker,
  SubstanceSpecification: Beaker,

  Bundle: FileJson,
  CapabilityStatement: FileJson,
  OperationOutcome: FileJson,
  StructureDefinition: FileJson,
  ValueSet: FileJson,
  CodeSystem: FileJson,
  NamingSystem: FileJson,
  ConceptMap: FileJson,
  SearchParameter: FileJson,
  OperationDefinition: FileJson,
  CompartmentDefinition: FileJson,
  ImplementationGuide: FileJson,
  MessageDefinition: FileJson,
  GraphDefinition: FileJson,
  TerminologyCapabilities: FileJson,

  Provenance: FileText,
  AuditEvent: FileText,

  Group: Users,
  ResearchStudy: Microscope,
  ResearchSubject: User,
};

export const resourceCategories: Record<string, string[]> = {
  Individuals: [
    "Patient",
    "Practitioner",
    "PractitionerRole",
    "RelatedPerson",
    "Person",
    "Group",
  ],
  Entities: [
    "Organization",
    "Location",
    "HealthcareService",
    "Endpoint",
    "Device",
    "DeviceDefinition",
  ],
  Clinical: [
    "Condition",
    "Procedure",
    "Observation",
    "DiagnosticReport",
    "CarePlan",
    "CareTeam",
    "Goal",
    "AllergyIntolerance",
    "FamilyMemberHistory",
    "ClinicalImpression",
    "RiskAssessment",
    "ImagingStudy",
    "Specimen",
  ],
  Medications: [
    "Medication",
    "MedicationRequest",
    "MedicationAdministration",
    "MedicationDispense",
    "MedicationStatement",
    "Immunization",
    "ImmunizationEvaluation",
    "ImmunizationRecommendation",
  ],
  Workflow: [
    "Encounter",
    "EpisodeOfCare",
    "Appointment",
    "AppointmentResponse",
    "Schedule",
    "Slot",
    "ServiceRequest",
    "NutritionOrder",
  ],
  Financial: [
    "Coverage",
    "Claim",
    "ClaimResponse",
    "ExplanationOfBenefit",
    "Account",
    "ChargeItem",
    "PaymentNotice",
    "PaymentReconciliation",
    "InsurancePlan",
    "Contract",
  ],
  Documents: [
    "DocumentReference",
    "DocumentManifest",
    "Composition",
    "Binary",
    "Consent",
    "Communication",
    "CommunicationRequest",
  ],
  Questionnaires: ["Questionnaire", "QuestionnaireResponse"],
  Conformance: [
    "CapabilityStatement",
    "StructureDefinition",
    "ValueSet",
    "CodeSystem",
    "ConceptMap",
    "SearchParameter",
    "OperationDefinition",
    "ImplementationGuide",
    "NamingSystem",
    "CompartmentDefinition",
    "GraphDefinition",
    "TerminologyCapabilities",
  ],
};

export function getResourceIcon(resourceType: string): LucideIcon {
  return resourceIconMap[resourceType] || FileJson;
}

export function getResourceCategory(resourceType: string): string | null {
  for (const [category, types] of Object.entries(resourceCategories)) {
    if (types.includes(resourceType)) {
      return category;
    }
  }
  return null;
}

export function groupResourcesByCategory(
  resourceTypes: string[],
): Record<string, string[]> {
  const grouped: Record<string, string[]> = {};
  const uncategorized: string[] = [];

  for (const type of resourceTypes) {
    const category = getResourceCategory(type);
    if (category) {
      if (!grouped[category]) {
        grouped[category] = [];
      }
      grouped[category].push(type);
    } else {
      uncategorized.push(type);
    }
  }

  if (uncategorized.length > 0) {
    grouped.Other = uncategorized;
  }

  return grouped;
}

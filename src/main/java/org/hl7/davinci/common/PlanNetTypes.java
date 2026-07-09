package org.hl7.davinci.common;

import java.util.List;

/** The Plan-Net resource types crawled and published by this server. */
public final class PlanNetTypes {

	/** The 8 Plan-Net resource types. No Bundle. */
	public static final List<String> TYPES = List.of(
			"Endpoint",
			"HealthcareService",
			"InsurancePlan",
			"Location",
			"Organization",
			"OrganizationAffiliation",
			"Practitioner",
			"PractitionerRole");

	private PlanNetTypes() {}
}

package org.hl7.davinci.common;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

	/** Stable storage ids for crawl_resource.type_id. Never renumber. */
	private static final Map<String, Integer> TYPE_TO_ID = Map.ofEntries(
			Map.entry("Endpoint", 1),
			Map.entry("HealthcareService", 2),
			Map.entry("InsurancePlan", 3),
			Map.entry("Location", 4),
			Map.entry("Organization", 5),
			Map.entry("OrganizationAffiliation", 6),
			Map.entry("Practitioner", 7),
			Map.entry("PractitionerRole", 8));

	private static final Map<Integer, String> ID_TO_TYPE =
			TYPE_TO_ID.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

	public static int idOf(String type) {
		Integer id = TYPE_TO_ID.get(type);
		if (id == null) {
			throw new IllegalArgumentException("Unknown Plan-Net resource type: " + type);
		}
		return id;
	}

	public static String typeOf(int id) {
		String type = ID_TO_TYPE.get(id);
		if (type == null) {
			throw new IllegalArgumentException("Unknown crawl_resource type id: " + id);
		}
		return type;
	}

	private PlanNetTypes() {}
}

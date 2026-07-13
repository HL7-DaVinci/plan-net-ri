package org.hl7.davinci.api.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * One target server in a crawl job's scope. Matches the frontend {@code ScopeServer}
 * shape persisted in {@code crawl_job.servers} as JSON. {@code serverKey} is advisory;
 * the crawler always derives the canonical key from {@code url}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerScope(String serverKey, String serverLabel, String url) {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Parse the {@code crawl_job.servers} JSON array; null or blank means no servers. */
	public static List<ServerScope> parseList(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return List.of(MAPPER.readValue(json, ServerScope[].class));
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid servers JSON: " + e.getMessage(), e);
		}
	}
}

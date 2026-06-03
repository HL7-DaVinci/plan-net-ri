package org.hl7.davinci.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** The served manifest body (Bulk Export style). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestJson(
		String transactionTime,
		String request,
		boolean requiresAccessToken,
		List<OutputEntry> output,
		List<Object> error) {

	public record OutputEntry(String type, String url, long count) {}
}

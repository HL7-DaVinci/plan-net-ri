package org.hl7.davinci.publish;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The served $bulk-publish manifest body (Bulk Data $bulk-publish operation). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulkPublishManifestJson(
		String manifestType,
		String transactionTime,
		String updateCadence,
		boolean requiresAccessToken,
		List<OutputEntry> output) {

	public record OutputEntry(String type, String url, long count, long fileSize) {}
}

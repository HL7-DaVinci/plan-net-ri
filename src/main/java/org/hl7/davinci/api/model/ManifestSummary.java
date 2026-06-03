package org.hl7.davinci.api.model;

/** Summary of a retained snapshot for the list endpoint. */
public record ManifestSummary(
		String id,
		String jobId,
		String jobName,
		String batchId,
		String transactionTime,
		String generatedAt,
		long totalResources,
		String windowSince,
		long buildDurationMs) {}

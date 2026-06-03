package org.hl7.davinci.api.model;

/** A crawl run as returned by the API. */
public record RunResponse(
		String id,
		String jobId,
		String batchId,
		String serverKey,
		String serverLabel,
		String mode,
		String startedAt,
		String serverTimeAtStart,
		long durationMs,
		String status,
		int added,
		int updated,
		int deleted,
		long records,
		long bytes,
		int requests,
		int pages,
		Boolean historySupported,
		String error) {}

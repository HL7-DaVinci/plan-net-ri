package org.hl7.davinci.api.service;

/**
 * A crawl step emitted by the crawl engine. The orchestrator stamps batchId, runId,
 * serverKey, and sequence before persisting/broadcasting it.
 */
public record StepEvent(
		String phase,
		String message,
		String method,
		String url,
		Integer status,
		Long ms,
		Long bytes,
		Integer count,
		String errorBody,
		boolean progress) {

	/** Cap stored error bodies; a broken server can return arbitrarily large pages. */
	static final int MAX_ERROR_BODY_CHARS = 100_000;

	/** A narrative step with no HTTP details. */
	public static StepEvent info(String phase, String message) {
		return new StepEvent(phase, message, null, null, null, null, null, null, null, false);
	}

	/** A step describing one HTTP interaction. */
	public static StepEvent request(
			String phase,
			String message,
			String method,
			String url,
			Integer status,
			Long ms,
			Long bytes,
			Integer count) {
		return new StepEvent(phase, message, method, url, status, ms, bytes, count, null, false);
	}

	/** A failed interaction, retaining the raw (truncated) response body for diagnosis. */
	public static StepEvent failure(
			String phase, String message, String method, String url, Integer status, Long ms, String errorBody) {
		return new StepEvent(phase, message, method, url, status, ms, null, null, truncate(errorBody), false);
	}

	/** A transient in-progress marker: broadcast to live subscribers but never persisted. */
	public static StepEvent progress(String phase, String message) {
		return new StepEvent(phase, message, null, null, null, null, null, null, null, true);
	}

	private static String truncate(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		return body.length() <= MAX_ERROR_BODY_CHARS
				? body
				: body.substring(0, MAX_ERROR_BODY_CHARS) + "\n... [truncated]";
	}
}

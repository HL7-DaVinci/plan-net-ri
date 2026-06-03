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
		Integer count) {

	/** A narrative step with no HTTP details. */
	public static StepEvent info(String phase, String message) {
		return new StepEvent(phase, message, null, null, null, null, null, null);
	}

	/** A step describing one HTTP interaction. */
	public static StepEvent request(
			String phase, String message, String method, String url, Integer status, Long ms, Long bytes, Integer count) {
		return new StepEvent(phase, message, method, url, status, ms, bytes, count);
	}
}

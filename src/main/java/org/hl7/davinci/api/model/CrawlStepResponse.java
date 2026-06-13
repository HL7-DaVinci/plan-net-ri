package org.hl7.davinci.api.model;

/** A recorded crawl step as returned by the steps endpoint and the SSE stream. */
public record CrawlStepResponse(
		int seq,
		String phase,
		String message,
		String method,
		String url,
		Integer status,
		Long ms,
		Long bytes,
		Integer count,
		String errorBody,
		String serverKey,
		String at) {}

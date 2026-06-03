package org.hl7.davinci.api.service;

/**
 * A single resource fetched from a server during a crawl, normalized for diffing and
 * persistence. {@code key} is {@code serverKey|resourceType/id} (see the frontend
 * {@code resourceKey}). {@code json} is the encoded FHIR resource body.
 */
public record FetchedResource(
		String key,
		String resourceType,
		String id,
		String versionId,
		String lastUpdated,
		String json,
		long bytes) {}

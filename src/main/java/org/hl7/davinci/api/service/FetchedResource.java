package org.hl7.davinci.api.service;

/**
 * A single resource fetched from a server during a crawl, normalized for diffing and
 * persistence. {@code json} is the encoded FHIR resource body.
 */
public record FetchedResource(
		String resourceType, String id, String versionId, String lastUpdated, String json, long bytes) {}

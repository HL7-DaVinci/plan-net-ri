package org.hl7.davinci.api.model;

import java.util.List;

/** Aggregate crawl statistics across all jobs and servers. */
public record OverallStatsResponse(
		long totalResources, long serverCount, long jobCount, long manifestCount, List<TypeCount> byType) {

	/** A resource type and how many of it the database is tracking. */
	public record TypeCount(String type, long count) {}
}

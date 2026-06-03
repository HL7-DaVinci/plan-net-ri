package org.hl7.davinci.api.model;

/** Aggregate stats for a crawl job, across its completed runs and published manifests. */
public record JobStatsResponse(
		String jobId,
		int manifestCount,
		long totalBuildMs,
		long avgBuildMs,
		long lastBuildMs,
		int runCount,
		int completedRuns,
		int erroredRuns,
		long totalRecords,
		long totalBytes,
		String lastRunAt,
		long latestTotalResources) {}

package org.hl7.davinci.api.model;

import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.service.ServerScope;

import java.util.List;

/** A crawl job as returned by the API. */
public record JobResponse(
		String id,
		String name,
		List<ServerScope> servers,
		CrawlStrategy strategy,
		String cronExpression,
		boolean enabled,
		boolean running,
		String currentBatchId,
		String lastRunAt,
		String nextRunAt,
		String createdAt) {}

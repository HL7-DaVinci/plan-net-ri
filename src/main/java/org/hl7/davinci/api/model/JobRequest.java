package org.hl7.davinci.api.model;

import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.service.ServerScope;
import java.util.List;

/** Create/update payload for a crawl job. */
public record JobRequest(
		String name,
		List<ServerScope> servers,
		CrawlStrategy strategy,
		String cronExpression,
		Boolean enabled) {}

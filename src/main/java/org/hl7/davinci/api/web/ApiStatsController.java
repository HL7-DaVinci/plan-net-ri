package org.hl7.davinci.api.web;

import org.hl7.davinci.api.model.OverallStatsResponse;
import org.hl7.davinci.api.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Aggregate crawl statistics across all jobs (not job-scoped). */
@RestController
@RequestMapping("/api/stats")
public class ApiStatsController {

	private final StatsService stats;

	public ApiStatsController(StatsService stats) {
		this.stats = stats;
	}

	@GetMapping
	public OverallStatsResponse overall() {
		return stats.computeOverall();
	}
}

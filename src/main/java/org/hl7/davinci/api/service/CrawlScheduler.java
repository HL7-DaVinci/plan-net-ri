package org.hl7.davinci.api.service;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due crawl jobs and dispatches each via the async crawl trigger. */
@Component
public class CrawlScheduler {

	private static final Logger ourLog = LoggerFactory.getLogger(CrawlScheduler.class);

	private final CrawlJobRepository jobRepo;
	private final CrawlService crawlService;
	private final ApiProperties props;

	public CrawlScheduler(CrawlJobRepository jobRepo, CrawlService crawlService, ApiProperties props) {
		this.jobRepo = jobRepo;
		this.crawlService = crawlService;
		this.props = props;
	}

	@Scheduled(fixedDelayString = "${api.poller-interval-ms:30000}")
	public void poll() {
		if (!props.isEnabled()) {
			return;
		}
		Instant now = Instant.now();
		for (CrawlJob job : jobRepo.findByEnabledTrue()) {
			String cron = job.getCronExpression();
			if (cron == null || cron.isBlank()) {
				continue;
			}
			if (job.getNextRunAt() != null && job.getNextRunAt().isAfter(now)) {
				continue;
			}
			job.setNextRunAt(CronSupport.nextRun(cron));
			jobRepo.save(job);
			try {
				crawlService.triggerAsync(job);
			} catch (JobAlreadyRunningException e) {
				ourLog.info("Skipping job {}: already running", job.getId());
			}
		}
	}
}

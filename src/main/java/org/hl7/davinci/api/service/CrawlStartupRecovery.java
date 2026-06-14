package org.hl7.davinci.api.service;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Clears the stale {@code running} flag on jobs left mid-crawl by a crash so they can run again.
 * Gated by {@code api.resume-crawls-on-startup} (default false): the hosted deployment uses an
 * ephemeral database where nothing survives a restart, so it stays off there and only persistent
 * (dev/local) databases opt in.
 */
@Component
public class CrawlStartupRecovery {

	private static final Logger ourLog = LoggerFactory.getLogger(CrawlStartupRecovery.class);

	private final CrawlJobRepository jobRepo;
	private final CrawlService crawlService;
	private final ApiProperties props;

	public CrawlStartupRecovery(CrawlJobRepository jobRepo, CrawlService crawlService, ApiProperties props) {
		this.jobRepo = jobRepo;
		this.crawlService = crawlService;
		this.props = props;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void unblockStaleJobs() {
		if (!props.isResumeCrawlsOnStartup()) {
			return;
		}
		int cleared = 0;
		for (CrawlJob job : jobRepo.findByRunningTrue()) {
			// A job genuinely running in this process is in the in-flight guard; leave it alone and
			// clear only flags with no live worker (left over from a prior crash).
			if (crawlService.getActiveBatchId(job.getId()) != null) {
				continue;
			}
			job.setRunning(false);
			jobRepo.save(job);
			cleared++;
			ourLog.warn(
					"Job {} ({}) was left running by a prior shutdown; cleared the stale flag so it can run again",
					job.getId(),
					job.getName());
		}
		if (cleared > 0) {
			ourLog.info("Startup recovery unblocked {} stale crawl job(s)", cleared);
		}
	}
}

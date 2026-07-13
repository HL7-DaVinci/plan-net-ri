package org.hl7.davinci.api.service;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.CrawlStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Recovers jobs left mid-crawl by a shutdown or crash. Dangling RUNNING run rows are converted
 * to PAUSED segments (their elapsed time derived from the last persisted step), the stale
 * {@code running} flag is always cleared (otherwise the UI shows a phantom in-progress run
 * forever), and when {@code api.resume-crawls-on-startup} is enabled the interrupted job is
 * re-triggered. Re-triggering is a safe resume: aggregate state in {@code crawl_resource} and
 * the per-type checkpoints survived the restart, so the new run continues where the crawl left
 * off and absorbs the converted segment's elapsed time into its final numbers.
 */
@Component
public class CrawlStartupRecovery {

	private static final Logger ourLog = LoggerFactory.getLogger(CrawlStartupRecovery.class);

	private final CrawlJobRepository jobRepo;
	private final CrawlRunRepository runRepo;
	private final CrawlStepRepository stepRepo;
	private final CrawlService crawlService;
	private final ApiProperties props;

	public CrawlStartupRecovery(
			CrawlJobRepository jobRepo,
			CrawlRunRepository runRepo,
			CrawlStepRepository stepRepo,
			CrawlService crawlService,
			ApiProperties props) {
		this.jobRepo = jobRepo;
		this.runRepo = runRepo;
		this.stepRepo = stepRepo;
		this.crawlService = crawlService;
		this.props = props;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void unblockStaleJobs() {
		convertDanglingRuns();
		int cleared = 0;
		int resumed = 0;
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
					"Job {} ({}) was left running by a prior shutdown; cleared the stale flag",
					job.getId(),
					job.getName());
			if (props.isResumeCrawlsOnStartup()) {
				try {
					String batchId = crawlService.triggerAsync(job);
					resumed++;
					ourLog.info(
							"Resuming interrupted crawl for job {} ({}) as batch {}",
							job.getId(),
							job.getName(),
							batchId);
				} catch (Exception e) {
					ourLog.error("Failed to resume interrupted crawl for job {}: {}", job.getId(), e.getMessage(), e);
				}
			}
		}
		if (cleared > 0) {
			ourLog.info("Startup recovery unblocked {} stale crawl job(s), resumed {}", cleared, resumed);
		}
	}

	/**
	 * A kill or crash leaves the in-flight run's row in RUNNING state with no worker. Convert it
	 * to a PAUSED segment so the resumed run absorbs its elapsed time; the last persisted step
	 * marks how long the segment actually worked. Its counts stay at zero since per-run counts
	 * are only computed at the end of a segment.
	 */
	private void convertDanglingRuns() {
		for (CrawlRun run : runRepo.findByStatus(RunStatus.RUNNING)) {
			if (crawlService.getActiveBatchId(run.getJobId()) != null) {
				continue;
			}
			run.setStatus(RunStatus.PAUSED);
			run.setDurationMs(elapsedFromSteps(run));
			runRepo.save(run);
			ourLog.warn(
					"Run {} (job {}) was left mid-crawl by a prior shutdown; recorded as a paused segment of {} ms",
					run.getId(),
					run.getJobId(),
					run.getDurationMs());
		}
	}

	private long elapsedFromSteps(CrawlRun run) {
		if (run.getStartedAt() == null) {
			return 0;
		}
		return stepRepo.findTop1ByBatchIdOrderBySeqDesc(run.getBatchId())
				.map(step -> {
					Instant last = step.getAt();
					return last == null
							? 0L
							: Math.max(
									0,
									Duration.between(run.getStartedAt(), last).toMillis());
				})
				.orElse(0L);
	}
}

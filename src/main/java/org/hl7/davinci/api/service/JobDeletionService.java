package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.CrawlStepRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes a crawl job together with its run history and retained manifests. */
@Service
public class JobDeletionService {

	private final CrawlJobRepository jobRepo;
	private final CrawlRunRepository runRepo;
	private final CrawlStepRepository stepRepo;
	private final ManifestService manifestService;

	public JobDeletionService(
			CrawlJobRepository jobRepo,
			CrawlRunRepository runRepo,
			CrawlStepRepository stepRepo,
			ManifestService manifestService) {
		this.jobRepo = jobRepo;
		this.runRepo = runRepo;
		this.stepRepo = stepRepo;
		this.manifestService = manifestService;
	}

	/**
	 * Remove the job and everything that hangs off it: retained manifests (rows and on-disk
	 * snapshots), play-by-play steps, and run history. A no-op if the job is gone. The crawled
	 * resource store is server-scoped and left intact. Refuses while a crawl is in flight so the
	 * finishing run cannot resurrect the rows we just cleared.
	 */
	@Transactional("crawlerTransactionManager")
	public void deleteJob(String jobId) {
		CrawlJob job = jobRepo.findById(jobId).orElse(null);
		if (job == null) {
			return;
		}
		if (job.isRunning()) {
			throw new JobAlreadyRunningException(jobId);
		}
		manifestService.deleteManifestsForJob(jobId);
		List<String> batchIds = runRepo.findBatchIdsByJobId(jobId);
		if (!batchIds.isEmpty()) {
			stepRepo.deleteByBatchIdIn(batchIds);
		}
		runRepo.deleteByJobId(jobId);
		jobRepo.deleteById(jobId);
	}
}

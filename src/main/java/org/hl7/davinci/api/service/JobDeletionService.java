package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.repository.CrawlCheckpointRepository;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.CrawlStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/** Removes a crawl job together with its run history and retained manifests. */
@Service
public class JobDeletionService {

	private static final Logger ourLog = LoggerFactory.getLogger(JobDeletionService.class);

	private final CrawlJobRepository jobRepo;
	private final CrawlRunRepository runRepo;
	private final CrawlStepRepository stepRepo;
	private final CrawlResourceRepository resourceRepo;
	private final ServerRegistry serverRegistry;
	private final CrawlCheckpointRepository checkpointRepo;
	private final ManifestService manifestService;
	private final CrawlService crawlService;

	public JobDeletionService(
			CrawlJobRepository jobRepo,
			CrawlRunRepository runRepo,
			CrawlStepRepository stepRepo,
			CrawlResourceRepository resourceRepo,
			ServerRegistry serverRegistry,
			CrawlCheckpointRepository checkpointRepo,
			ManifestService manifestService,
			CrawlService crawlService) {
		this.jobRepo = jobRepo;
		this.runRepo = runRepo;
		this.stepRepo = stepRepo;
		this.resourceRepo = resourceRepo;
		this.serverRegistry = serverRegistry;
		this.checkpointRepo = checkpointRepo;
		this.manifestService = manifestService;
		this.crawlService = crawlService;
	}

	/**
	 * Remove the job and everything that hangs off it: retained manifests (rows and on-disk
	 * snapshots), play-by-play steps, and run history. The crawled resource store is server-scoped, so
	 * it is cleared only for servers no remaining job targets. A no-op if the job is gone. An in-flight
	 * crawl is cancelled first so the dying worker cannot resurrect the rows this clears.
	 */
	@Transactional("crawlerTransactionManager")
	public void deleteJob(String jobId) {
		CrawlJob job = jobRepo.findById(jobId).orElse(null);
		if (job == null) {
			return;
		}
		crawlService.cancelJob(jobId);
		Set<String> serversOfJob = crawlService.serverKeys(job);
		manifestService.deleteManifestsForJob(jobId);
		List<String> batchIds = runRepo.findBatchIdsByJobId(jobId);
		if (!batchIds.isEmpty()) {
			stepRepo.deleteByBatchIdIn(batchIds);
		}
		runRepo.deleteByJobId(jobId);
		checkpointRepo.deleteByJobId(jobId);
		jobRepo.deleteById(jobId);
		deleteOrphanedServerResources(jobId, serversOfJob);
	}

	/** Clear the crawled aggregate for any of the job's servers that no remaining job targets. */
	private void deleteOrphanedServerResources(String deletedJobId, Set<String> serversOfJob) {
		if (serversOfJob.isEmpty()) {
			return;
		}
		Set<String> stillReferenced = new HashSet<>();
		for (CrawlJob other : jobRepo.findAll()) {
			if (!other.getId().equals(deletedJobId)) {
				stillReferenced.addAll(crawlService.serverKeys(other));
			}
		}
		for (String serverKey : serversOfJob) {
			if (stillReferenced.contains(serverKey)) {
				continue;
			}
			OptionalInt serverId = serverRegistry.idIfExists(serverKey);
			if (serverId.isEmpty()) {
				continue;
			}
			int deleted = resourceRepo.deleteByServerId(serverId.getAsInt());
			serverRegistry.deleteServer(serverKey);
			ourLog.info("Cleared {} resources for server {}: no remaining job targets it", deleted, serverKey);
		}
	}
}

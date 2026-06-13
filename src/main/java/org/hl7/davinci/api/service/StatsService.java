package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.model.JobStatsResponse;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Aggregates per-job stats from crawl runs and published manifests. */
@Service
public class StatsService {

	private final ManifestRepository manifestRepo;
	private final CrawlRunRepository runRepo;

	public StatsService(ManifestRepository manifestRepo, CrawlRunRepository runRepo) {
		this.manifestRepo = manifestRepo;
		this.runRepo = runRepo;
	}

	public JobStatsResponse computeStats(String jobId) {
		List<ManifestRecord> manifests = manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc(jobId);
		List<CrawlRun> runs = runRepo.findByJobIdOrderByStartedAtDesc(jobId);

		int manifestCount = manifests.size();
		long totalBuildMs =
				manifests.stream().mapToLong(ManifestRecord::getBuildDurationMs).sum();
		long avgBuildMs = manifestCount > 0 ? totalBuildMs / manifestCount : 0;
		long lastBuildMs = manifestCount > 0 ? manifests.get(0).getBuildDurationMs() : 0;
		long latestTotalResources = manifestCount > 0 ? manifests.get(0).getTotalResources() : 0;

		int completedRuns = (int)
				runs.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED).count();
		int erroredRuns = (int)
				runs.stream().filter(r -> r.getStatus() == RunStatus.ERROR).count();
		long totalRecords = runs.stream().mapToLong(CrawlRun::getRecords).sum();
		long totalBytes = runs.stream().mapToLong(CrawlRun::getBytes).sum();
		String lastRunAt = runs.isEmpty() ? null : String.valueOf(runs.get(0).getStartedAt());

		return new JobStatsResponse(
				jobId,
				manifestCount,
				totalBuildMs,
				avgBuildMs,
				lastBuildMs,
				runs.size(),
				completedRuns,
				erroredRuns,
				totalRecords,
				totalBytes,
				lastRunAt,
				latestTotalResources);
	}
}

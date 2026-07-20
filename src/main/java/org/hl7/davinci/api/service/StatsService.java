package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.model.JobStatsResponse;
import org.hl7.davinci.api.model.OverallStatsResponse;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.hl7.davinci.common.PlanNetTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Aggregates per-job stats from crawl runs and published manifests. */
@Service
public class StatsService {

	private static final Logger ourLog = LoggerFactory.getLogger(StatsService.class);

	/** How long a computed overall-stats snapshot is served before a background refresh. */
	private static final long OVERALL_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

	private final ManifestRepository manifestRepo;
	private final CrawlRunRepository runRepo;
	private final CrawlResourceRepository resourceRepo;
	private final ServerRegistry serverRegistry;
	private final CrawlJobRepository jobRepo;

	private final AtomicReference<OverallStatsResponse> cachedOverall = new AtomicReference<>();
	private volatile long cachedOverallAtNanos;
	private final AtomicBoolean refreshing = new AtomicBoolean();

	public StatsService(
			ManifestRepository manifestRepo,
			CrawlRunRepository runRepo,
			CrawlResourceRepository resourceRepo,
			ServerRegistry serverRegistry,
			CrawlJobRepository jobRepo) {
		this.manifestRepo = manifestRepo;
		this.runRepo = runRepo;
		this.resourceRepo = resourceRepo;
		this.serverRegistry = serverRegistry;
		this.jobRepo = jobRepo;
	}

	public JobStatsResponse computeStats(String jobId) {
		List<ManifestRecord> manifests = manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc(jobId);
		List<CrawlRun> runs = runRepo.findByJobIdOrderByStartedAtDesc(jobId);
		// A paused segment's numbers are absorbed by the run that resumes it, so aggregating the
		// segment rows too would double count; RUNNING and PAUSED rows are progress markers,
		// not finished runs.
		List<CrawlRun> finished = runs.stream()
				.filter(r -> r.getStatus() != RunStatus.PAUSED && r.getStatus() != RunStatus.RUNNING)
				.toList();

		int manifestCount = manifests.size();
		long totalBuildMs =
				manifests.stream().mapToLong(ManifestRecord::getBuildDurationMs).sum();
		long avgBuildMs = manifestCount > 0 ? totalBuildMs / manifestCount : 0;
		long lastBuildMs = manifestCount > 0 ? manifests.get(0).getBuildDurationMs() : 0;
		long latestTotalResources = manifestCount > 0 ? manifests.get(0).getTotalResources() : 0;

		int completedRuns = (int) finished.stream()
				.filter(r -> r.getStatus() == RunStatus.COMPLETED)
				.count();
		int erroredRuns = (int)
				finished.stream().filter(r -> r.getStatus() == RunStatus.ERROR).count();
		long totalRecords = finished.stream().mapToLong(CrawlRun::getRecords).sum();
		long totalBytes = finished.stream().mapToLong(CrawlRun::getBytes).sum();
		String lastRunAt = runs.isEmpty() ? null : String.valueOf(runs.get(0).getStartedAt());

		return new JobStatsResponse(
				jobId,
				manifestCount,
				totalBuildMs,
				avgBuildMs,
				lastBuildMs,
				finished.size(),
				completedRuns,
				erroredRuns,
				totalRecords,
				totalBytes,
				lastRunAt,
				latestTotalResources);
	}

	/**
	 * Serves the cached snapshot when fresh; a stale snapshot is returned immediately while a
	 * background refresh recomputes it (stale-while-revalidate), so only the very first call on
	 * a cold server pays the count queries, and the startup warm-up usually covers even that.
	 */
	public OverallStatsResponse computeOverall() {
		OverallStatsResponse cached = cachedOverall.get();
		if (cached == null) {
			return refreshOverall();
		}
		if (System.nanoTime() - cachedOverallAtNanos > OVERALL_TTL_NANOS) {
			refreshOverallAsync();
		}
		return cached;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warmOverallStats() {
		refreshOverallAsync();
	}

	private void refreshOverallAsync() {
		if (!refreshing.compareAndSet(false, true)) {
			return;
		}
		Thread worker = new Thread(
				() -> {
					try {
						refreshOverall();
					} catch (Exception e) {
						ourLog.warn("Overall stats refresh failed: {}", e.getMessage(), e);
					} finally {
						refreshing.set(false);
					}
				},
				"stats-refresh");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Counts ride the composite primary key: every (server, type) pair is one index-only prefix
	 * count on (server_id, type_id, uid), and the server list comes from the jobs table
	 * ({@code crawl_resource} only ever holds servers some job targets), so no query full-scans
	 * the aggregate table.
	 */
	private OverallStatsResponse refreshOverall() {
		Set<String> servers = new TreeSet<>();
		for (CrawlJob job : jobRepo.findAll()) {
			try {
				for (ServerScope server : ServerScope.parseList(job.getServers())) {
					servers.add(CrawlService.normalizeServerKey(server.url()));
				}
			} catch (IllegalArgumentException e) {
				ourLog.warn("Skipping job {} with unparseable servers JSON in stats: {}", job.getId(), e.getMessage());
			}
		}
		long total = 0;
		long serversWithData = 0;
		Map<String, Long> byType = new TreeMap<>();
		for (String serverKey : servers) {
			OptionalInt serverId = serverRegistry.idIfExists(serverKey);
			if (serverId.isEmpty()) {
				continue;
			}
			long serverTotal = 0;
			for (String type : PlanNetTypes.TYPES) {
				long count = resourceRepo.countByIdServerIdAndIdTypeId(serverId.getAsInt(), PlanNetTypes.idOf(type));
				if (count > 0) {
					byType.merge(type, count, Long::sum);
					serverTotal += count;
				}
			}
			total += serverTotal;
			if (serverTotal > 0) {
				serversWithData++;
			}
		}
		List<OverallStatsResponse.TypeCount> typeCounts = new ArrayList<>();
		byType.forEach((type, count) -> typeCounts.add(new OverallStatsResponse.TypeCount(type, count)));
		OverallStatsResponse stats =
				new OverallStatsResponse(total, serversWithData, jobRepo.count(), manifestRepo.count(), typeCounts);
		cachedOverall.set(stats);
		cachedOverallAtNanos = System.nanoTime();
		return stats;
	}
}

package org.hl7.davinci.api.service;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlMode;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Orchestrates a crawl: fetch per server (outside any tx), persist, and record a run. */
@Service
public class CrawlService {

	private static final Logger ourLog = LoggerFactory.getLogger(CrawlService.class);

	private final FhirCrawlClient client;
	private final CrawlPersistenceService persistence;
	private final ManifestService manifestService;
	private final CrawlEventService events;
	private final CrawlJobRepository jobRepo;
	private final CrawlRunRepository runRepo;
	private final ObjectMapper objectMapper;
	private final ApiProperties props;

	/** Per-job single-flight guard (restores the browser's isCrawling invariant). */
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	/** Background workers for /run and scheduled crawls so callers return immediately. */
	private final ExecutorService executor;

	public CrawlService(
			FhirCrawlClient client,
			CrawlPersistenceService persistence,
			ManifestService manifestService,
			CrawlEventService events,
			CrawlJobRepository jobRepo,
			CrawlRunRepository runRepo,
			ObjectMapper objectMapper,
			ApiProperties props) {
		this.client = client;
		this.persistence = persistence;
		this.manifestService = manifestService;
		this.events = events;
		this.jobRepo = jobRepo;
		this.runRepo = runRepo;
		this.objectMapper = objectMapper;
		this.props = props;
		AtomicInteger counter = new AtomicInteger();
		this.executor = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "crawler-worker-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	/**
	 * Acquire the single-flight guard and run the crawl on a background worker, returning
	 * the batchId immediately. Throws {@link JobAlreadyRunningException} if already running.
	 */
	public String triggerAsync(CrawlJob job) {
		if (!inFlight.add(job.getId())) {
			throw new JobAlreadyRunningException(job.getId());
		}
		String batchId = UUID.randomUUID().toString();
		// Mark running synchronously so the flag is set before this call returns.
		markRunning(job.getId(), true);
		try {
			executor.submit(() -> {
				try {
					crawlJob(job, batchId);
				} catch (Exception e) {
					ourLog.error("Crawl failed for job {}: {}", job.getId(), e.getMessage(), e);
				} finally {
					markRunning(job.getId(), false);
					inFlight.remove(job.getId());
				}
			});
		} catch (RejectedExecutionException e) {
			// Submission failed (e.g. executor shutdown); release the guard the worker would have cleared.
			markRunning(job.getId(), false);
			inFlight.remove(job.getId());
			throw e;
		}
		return batchId;
	}

	private void markRunning(String jobId, boolean running) {
		jobRepo.findById(jobId).ifPresent(j -> {
			j.setRunning(running);
			if (running) {
				j.setLastRunAt(Instant.now());
			}
			jobRepo.save(j);
		});
	}

	/** Synchronous crawl with a generated batchId (used by tests). */
	public List<CrawlRun> crawlJob(CrawlJob job) {
		return crawlJob(job, UUID.randomUUID().toString());
	}

	/** Run the job against every server in its scope, then publish a manifest snapshot. */
	public List<CrawlRun> crawlJob(CrawlJob job, String batchId) {
		long operationStartNanos = System.nanoTime();
		events.start(batchId);
		AtomicInteger seq = new AtomicInteger();
		try {
			List<ServerScope> servers = parseServers(job.getServers());
			List<String> serverKeys = servers.stream().map(s -> normalizeServerKey(s.url())).toList();
			events.publish(batchId, null, null, seq.incrementAndGet(), StepEvent.info(
					"STARTING",
					"Crawl started: " + job.getStrategy() + " strategy across " + servers.size()
							+ " server(s)"));

			List<ServerCrawlOutcome> outcomes = new ArrayList<>();
			for (ServerScope server : servers) {
				outcomes.add(crawlServer(job, server, batchId, seq));
			}

			boolean allCompleted =
					!outcomes.isEmpty()
							&& outcomes.stream().allMatch(o -> o.run().getStatus() == RunStatus.COMPLETED);
			if (allCompleted) {
				String windowSince = outcomes.stream()
						.map(ServerCrawlOutcome::sinceUsed)
						.filter(Objects::nonNull)
						.min(Comparator.naturalOrder())
						.orElse(null);
				ManifestRecord manifest =
						manifestService.createManifest(job, batchId, windowSince, serverKeys, operationStartNanos);
				events.publish(batchId, null, null, seq.incrementAndGet(), StepEvent.info(
						"MANIFEST",
						"Published manifest: " + manifest.getTotalResources() + " resources, built in "
								+ manifest.getBuildDurationMs() + " ms"));
			}
			events.publish(batchId, null, null, seq.incrementAndGet(), StepEvent.info("DONE", "Crawl complete"));
			return outcomes.stream().map(ServerCrawlOutcome::run).toList();
		} finally {
			events.complete(batchId);
		}
	}

	private record ServerCrawlOutcome(CrawlRun run, String sinceUsed) {}

	private ServerCrawlOutcome crawlServer(CrawlJob job, ServerScope server, String batchId, AtomicInteger seq) {
		String serverKey = normalizeServerKey(server.url());
		String serverLabel = server.serverLabel() != null ? server.serverLabel() : serverKey;
		long startNanos = System.nanoTime();

		String since = incrementalSince(job, serverKey);
		CrawlMode mode = since != null ? CrawlMode.INCREMENTAL : CrawlMode.FULL;

		CrawlRun run = new CrawlRun();
		run.setId(UUID.randomUUID().toString());
		run.setJobId(job.getId());
		run.setBatchId(batchId);
		run.setServerKey(serverKey);
		run.setServerLabel(serverLabel);
		run.setMode(mode);
		run.setStartedAt(Instant.now());

		Consumer<StepEvent> sink =
				ev -> events.publish(batchId, run.getId(), serverKey, seq.incrementAndGet(), ev);

		try {
			switch (job.getStrategy()) {
				case SEARCH -> {
					if (mode == CrawlMode.INCREMENTAL) {
						crawlSearchIncremental(server, serverKey, serverLabel, since, run, sink);
					} else {
						crawlSearchFull(server, serverKey, serverLabel, run, sink);
					}
				}
				case BULK_EXPORT -> crawlBulkExport(server, serverKey, serverLabel, run, sink);
				case HISTORY -> crawlHistory(server, serverKey, serverLabel, since, run, sink);
			}
			run.setStatus(RunStatus.COMPLETED);
		} catch (Exception e) {
			ourLog.error("Crawl failed for job {} server {}: {}", job.getId(), serverKey, e.getMessage(), e);
			run.setStatus(RunStatus.ERROR);
			run.setError(e.getMessage());
			if (run.getServerTimeAtStart() == null) {
				run.setServerTimeAtStart(Instant.now().toString());
			}
			sink.accept(StepEvent.info("ERROR", "Crawl failed for " + serverLabel + ": " + e.getMessage()));
		}

		run.setDurationMs((System.nanoTime() - startNanos) / 1_000_000);
		runRepo.save(run);
		return new ServerCrawlOutcome(run, mode == CrawlMode.INCREMENTAL ? since : null);
	}

	/**
	 * SEARCH and HISTORY crawl incrementally (using the prior run's anchor as _since) once a
	 * completed run for this server exists. BULK_EXPORT always pulls a full snapshot.
	 */
	private String incrementalSince(CrawlJob job, String serverKey) {
		if (job.getStrategy() == CrawlStrategy.BULK_EXPORT) {
			return null;
		}
		return runRepo
				.findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc(job.getId(), serverKey, RunStatus.COMPLETED)
				.map(CrawlRun::getServerTimeAtStart)
				.orElse(null);
	}

	private void crawlSearchFull(
			ServerScope server, String serverKey, String serverLabel, CrawlRun run, Consumer<StepEvent> sink) {
		captureServerTime(server, run, sink);

		FhirCrawlClient.SearchResult result =
				client.searchTypes(server.url(), serverKey, props.getPageSize(), null, sink);

		CrawlPersistenceService.PersistCounts counts =
				persistence.persistFullSnapshot(serverKey, serverLabel, result.fetched());

		applyCounts(run, counts, result, 0, 0, 0);
		run.setHistorySupported(null);
		emitPersistStep(sink, counts);
	}

	private void crawlSearchIncremental(
			ServerScope server,
			String serverKey,
			String serverLabel,
			String since,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		captureServerTime(server, run, sink);

		FhirCrawlClient.SearchResult result =
				client.searchTypes(server.url(), serverKey, props.getPageSize(), since, sink);

		List<DeletionEntry> deletions = List.of();
		boolean historySupported = true;
		int delRequests = 0;
		int delPages = 0;
		long delBytes = 0;
		try {
			FhirCrawlClient.DeletionScanResult scan =
					client.scanDeletions(server.url(), since, props.getPageSize(), sink);
			deletions = scan.deletions();
			delRequests = scan.requests();
			delPages = scan.pages();
			delBytes = scan.bytes();
		} catch (HistoryUnsupportedException e) {
			historySupported = false;
			sink.accept(StepEvent.info("HISTORY", "Server does not support system _history; deletions not detected"));
		}

		CrawlPersistenceService.PersistCounts counts =
				persistence.persistIncremental(serverKey, serverLabel, result.fetched(), deletions);

		applyCounts(run, counts, result, delRequests, delPages, delBytes);
		run.setHistorySupported(historySupported);
		emitPersistStep(sink, counts);
	}

	private void crawlBulkExport(
			ServerScope server, String serverKey, String serverLabel, CrawlRun run, Consumer<StepEvent> sink) {
		captureServerTime(server, run, sink);

		FhirCrawlClient.SearchResult result = client.bulkExport(server.url(), serverKey, sink);
		CrawlPersistenceService.PersistCounts counts =
				persistence.persistFullSnapshot(serverKey, serverLabel, result.fetched());

		applyCounts(run, counts, result, 0, 0, 0);
		run.setHistorySupported(null);
		emitPersistStep(sink, counts);
	}

	private void crawlHistory(
			ServerScope server,
			String serverKey,
			String serverLabel,
			String since,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		captureServerTime(server, run, sink);

		FhirCrawlClient.HistoryResult result = client.historyExport(server.url(), serverKey, since, sink);
		CrawlPersistenceService.PersistCounts counts;
		if (since == null) {
			counts = persistence.persistFullSnapshot(serverKey, serverLabel, result.fetched());
			run.setHistorySupported(null);
		} else {
			counts = persistence.persistIncremental(serverKey, serverLabel, result.fetched(), result.deletions());
			run.setHistorySupported(true);
		}

		run.setAdded(counts.added());
		run.setUpdated(counts.updated());
		run.setDeleted(counts.deleted());
		run.setRecords(result.fetched().size());
		run.setBytes(result.bytes());
		run.setRequests(result.requests() + 1); // + the metadata call
		run.setPages(result.pages());
		emitPersistStep(sink, counts);
	}

	private FhirCrawlClient.ServerTime captureServerTime(ServerScope server, CrawlRun run, Consumer<StepEvent> sink) {
		FhirCrawlClient.ServerTime serverTime = client.getServerTime(server.url());
		run.setServerTimeAtStart(serverTime.iso());
		sink.accept(StepEvent.info(
				"SERVER_TIME",
				"Captured server-time anchor " + serverTime.iso() + " (source: " + serverTime.source() + ")"));
		return serverTime;
	}

	private void emitPersistStep(Consumer<StepEvent> sink, CrawlPersistenceService.PersistCounts counts) {
		sink.accept(StepEvent.info(
				"PERSIST",
				"Applied to aggregate: +" + counts.added() + " added, ~" + counts.updated() + " updated, -"
						+ counts.deleted() + " deleted"));
	}

	private void applyCounts(
			CrawlRun run,
			CrawlPersistenceService.PersistCounts counts,
			FhirCrawlClient.SearchResult result,
			int extraRequests,
			int extraPages,
			long extraBytes) {
		run.setAdded(counts.added());
		run.setUpdated(counts.updated());
		run.setDeleted(counts.deleted());
		run.setRecords(result.fetched().size());
		run.setBytes(result.bytes() + extraBytes);
		run.setRequests(result.requests() + extraRequests + 1); // + the metadata call
		run.setPages(result.pages() + extraPages);
	}

	private List<ServerScope> parseServers(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			ServerScope[] parsed = objectMapper.readValue(json, ServerScope[].class);
			return List.of(parsed);
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid crawl_job.servers JSON: " + e.getMessage(), e);
		}
	}

	static String normalizeServerKey(String url) {
		return url.trim().replaceAll("/+$", "");
	}
}

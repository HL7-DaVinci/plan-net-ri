package org.hl7.davinci.api.service;

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlMode;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

	/** Per-job single-flight guard (restores the browser's isCrawling invariant); maps jobId to its in-flight batchId. */
	private final Map<String, String> inFlight = new ConcurrentHashMap<>();

	/** The worker task per job, so a force delete can interrupt an in-flight run. */
	private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();

	/** Jobs whose in-flight run was cancelled; the worker suppresses all further writes. */
	private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

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
		String batchId = UUID.randomUUID().toString();
		if (inFlight.putIfAbsent(job.getId(), batchId) != null) {
			throw new JobAlreadyRunningException(job.getId());
		}
		// Mark running synchronously so the flag is set before this call returns.
		markRunning(job.getId(), true);
		FutureTask<Void> task = new FutureTask<>(
				() -> {
					try {
						crawlJob(job, batchId);
					} catch (Exception e) {
						ourLog.error("Crawl failed for job {}: {}", job.getId(), e.getMessage(), e);
					} finally {
						markRunning(job.getId(), false);
						inFlight.remove(job.getId());
						tasks.remove(job.getId());
						cancelled.remove(job.getId());
					}
				},
				null);
		// Registered before execution so cancelJob can always reach the worker.
		tasks.put(job.getId(), task);
		try {
			executor.execute(task);
		} catch (RejectedExecutionException e) {
			// Submission failed (e.g. executor shutdown); release the guard the worker would have cleared.
			markRunning(job.getId(), false);
			inFlight.remove(job.getId());
			tasks.remove(job.getId());
			throw e;
		}
		return batchId;
	}

	/**
	 * Cooperatively stop an in-flight run so its job can be force deleted: suppress further
	 * writes, interrupt the worker, and release the guard. A no-op when the job is idle.
	 */
	public void cancelJob(String jobId) {
		if (inFlight.get(jobId) == null) {
			return;
		}
		cancelled.add(jobId);
		Future<?> task = tasks.remove(jobId);
		if (task != null) {
			task.cancel(true);
		}
		markRunning(jobId, false);
		inFlight.remove(jobId);
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

	/** The batchId of the run currently in flight for this job, or null when idle. */
	public String getActiveBatchId(String jobId) {
		return inFlight.get(jobId);
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
			List<String> serverKeys =
					servers.stream().map(s -> normalizeServerKey(s.url())).toList();
			events.publish(
					batchId,
					null,
					null,
					seq.incrementAndGet(),
					StepEvent.info(
							"STARTING",
							"Crawl started: " + job.getStrategy() + " strategy across " + servers.size()
									+ " server(s)"));

			List<ServerCrawlOutcome> outcomes = new ArrayList<>();
			for (ServerScope server : servers) {
				if (cancelled.contains(job.getId())) {
					break;
				}
				outcomes.add(crawlServer(job, server, batchId, seq));
			}

			boolean allCompleted = !outcomes.isEmpty()
					&& !cancelled.contains(job.getId())
					&& outcomes.stream().allMatch(o -> o.run().getStatus() == RunStatus.COMPLETED);
			if (allCompleted) {
				String windowSince = outcomes.stream()
						.map(ServerCrawlOutcome::sinceUsed)
						.filter(Objects::nonNull)
						.min(Comparator.naturalOrder())
						.orElse(null);
				ManifestRecord manifest =
						manifestService.createManifest(job, batchId, windowSince, serverKeys, operationStartNanos);
				events.publish(
						batchId,
						null,
						null,
						seq.incrementAndGet(),
						StepEvent.info(
								"MANIFEST",
								"Published manifest: " + manifest.getTotalResources() + " resources, built in "
										+ manifest.getBuildDurationMs() + " ms"));
			}
			if (!cancelled.contains(job.getId())) {
				events.publish(batchId, null, null, seq.incrementAndGet(), StepEvent.info("DONE", "Crawl complete"));
			}
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

		// A cancelled run stops publishing so it cannot repopulate steps the delete just removed.
		// Transient progress markers do not consume a sequence number, keeping the persisted
		// timeline contiguous.
		Consumer<StepEvent> sink = ev -> {
			if (!cancelled.contains(job.getId())) {
				events.publish(batchId, run.getId(), serverKey, ev.progress() ? 0 : seq.incrementAndGet(), ev);
			}
		};

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
			String message = "Crawl failed for " + serverLabel + ": " + e.getMessage();
			// Server errors keep their status and raw response body so the UI can show them.
			if (e instanceof BaseServerResponseException serverError) {
				sink.accept(StepEvent.failure(
						"ERROR",
						message,
						null,
						null,
						serverError.getStatusCode(),
						null,
						serverError.getResponseBody()));
			} else {
				sink.accept(StepEvent.info("ERROR", message));
			}
		}

		run.setDurationMs((System.nanoTime() - startNanos) / 1_000_000);
		if (!cancelled.contains(job.getId())) {
			runRepo.save(run);
		}
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
		return runRepo.findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc(
						job.getId(), serverKey, RunStatus.COMPLETED)
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
		run.setTotalAfter(counts.total());
		run.setBytes(result.bytes());
		run.setRequests(result.requests() + 1); // + the metadata call
		run.setPages(result.pages());
		emitPersistStep(sink, counts);
	}

	private FhirCrawlClient.ServerTime captureServerTime(ServerScope server, CrawlRun run, Consumer<StepEvent> sink) {
		sink.accept(StepEvent.progress("SERVER_TIME", "Reading the server-time anchor..."));
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
		run.setTotalAfter(counts.total());
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

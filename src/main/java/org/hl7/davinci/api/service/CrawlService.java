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
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
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
		ourLog.info("Crawl {} queued for job {} ({})", batchId, job.getId(), job.getName());
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
		MDC.put("batchId", batchId);
		MDC.put("jobId", job.getId());
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

			ourLog.info("Crawl started: {} strategy across {} server(s)", job.getStrategy(), servers.size());

			List<ServerCrawlOutcome> outcomes = new ArrayList<>();
			for (ServerScope server : servers) {
				if (cancelled.contains(job.getId())) {
					break;
				}
				outcomes.add(crawlServer(job, server, batchId, seq));
			}
			MDC.remove("serverKey"); // job-level steps below are not server-scoped

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
				ourLog.info(
						"Published manifest: {} resources, built in {} ms",
						manifest.getTotalResources(),
						manifest.getBuildDurationMs());
			}
			if (!cancelled.contains(job.getId())) {
				events.publish(batchId, null, null, seq.incrementAndGet(), StepEvent.info("DONE", "Crawl complete"));
				ourLog.info("Crawl complete");
			}
			return outcomes.stream().map(ServerCrawlOutcome::run).toList();
		} finally {
			events.complete(batchId);
			MDC.remove("batchId");
			MDC.remove("jobId");
			MDC.remove("serverKey");
		}
	}

	private record ServerCrawlOutcome(CrawlRun run, String sinceUsed) {}

	private ServerCrawlOutcome crawlServer(CrawlJob job, ServerScope server, String batchId, AtomicInteger seq) {
		String serverKey = normalizeServerKey(server.url());
		String serverLabel = server.serverLabel() != null ? server.serverLabel() : serverKey;
		MDC.put("serverKey", serverKey);
		long startNanos = System.nanoTime();

		String since = incrementalSince(job, serverKey);
		CrawlMode mode = since != null ? CrawlMode.INCREMENTAL : CrawlMode.FULL;
		ourLog.info("Server {}: {} crawl starting", serverLabel, mode);

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
		// Parallel type/file tasks share this sink; seq assignment, the persisted-step save, and the
		// SSE broadcast must be one atomic unit or two workers can take adjacent sequence numbers but
		// save/broadcast out of order.
		Consumer<StepEvent> sink = ev -> {
			logStep(ev);
			if (!cancelled.contains(job.getId())) {
				synchronized (seq) {
					events.publish(batchId, run.getId(), serverKey, ev.progress() ? 0 : seq.incrementAndGet(), ev);
				}
			}
		};

		try {
			FhirCrawlClient.ServerTime anchor = captureServerTime(server, run, sink);

			CrawlStrategy strategy = job.getStrategy();
			boolean crawled = false;
			if (strategy == CrawlStrategy.AUTO) {
				// The $export kick-off is its own entry-phase probe: a 202 commits us to the
				// export already running server-side, so the bulk attempt IS the crawl.
				sink.accept(StepEvent.info("STRATEGY", "Auto strategy: attempting Bulk Data $export"));
				try {
					crawlBulkExport(server, serverKey, serverLabel, since, run, sink);
					strategy = CrawlStrategy.BULK_EXPORT;
					crawled = true;
				} catch (StrategyUnsupportedException e) {
					sink.accept(StepEvent.info("STRATEGY", e.getMessage() + "; probing search support"));
					strategy = resolveSearchStrategy(server, sink);
					sink.accept(StepEvent.info("STRATEGY", "Resolved strategy: " + strategy));
				}
			}
			run.setStrategy(strategy);

			if (!crawled) {
				switch (strategy) {
					case SEARCH -> crawlSearch(
							(u, k, ps, snc, anc, st, rs) -> client.searchTypes(u, k, ps, snc, st, rs),
							mode,
							server,
							serverKey,
							serverLabel,
							since,
							anchor,
							run,
							sink);
					case SEARCH_LAST_UPDATED -> crawlSearch(
							(u, k, ps, snc, anc, st, rs) -> client.searchTypesByLastUpdated(u, k, ps, snc, st, rs),
							mode,
							server,
							serverKey,
							serverLabel,
							since,
							anchor,
							run,
							sink);
					case SEARCH_LAST_UPDATED_PARTITIONED -> crawlSearch(
							client::searchTypesPartitioned,
							mode,
							server,
							serverKey,
							serverLabel,
							since,
							anchor,
							run,
							sink);
					case BULK_EXPORT -> crawlBulkExport(server, serverKey, serverLabel, since, run, sink);
					case HISTORY -> crawlHistory(server, serverKey, serverLabel, since, run, sink);
					case AUTO -> throw new IllegalStateException("AUTO must resolve to a concrete strategy");
				}
			}
			run.setStatus(RunStatus.COMPLETED);
		} catch (CancellationException e) {
			run.setStatus(RunStatus.ABORTED);
		} catch (Exception e) {
			ourLog.error("Crawl failed for job {} server {}: {}", job.getId(), serverKey, e.getMessage(), e);
			run.setStatus(RunStatus.ERROR);
			run.setError(StepEvent.clip(e.getMessage(), StepEvent.MAX_TEXT_CHARS));
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
		ourLog.info(
				"Server {}: {} in {} ms (+{} ~{} -{}, total {})",
				serverLabel,
				run.getStatus(),
				run.getDurationMs(),
				run.getAdded(),
				run.getUpdated(),
				run.getDeleted(),
				run.getTotalAfter());
		if (!cancelled.contains(job.getId())) {
			runRepo.save(run);
		}
		// run.getMode(), not the local mode: a bulk run whose _since was rejected flipped to FULL.
		return new ServerCrawlOutcome(run, run.getMode() == CrawlMode.INCREMENTAL ? since : null);
	}

	/** Mirror a step to the application log: failures at ERROR, transient progress at DEBUG, else INFO. */
	private static void logStep(StepEvent ev) {
		boolean isError = ev.errorBody() != null
				|| (ev.status() != null && ev.status() >= 400)
				|| "ERROR".equalsIgnoreCase(ev.phase())
				|| "FAILURE".equalsIgnoreCase(ev.phase());
		if (isError) {
			ourLog.error("[{}] {}", ev.phase(), ev.message());
			if (ev.errorBody() != null) {
				ourLog.debug("[{}] response body: {}", ev.phase(), ev.errorBody());
			}
		} else if (ev.progress()) {
			ourLog.debug("[{}] {}", ev.phase(), ev.message());
		} else {
			ourLog.info("[{}] {}", ev.phase(), ev.message());
		}
	}

	/**
	 * Every strategy crawls incrementally (using the prior run's anchor as _since) once a
	 * completed run for this server exists; BULK_EXPORT passes it as the kick-off's _since.
	 */
	private String incrementalSince(CrawlJob job, String serverKey) {
		return runRepo.findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc(
						job.getId(), serverKey, RunStatus.COMPLETED)
				.map(CrawlRun::getServerTimeAtStart)
				.orElse(null);
	}

	/**
	 * The search half of the AUTO pecking order, probed in decreasing preference. Falls back
	 * only on unsupported-class rejections; a transient probe failure propagates and fails
	 * the run. HISTORY is the unprobed last resort: if it fails too, the run fails.
	 */
	private CrawlStrategy resolveSearchStrategy(ServerScope server, Consumer<StepEvent> sink) {
		if (client.probePartitionedSearch(server.url(), sink)) {
			return CrawlStrategy.SEARCH_LAST_UPDATED_PARTITIONED;
		}
		sink.accept(StepEvent.info("STRATEGY", "_lastUpdated search rejected for every type; probing basic search"));
		if (client.probePlainSearch(server.url(), sink)) {
			return CrawlStrategy.SEARCH;
		}
		sink.accept(StepEvent.info("STRATEGY", "Search rejected for every type; falling back to history paging"));
		return CrawlStrategy.HISTORY;
	}

	/**
	 * Any search strategy's per-type fetch (link paging, a plain _lastUpdated watermark, or
	 * census-partitioned watermark windows). The anchor is only consumed by the partitioned
	 * strategy; the other two arms ignore it via a lambda adapter.
	 */
	@FunctionalInterface
	private interface TypeSearch {
		FhirCrawlClient.SearchResult run(
				String serverUrl,
				String serverKey,
				int pageSize,
				String since,
				FhirCrawlClient.ServerTime anchor,
				Consumer<StepEvent> steps,
				Consumer<List<FetchedResource>> resourceSink);
	}

	private void crawlSearch(
			TypeSearch search,
			CrawlMode mode,
			ServerScope server,
			String serverKey,
			String serverLabel,
			String since,
			FhirCrawlClient.ServerTime anchor,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		if (mode == CrawlMode.INCREMENTAL) {
			crawlSearchIncremental(search, server, serverKey, serverLabel, since, anchor, run, sink);
		} else {
			crawlSearchFull(search, server, serverKey, serverLabel, anchor, run, sink);
		}
	}

	private void crawlSearchFull(
			TypeSearch search,
			ServerScope server,
			String serverKey,
			String serverLabel,
			FhirCrawlClient.ServerTime anchor,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		CrawlPersistenceService.SnapshotSession session = persistence.openSession(serverKey, serverLabel);
		FhirCrawlClient.SearchResult result = search.run(
				server.url(), serverKey, props.getPageSize(), null, anchor, sink, resourceSink(run, session));
		throwIfCancelled(run);
		CrawlPersistenceService.PersistCounts counts = session.finishFullSnapshot();

		applyCounts(run, counts, result, 0, 0, 0);
		run.setHistorySupported(null);
		emitPersistStep(sink, counts);
	}

	private void crawlSearchIncremental(
			TypeSearch search,
			ServerScope server,
			String serverKey,
			String serverLabel,
			String since,
			FhirCrawlClient.ServerTime anchor,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		CrawlPersistenceService.SnapshotSession session = persistence.openSession(serverKey, serverLabel);
		FhirCrawlClient.SearchResult result = search.run(
				server.url(), serverKey, props.getPageSize(), since, anchor, sink, resourceSink(run, session));
		throwIfCancelled(run);

		DeletionScan scan = scanIncrementalDeletions(server, since, sink);
		throwIfCancelled(run);

		CrawlPersistenceService.PersistCounts counts = session.finishIncremental(scan.deletions());

		applyCounts(run, counts, result, scan.requests(), scan.pages(), scan.bytes());
		run.setHistorySupported(scan.historySupported());
		emitPersistStep(sink, counts);
	}

	/** An incremental run's _history deletion scan, degrading gracefully when unsupported. */
	private record DeletionScan(
			List<DeletionEntry> deletions, boolean historySupported, int requests, int pages, long bytes) {}

	private DeletionScan scanIncrementalDeletions(ServerScope server, String since, Consumer<StepEvent> sink) {
		try {
			FhirCrawlClient.DeletionScanResult scan =
					client.scanDeletions(server.url(), since, props.getPageSize(), sink);
			return new DeletionScan(scan.deletions(), true, scan.requests(), scan.pages(), scan.bytes());
		} catch (HistoryUnsupportedException e) {
			sink.accept(StepEvent.info("HISTORY", "Server does not support system _history; deletions not detected"));
			return new DeletionScan(List.of(), false, 0, 0, 0);
		}
	}

	private void crawlBulkExport(
			ServerScope server,
			String serverKey,
			String serverLabel,
			String since,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		if (since != null) {
			try {
				crawlBulkExportIncremental(server, serverKey, serverLabel, since, run, sink);
				return;
			} catch (SinceUnsupportedException e) {
				sink.accept(
						StepEvent.info("EXPORT", "Server rejected the _since parameter; retrying as a full export"));
				run.setMode(CrawlMode.FULL);
			}
		}
		CrawlPersistenceService.SnapshotSession session = persistence.openSession(serverKey, serverLabel);
		FhirCrawlClient.SearchResult result =
				client.bulkExport(server.url(), serverKey, null, sink, resourceSink(run, session));
		throwIfCancelled(run);
		CrawlPersistenceService.PersistCounts counts = session.finishFullSnapshot();

		applyCounts(run, counts, result, 0, 0, 0);
		run.setHistorySupported(null);
		emitPersistStep(sink, counts);
	}

	/**
	 * An incremental export conveys no deletions by absence (only what changed is present),
	 * so deletions come from the same _history scan the incremental search strategies use.
	 */
	private void crawlBulkExportIncremental(
			ServerScope server,
			String serverKey,
			String serverLabel,
			String since,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		CrawlPersistenceService.SnapshotSession session = persistence.openSession(serverKey, serverLabel);
		FhirCrawlClient.SearchResult result =
				client.bulkExport(server.url(), serverKey, since, sink, resourceSink(run, session));
		throwIfCancelled(run);

		DeletionScan scan = scanIncrementalDeletions(server, since, sink);
		throwIfCancelled(run);

		CrawlPersistenceService.PersistCounts counts = session.finishIncremental(scan.deletions());

		applyCounts(run, counts, result, scan.requests(), scan.pages(), scan.bytes());
		run.setHistorySupported(scan.historySupported());
		emitPersistStep(sink, counts);
	}

	private void crawlHistory(
			ServerScope server,
			String serverKey,
			String serverLabel,
			String since,
			CrawlRun run,
			Consumer<StepEvent> sink) {
		CrawlPersistenceService.SnapshotSession session = persistence.openSession(serverKey, serverLabel);
		FhirCrawlClient.HistoryResult result =
				client.historyExport(server.url(), serverKey, since, sink, resourceSink(run, session));
		throwIfCancelled(run);
		CrawlPersistenceService.PersistCounts counts;
		if (since == null) {
			counts = session.finishFullSnapshot();
			run.setHistorySupported(null);
		} else {
			counts = session.finishIncremental(result.deletions());
			run.setHistorySupported(true);
		}

		run.setAdded(counts.added());
		run.setUpdated(counts.updated());
		run.setDeleted(counts.deleted());
		run.setRecords(result.records());
		run.setTotalAfter(counts.total());
		run.setBytes(result.bytes());
		run.setRequests(result.requests() + 1); // + the metadata call
		run.setPages(result.pages());
		emitPersistStep(sink, counts);
	}

	private Consumer<List<FetchedResource>> resourceSink(
			CrawlRun run, CrawlPersistenceService.SnapshotSession session) {
		return batch -> {
			// The cancellation check and the write must be atomic, or a worker can pass the check,
			// block on the session monitor, and persist after a force-delete's row cleanup. accept()
			// is synchronized on the same session instance, so this lock is reentrant.
			synchronized (session) {
				throwIfCancelled(run);
				session.accept(batch);
			}
		};
	}

	private void throwIfCancelled(CrawlRun run) {
		if (cancelled.contains(run.getJobId())) {
			throw new CancellationException();
		}
	}

	/** Captures the server-time anchor onto the run and returns it for strategies that need it (e.g. partitioning). */
	private FhirCrawlClient.ServerTime captureServerTime(ServerScope server, CrawlRun run, Consumer<StepEvent> sink) {
		sink.accept(StepEvent.progress("SERVER_TIME", "Reading the server-time anchor..."));
		FhirCrawlClient.ServerTime serverTime = client.getServerTime(server.url(), sink);
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
		run.setRecords(result.records());
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

	/** The normalized server keys this job targets (the {@code serverKey} prefix used in crawl_resource). */
	public Set<String> serverKeys(CrawlJob job) {
		Set<String> keys = new HashSet<>();
		for (ServerScope server : parseServers(job.getServers())) {
			keys.add(normalizeServerKey(server.url()));
		}
		return keys;
	}
}

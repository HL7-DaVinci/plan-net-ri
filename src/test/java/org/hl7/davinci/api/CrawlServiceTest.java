package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlCheckpoint;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.repository.CrawlCheckpointRepository;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.service.CrawlEventService;
import org.hl7.davinci.api.service.CrawlPersistenceService;
import org.hl7.davinci.api.service.CrawlService;
import org.hl7.davinci.api.service.JobAlreadyRunningException;
import org.hl7.davinci.api.service.FetchedResource;
import org.hl7.davinci.api.service.FhirCrawlClient;
import org.hl7.davinci.api.service.ManifestService;
import org.hl7.davinci.api.service.ServerRegistry;
import org.hl7.davinci.api.service.SinceUnsupportedException;
import org.hl7.davinci.api.service.StepEvent;
import org.hl7.davinci.api.service.StrategyUnsupportedException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrawlServiceTest {

	@Test
	void doesNotPublishManifestWhenAnyConfiguredServerFails() {
		ApiProperties props = new ApiProperties();
		String good = "http://good.example/fhir";
		String bad = "http://bad.example/fhir";
		FetchedResource fetched = new FetchedResource(
				"Organization",
				"a",
				"1",
				"2026-01-01T00:00:00Z",
				"{\"resourceType\":\"Organization\",\"id\":\"a\"}",
				40);
		RecordingManifestService manifestService = new RecordingManifestService();
		CrawlService service = new CrawlService(
				new FakeFhirCrawlClient(good, bad, fetched),
				new FakePersistence(),
				manifestService,
				new NoopEvents(),
				jobRepo(),
				runRepo(),
				checkpointRepo(),
				new ObjectMapper(),
				props);
		CrawlJob job = new CrawlJob();
		job.setId("partial-job");
		job.setName("Partial job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"good\",\"url\":\""
				+ good
				+ "\"},{\"serverLabel\":\"bad\",\"url\":\""
				+ bad
				+ "\"}]");

		List<CrawlRun> runs = service.crawlJob(job, "batch-partial");

		assertEquals(2, runs.size());
		assertEquals(RunStatus.COMPLETED, runs.get(0).getStatus());
		assertEquals(RunStatus.ERROR, runs.get(1).getStatus());
		assertEquals(0, manifestService.createCalls);
	}

	@Test
	void serverErrorsRetainTheResponseBodyOnTheErrorStep() {
		String body = "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}";
		InternalErrorException failure = new InternalErrorException("HTTP 500 Internal Server Error");
		failure.setResponseBody(body);
		RecordingEvents events = new RecordingEvents();
		CrawlService service = new CrawlService(
				new ThrowingFhirCrawlClient(failure),
				new FakePersistence(),
				new RecordingManifestService(),
				events,
				jobRepo(),
				runRepo(),
				checkpointRepo(),
				new ObjectMapper(),
				new ApiProperties());
		CrawlJob job = new CrawlJob();
		job.setId("err-job");
		job.setName("Erroring job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"bad\",\"url\":\"http://bad.example/fhir\"}]");

		List<CrawlRun> runs = service.crawlJob(job, "batch-err");

		assertEquals(RunStatus.ERROR, runs.get(0).getStatus());
		StepEvent errorStep = events.published.stream()
				.filter(step -> "ERROR".equals(step.phase()))
				.findFirst()
				.orElseThrow();
		assertEquals(Integer.valueOf(500), errorStep.status(), "the failing status should be on the step");
		assertEquals(body, errorStep.errorBody(), "the raw response body should be on the step");

		List<Integer> contiguous = new ArrayList<>();
		for (int i = 1; i <= events.persistedSeqs.size(); i++) {
			contiguous.add(i);
		}
		assertEquals(
				contiguous,
				events.persistedSeqs,
				"transient progress markers must not consume persisted sequence numbers");
	}

	@Test
	void cancelJobStopsTheRunAndSuppressesItsWrites() throws Exception {
		String server = "http://good.example/fhir";
		FetchedResource fetched = new FetchedResource(
				"Organization",
				"a",
				"1",
				"2026-01-01T00:00:00Z",
				"{\"resourceType\":\"Organization\",\"id\":\"a\"}",
				40);
		CountDownLatch release = new CountDownLatch(1);
		BlockingFhirCrawlClient client = new BlockingFhirCrawlClient(fetched, release);
		List<CrawlRun> savedRuns = new ArrayList<>();
		RecordingManifestService manifestService = new RecordingManifestService();
		CompletionAwareEvents events = new CompletionAwareEvents();
		CrawlService service = new CrawlService(
				client,
				new FakePersistence(),
				manifestService,
				events,
				idleJobRepo(),
				recordingRunRepo(savedRuns),
				checkpointRepo(),
				new ObjectMapper(),
				new ApiProperties());
		CrawlJob job = new CrawlJob();
		job.setId("cancel-job");
		job.setName("Cancelled job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"good\",\"url\":\"" + server + "\"}]");

		service.triggerAsync(job);
		assertNotNull(service.getActiveBatchId("cancel-job"));
		assertTrue(client.entered.await(5, TimeUnit.SECONDS), "the worker should start crawling");

		service.cancelJob("cancel-job");

		assertNull(service.getActiveBatchId("cancel-job"), "cancel must release the guard immediately");
		assertTrue(events.completed.await(5, TimeUnit.SECONDS), "the interrupted worker should finish promptly");
		assertEquals(
				1,
				savedRuns.size(),
				"only the start-of-run RUNNING row is written; the cancelled terminal save is suppressed");
		assertEquals(0, manifestService.createCalls, "a cancelled run must not publish a manifest");
	}

	@Test
	void cancelJobStopsStreamedResourceBatches() throws Exception {
		String server = "http://good.example/fhir";
		FetchedResource first = new FetchedResource("Organization", "a", "1", null, "{}", 2);
		FetchedResource second = new FetchedResource("Organization", "b", "1", null, "{}", 2);
		EmittingAfterCancelFhirCrawlClient client = new EmittingAfterCancelFhirCrawlClient(first, second);
		RecordingPersistence persistence = new RecordingPersistence();
		CompletionAwareEvents events = new CompletionAwareEvents();
		CrawlService service = new CrawlService(
				client,
				persistence,
				new RecordingManifestService(),
				events,
				idleJobRepo(),
				runRepo(),
				checkpointRepo(),
				new ObjectMapper(),
				new ApiProperties());
		CrawlJob job = new CrawlJob();
		job.setId("cancel-stream-job");
		job.setName("Cancelled stream job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"good\",\"url\":\"" + server + "\"}]");

		service.triggerAsync(job);
		assertTrue(client.firstEmitted.await(5, TimeUnit.SECONDS), "the first streamed batch should be persisted");

		service.cancelJob("cancel-stream-job");
		client.releaseSecond.countDown();

		assertTrue(client.secondAttempted.await(5, TimeUnit.SECONDS), "the client should try to emit after cancel");
		assertTrue(events.completed.await(5, TimeUnit.SECONDS), "the cancelled worker should finish");
		assertEquals(1, persistence.accepted.get(), "post-cancel streamed batches must not be persisted");
	}

	@Test
	void serverShutdownRecordsInFlightRunsAsPausedAndKeepsTheRunningFlagForStartupResume() throws Exception {
		String server = "http://good.example/fhir";
		FetchedResource fetched = new FetchedResource(
				"Organization",
				"a",
				"1",
				"2026-01-01T00:00:00Z",
				"{\"resourceType\":\"Organization\",\"id\":\"a\"}",
				40);
		CountDownLatch release = new CountDownLatch(1);
		BlockingFhirCrawlClient client = new BlockingFhirCrawlClient(fetched, release);
		List<CrawlRun> savedRuns = new ArrayList<>();
		List<String> checkpointClears = new ArrayList<>();
		CrawlCheckpointRepository checkpoints = (CrawlCheckpointRepository) Proxy.newProxyInstance(
				CrawlCheckpointRepository.class.getClassLoader(),
				new Class<?>[] {CrawlCheckpointRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByJobIdAndServerKey" -> List.of();
					case "save" -> null;
					case "deleteByJobIdAndServerKey" -> {
						checkpointClears.add((String) args[0]);
						yield null;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
		RecordingManifestService manifestService = new RecordingManifestService();
		CompletionAwareEvents events = new CompletionAwareEvents();
		CrawlJob job = new CrawlJob();
		job.setId("shutdown-job");
		job.setName("Shutdown job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"good\",\"url\":\"" + server + "\"}]");
		CrawlJobRepository jobRepo = (CrawlJobRepository) Proxy.newProxyInstance(
				CrawlJobRepository.class.getClassLoader(),
				new Class<?>[] {CrawlJobRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findById" -> Optional.of(job);
					case "save" -> args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
		CrawlService service = new CrawlService(
				client,
				new FakePersistence(),
				manifestService,
				events,
				jobRepo,
				recordingRunRepo(savedRuns),
				checkpoints,
				new ObjectMapper(),
				new ApiProperties());

		service.triggerAsync(job);
		assertTrue(client.entered.await(5, TimeUnit.SECONDS), "the worker should start crawling");
		assertTrue(job.isRunning(), "the running flag is set while the crawl is in flight");

		service.shutdown();

		assertTrue(events.completed.await(5, TimeUnit.SECONDS), "the interrupted worker should finish promptly");
		assertEquals(RunStatus.PAUSED, savedRuns.get(0).getStatus(), "a shutdown-interrupted run records as paused");
		assertTrue(job.isRunning(), "the running flag stays set so startup recovery re-triggers the job");
		assertEquals(List.of(), checkpointClears, "shutdown must keep the checkpoints for the resumed run");
		assertEquals(0, manifestService.createCalls, "an interrupted run must not publish a manifest");
	}

	@Test
	void pauseJobRecordsAPausedRunAndKeepsItsCheckpoints() throws Exception {
		String server = "http://good.example/fhir";
		FetchedResource fetched = new FetchedResource(
				"Organization",
				"a",
				"1",
				"2026-01-01T00:00:00Z",
				"{\"resourceType\":\"Organization\",\"id\":\"a\"}",
				40);
		CountDownLatch release = new CountDownLatch(1);
		BlockingFhirCrawlClient client = new BlockingFhirCrawlClient(fetched, release);
		List<CrawlRun> savedRuns = new ArrayList<>();
		List<String> checkpointClears = new ArrayList<>();
		CrawlCheckpointRepository checkpoints = (CrawlCheckpointRepository) Proxy.newProxyInstance(
				CrawlCheckpointRepository.class.getClassLoader(),
				new Class<?>[] {CrawlCheckpointRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByJobIdAndServerKey" -> List.of();
					case "save" -> null;
					case "deleteByJobIdAndServerKey" -> {
						checkpointClears.add((String) args[0]);
						yield null;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
		RecordingManifestService manifestService = new RecordingManifestService();
		CompletionAwareEvents events = new CompletionAwareEvents();
		CrawlService service = new CrawlService(
				client,
				new FakePersistence(),
				manifestService,
				events,
				idleJobRepo(),
				recordingRunRepo(savedRuns),
				checkpoints,
				new ObjectMapper(),
				new ApiProperties());
		CrawlJob job = new CrawlJob();
		job.setId("pause-job");
		job.setName("Paused job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"good\",\"url\":\"" + server + "\"}]");

		service.triggerAsync(job);
		assertTrue(client.entered.await(5, TimeUnit.SECONDS), "the worker should start crawling");

		assertTrue(service.pauseJob("pause-job"), "an in-flight run can be paused");

		assertTrue(events.completed.await(5, TimeUnit.SECONDS), "the paused worker should finish promptly");
		assertEquals(2, savedRuns.size(), "the start-of-run row plus the paused terminal row are recorded");
		assertEquals(RunStatus.PAUSED, savedRuns.get(0).getStatus());
		assertEquals(0, manifestService.createCalls, "a paused run must not publish a manifest");
		assertEquals(List.of(), checkpointClears, "pause must keep the checkpoints for resume");
		assertFalse(service.pauseJob("pause-job"), "an idle job has nothing to pause");
	}

	@Test
	void aResumedRunAbsorbsThePausedSegmentsDurationAndCounts() {
		CrawlRun segment = new CrawlRun();
		segment.setStatus(RunStatus.PAUSED);
		segment.setDurationMs(60_000);
		segment.setAdded(5);
		segment.setUpdated(2);
		segment.setDeleted(1);
		segment.setRecords(700);
		segment.setBytes(12_345);
		segment.setRequests(9);
		segment.setPages(8);
		List<CrawlRun> savedRuns = new ArrayList<>();
		CrawlRunRepository runRepo = (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc" -> Optional.empty();
					case "findTop1ByJobIdAndServerKeyOrderByStartedAtDesc" -> Optional.of(segment);
					case "save" -> {
						savedRuns.add((CrawlRun) args[0]);
						yield args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			public ServerTime getServerTime(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
				return new ServerTime("2026-07-01T00:00:00Z", "date-header");
			}

			@Override
			public SearchResult searchTypesByLastUpdated(
					String serverUrl,
					String serverKey,
					int pageSize,
					String since,
					ResumeContext resume,
					java.util.function.Consumer<StepEvent> steps,
					java.util.function.Consumer<List<FetchedResource>> resourceSink) {
				return new SearchResult(1, 40, 1, 1);
			}
		};
		RecordingManifestService manifestService = new RecordingManifestService();
		CrawlService service = new CrawlService(
				client,
				new FakePersistence(),
				manifestService,
				new NoopEvents(),
				jobRepo(),
				runRepo,
				checkpointRepo(),
				new ObjectMapper(),
				new ApiProperties());

		service.crawlJob(job("resume-stats", CrawlStrategy.SEARCH_LAST_UPDATED), "batch-resume-stats");

		assertEquals(
				60_000,
				manifestService.lastCarryoverMs,
				"the manifest's build time must absorb the paused segment too");
		CrawlRun run = savedRuns.get(0);
		assertEquals(RunStatus.COMPLETED, run.getStatus());
		assertTrue(run.getDurationMs() >= 60_000, "the paused segment's time counts toward the finished run");
		assertEquals(6, run.getAdded(), "5 carried + 1 from this segment");
		assertEquals(2, run.getUpdated());
		assertEquals(1, run.getDeleted());
		assertEquals(701, run.getRecords());
		assertEquals(12_385, run.getBytes());
		assertEquals(11, run.getRequests(), "9 carried + 1 search + 1 metadata");
		assertEquals(9, run.getPages());
	}

	@Test
	void triggerAsyncExposesTheActiveBatchUntilTheRunCompletes() throws Exception {
		String server = "http://good.example/fhir";
		FetchedResource fetched = new FetchedResource(
				"Organization",
				"a",
				"1",
				"2026-01-01T00:00:00Z",
				"{\"resourceType\":\"Organization\",\"id\":\"a\"}",
				40);
		CountDownLatch release = new CountDownLatch(1);
		CrawlService service = new CrawlService(
				new BlockingFhirCrawlClient(fetched, release),
				new FakePersistence(),
				new RecordingManifestService(),
				new NoopEvents(),
				idleJobRepo(),
				runRepo(),
				checkpointRepo(),
				new ObjectMapper(),
				new ApiProperties());
		CrawlJob job = new CrawlJob();
		job.setId("live-job");
		job.setName("Live job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"good\",\"url\":\"" + server + "\"}]");

		String batchId = service.triggerAsync(job);

		assertEquals(batchId, service.getActiveBatchId("live-job"), "the in-flight batch should be discoverable");
		assertThrows(JobAlreadyRunningException.class, () -> service.triggerAsync(job));

		release.countDown();
		long deadline = System.currentTimeMillis() + 5_000;
		while (service.getActiveBatchId("live-job") != null && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertNull(service.getActiveBatchId("live-job"), "the guard should clear when the run completes");
	}

	@Test
	void autoResolvesToBulkExportWhenTheKickoffSucceeds() {
		AutoFhirCrawlClient client = new AutoFhirCrawlClient();
		List<CrawlRun> savedRuns = new ArrayList<>();
		CrawlService service = service(client, new FakePersistence(), recordingRunRepo(savedRuns));

		service.crawlJob(job("auto-bulk", CrawlStrategy.AUTO), "batch-auto-bulk");

		assertEquals(RunStatus.COMPLETED, savedRuns.get(0).getStatus());
		assertEquals(CrawlStrategy.BULK_EXPORT, savedRuns.get(0).getStrategy());
		assertEquals(0, client.partitionedProbes, "a committed export needs no search probing");
	}

	@Test
	void autoFallsBackToPartitionedSearchWhenExportIsUnsupported() {
		AutoFhirCrawlClient client = new AutoFhirCrawlClient();
		client.bulkSupported = false;
		List<CrawlRun> savedRuns = new ArrayList<>();
		CrawlService service = service(client, new FakePersistence(), recordingRunRepo(savedRuns));

		service.crawlJob(job("auto-part", CrawlStrategy.AUTO), "batch-auto-part");

		assertEquals(RunStatus.COMPLETED, savedRuns.get(0).getStatus());
		assertEquals(CrawlStrategy.SEARCH_LAST_UPDATED_PARTITIONED, savedRuns.get(0).getStrategy());
		assertEquals("partitioned", client.dispatched);
		assertEquals(0, client.plainProbes, "a viable partitioned probe should skip the basic-search probe");
	}

	@Test
	void autoFallsBackToHistoryWhenNoSearchProbeSucceeds() {
		AutoFhirCrawlClient client = new AutoFhirCrawlClient();
		client.bulkSupported = false;
		client.partitionedViable = false;
		client.plainViable = false;
		List<CrawlRun> savedRuns = new ArrayList<>();
		CrawlService service = service(client, new FakePersistence(), recordingRunRepo(savedRuns));

		service.crawlJob(job("auto-hist", CrawlStrategy.AUTO), "batch-auto-hist");

		assertEquals(RunStatus.COMPLETED, savedRuns.get(0).getStatus());
		assertEquals(CrawlStrategy.HISTORY, savedRuns.get(0).getStrategy());
		assertEquals("history", client.dispatched);
	}

	@Test
	void autoDoesNotFallBackOnATransientBulkFailure() {
		AutoFhirCrawlClient client = new AutoFhirCrawlClient() {
			@Override
			public SearchResult bulkExport(
					String serverUrl,
					String serverKey,
					String since,
					java.util.function.Consumer<StepEvent> steps,
					java.util.function.Consumer<List<FetchedResource>> resourceSink) {
				throw new IllegalStateException("Expected 202 from $export, got 503");
			}
		};
		List<CrawlRun> savedRuns = new ArrayList<>();
		CrawlService service = service(client, new FakePersistence(), recordingRunRepo(savedRuns));

		service.crawlJob(job("auto-503", CrawlStrategy.AUTO), "batch-auto-503");

		assertEquals(RunStatus.ERROR, savedRuns.get(0).getStatus());
		assertEquals(0, client.partitionedProbes, "a transient failure must not demote the server to search");
	}

	@Test
	void bulkExportCrawlsIncrementallyWithSinceAndTheHistoryScan() {
		AutoFhirCrawlClient client = new AutoFhirCrawlClient();
		FinishRecordingPersistence persistence = new FinishRecordingPersistence();
		List<CrawlRun> savedRuns = new ArrayList<>();
		CrawlService service =
				service(client, persistence, runRepoWithPriorRun(savedRuns, "2026-06-01T00:00:00Z"));

		service.crawlJob(job("bulk-inc", CrawlStrategy.BULK_EXPORT), "batch-bulk-inc");

		assertEquals(RunStatus.COMPLETED, savedRuns.get(0).getStatus());
		assertEquals("2026-06-01T00:00:00Z", client.bulkSinceSeen, "the kick-off should receive the prior anchor");
		assertEquals(List.of("incremental"), persistence.finishes);
		assertEquals(Boolean.TRUE, savedRuns.get(0).getHistorySupported());
		assertEquals("INCREMENTAL", savedRuns.get(0).getMode().name());
	}

	@Test
	void bulkExportRetriesAsAFullExportWhenSinceIsRejected() {
		AutoFhirCrawlClient client = new AutoFhirCrawlClient();
		client.sinceSupported = false;
		FinishRecordingPersistence persistence = new FinishRecordingPersistence();
		List<CrawlRun> savedRuns = new ArrayList<>();
		CrawlService service =
				service(client, persistence, runRepoWithPriorRun(savedRuns, "2026-06-01T00:00:00Z"));

		service.crawlJob(job("bulk-since-rej", CrawlStrategy.BULK_EXPORT), "batch-bulk-since-rej");

		assertEquals(RunStatus.COMPLETED, savedRuns.get(0).getStatus());
		assertEquals(2, client.bulkCalls, "the rejected _since kick-off should be retried bare");
		assertNull(client.bulkSinceSeen, "the retry must not carry _since");
		assertEquals(List.of("full"), persistence.finishes, "a full export must persist as a full snapshot");
		assertEquals("FULL", savedRuns.get(0).getMode().name(), "the run should record what actually happened");
	}

	@Test
	void resumedFullCrawlUsesFloorsSkipsTheDeletionScanAndClearsCheckpointsOnCompletion() {
		String floor = "2026-03-01T00:00:00Z";
		List<CrawlRun> savedRuns = new ArrayList<>();
		List<String> clears = new ArrayList<>();
		java.util.Map<String, String> floorsSeen = new java.util.HashMap<>();
		List<String> finishes = new ArrayList<>();

		CrawlCheckpoint checkpoint = new CrawlCheckpoint();
		checkpoint.setKey(CrawlCheckpoint.key("job-resume", "http://auto.example/fhir", "Organization"));
		checkpoint.setJobId("job-resume");
		checkpoint.setServerKey("http://auto.example/fhir");
		checkpoint.setResourceType("Organization");
		checkpoint.setWatermark(floor);
		CrawlCheckpointRepository checkpoints = (CrawlCheckpointRepository) Proxy.newProxyInstance(
				CrawlCheckpointRepository.class.getClassLoader(),
				new Class<?>[] {CrawlCheckpointRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByJobIdAndServerKey" -> List.of(checkpoint);
					case "deleteByJobIdAndServerKey" -> {
						clears.add(args[0] + "|" + args[1]);
						yield null;
					}
					case "save" -> null;
					default -> throw new UnsupportedOperationException(method.getName());
				});

		CrawlPersistenceService persistence = new CrawlPersistenceService(resourceRepo(), serverRegistry()) {
			@Override
			public SnapshotSession openSession(String serverKey, String serverLabel) {
				throw new AssertionError("a resumed full crawl must open the resumed session");
			}

			@Override
			public SnapshotSession openResumedFullSession(String serverKey, String serverLabel) {
				return new SnapshotSession() {
					@Override
					public void accept(List<FetchedResource> batch) {}

					@Override
					public PersistCounts finishFullSnapshot() {
						throw new AssertionError("the full-snapshot deletion scan must not run on a resumed crawl");
					}

					@Override
					public PersistCounts finishIncremental(
							List<org.hl7.davinci.api.service.DeletionEntry> deletions) {
						finishes.add("incremental:" + deletions.size());
						return new PersistCounts(1, 0, 0, 1);
					}
				};
			}
		};

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			public ServerTime getServerTime(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
				return new ServerTime("2026-07-01T00:00:00Z", "date-header");
			}

			@Override
			public SearchResult searchTypesByLastUpdated(
					String serverUrl,
					String serverKey,
					int pageSize,
					String since,
					ResumeContext resume,
					java.util.function.Consumer<StepEvent> steps,
					java.util.function.Consumer<List<FetchedResource>> resourceSink) {
				floorsSeen.putAll(resume.floors());
				return new SearchResult(1, 40, 1, 1);
			}
		};

		CrawlService service = new CrawlService(
				client,
				persistence,
				new RecordingManifestService(),
				new NoopEvents(),
				jobRepo(),
				recordingRunRepo(savedRuns),
				checkpoints,
				new ObjectMapper(),
				new ApiProperties());

		service.crawlJob(job("job-resume", CrawlStrategy.SEARCH_LAST_UPDATED), "batch-resume");

		assertEquals(RunStatus.COMPLETED, savedRuns.get(0).getStatus());
		assertEquals(floor, floorsSeen.get("Organization"), "the checkpoint floor reaches the search strategy");
		assertEquals(List.of("incremental:0"), finishes, "a resumed full crawl finishes with no-deletion semantics");
		assertEquals(
				List.of("job-resume|http://auto.example/fhir"), clears, "completing the server clears its checkpoints");
	}

	private static CrawlService service(
			FhirCrawlClient client, CrawlPersistenceService persistence, CrawlRunRepository runRepo) {
		return new CrawlService(
				client,
				persistence,
				new RecordingManifestService(),
				new NoopEvents(),
				jobRepo(),
				runRepo,
				checkpointRepo(),
				new ObjectMapper(),
				new ApiProperties());
	}

	private static CrawlJob job(String id, CrawlStrategy strategy) {
		CrawlJob job = new CrawlJob();
		job.setId(id);
		job.setName(id);
		job.setStrategy(strategy);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverLabel\":\"srv\",\"url\":\"http://auto.example/fhir\"}]");
		return job;
	}

	/** Scriptable client for AUTO resolution: each strategy entry point records what ran. */
	private static class AutoFhirCrawlClient extends FhirCrawlClient {
		boolean bulkSupported = true;
		boolean sinceSupported = true;
		boolean partitionedViable = true;
		boolean plainViable = true;
		String bulkSinceSeen;
		int bulkCalls;
		int partitionedProbes;
		int plainProbes;
		String dispatched;

		AutoFhirCrawlClient() {
			super(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
		}

		@Override
		public ServerTime getServerTime(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
			return new ServerTime("2026-01-01T00:00:00Z", "test");
		}

		@Override
		public SearchResult bulkExport(
				String serverUrl,
				String serverKey,
				String since,
				java.util.function.Consumer<StepEvent> steps,
				java.util.function.Consumer<List<FetchedResource>> resourceSink) {
			bulkCalls++;
			bulkSinceSeen = since;
			if (!bulkSupported) {
				throw new StrategyUnsupportedException("Server does not support $export: HTTP 404");
			}
			if (since != null && !sinceSupported) {
				throw new SinceUnsupportedException("$export kick-off with _since rejected: HTTP 400");
			}
			return new SearchResult(1, 40, 1, 0);
		}

		@Override
		public boolean probePartitionedSearch(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
			partitionedProbes++;
			return partitionedViable;
		}

		@Override
		public boolean probePlainSearch(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
			plainProbes++;
			return plainViable;
		}

		@Override
		public SearchResult searchTypesPartitioned(
				String serverUrl,
				String serverKey,
				int pageSize,
				String since,
				ServerTime anchor,
				ResumeContext resume,
				java.util.function.Consumer<StepEvent> steps,
				java.util.function.Consumer<List<FetchedResource>> resourceSink) {
			dispatched = "partitioned";
			return new SearchResult(1, 40, 1, 1);
		}

		@Override
		public HistoryResult historyExport(
				String serverUrl,
				String serverKey,
				String since,
				java.util.function.Consumer<StepEvent> steps,
				java.util.function.Consumer<List<FetchedResource>> resourceSink) {
			dispatched = "history";
			return new HistoryResult(1, List.of(), 40, 1, 1);
		}

		@Override
		public DeletionScanResult scanDeletions(
				String serverUrl, String since, int pageSize, java.util.function.Consumer<StepEvent> steps) {
			return new DeletionScanResult(List.of(), 1, 1, 10);
		}
	}

	/** Records which finish path each session took, so full-vs-incremental persistence is observable. */
	private static class FinishRecordingPersistence extends CrawlPersistenceService {
		final List<String> finishes = new ArrayList<>();

		FinishRecordingPersistence() {
			super(resourceRepo(), serverRegistry());
		}

		@Override
		public SnapshotSession openSession(String serverKey, String serverLabel) {
			return new SnapshotSession() {
				@Override
				public void accept(List<FetchedResource> batch) {}

				@Override
				public PersistCounts finishFullSnapshot() {
					finishes.add("full");
					return new PersistCounts(1, 0, 0, 1);
				}

				@Override
				public PersistCounts finishIncremental(List<org.hl7.davinci.api.service.DeletionEntry> deletions) {
					finishes.add("incremental");
					return new PersistCounts(1, 0, 0, 1);
				}
			};
		}
	}

	private static class FakeFhirCrawlClient extends FhirCrawlClient {
		private final String good;
		private final String bad;
		private final FetchedResource fetched;

		FakeFhirCrawlClient(String good, String bad, FetchedResource fetched) {
			super(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
			this.good = good;
			this.bad = bad;
			this.fetched = fetched;
		}

		@Override
		public ServerTime getServerTime(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
			if (bad.equals(serverUrl)) {
				throw new IllegalStateException("server unavailable");
			}
			return new ServerTime("2026-01-01T00:00:00Z", "test");
		}

		@Override
		public SearchResult searchTypes(
				String serverUrl,
				String serverKey,
				int pageSize,
				String since,
				java.util.function.Consumer<StepEvent> steps,
				java.util.function.Consumer<List<FetchedResource>> resourceSink) {
			if (!good.equals(serverUrl)) {
				throw new IllegalStateException("unexpected server " + serverUrl);
			}
			resourceSink.accept(List.of(fetched));
			return new SearchResult(1, 40, 1, 1);
		}
	}

	/** Holds the crawl on the worker thread until released, so the in-flight window is observable. */
	private static class BlockingFhirCrawlClient extends FhirCrawlClient {
		private final FetchedResource fetched;
		private final CountDownLatch release;

		/** Signals that the worker thread has actually started crawling. */
		final CountDownLatch entered = new CountDownLatch(1);

		BlockingFhirCrawlClient(FetchedResource fetched, CountDownLatch release) {
			super(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
			this.fetched = fetched;
			this.release = release;
		}

		@Override
		public ServerTime getServerTime(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
			return new ServerTime("2026-01-01T00:00:00Z", "test");
		}

		@Override
		public SearchResult searchTypes(
				String serverUrl,
				String serverKey,
				int pageSize,
				String since,
				java.util.function.Consumer<StepEvent> steps,
				java.util.function.Consumer<List<FetchedResource>> resourceSink) {
			resourceSink.accept(List.of(fetched));
			return new SearchResult(1, 40, 1, 1);
		}
	}

	private static class FakePersistence extends CrawlPersistenceService {
		FakePersistence() {
			super(resourceRepo(), serverRegistry());
		}

		@Override
		public SnapshotSession openSession(String serverKey, String serverLabel) {
			return new SnapshotSession() {
				@Override
				public void accept(List<FetchedResource> batch) {}

				@Override
				public PersistCounts finishFullSnapshot() {
					return new PersistCounts(1, 0, 0, 1);
				}

				@Override
				public PersistCounts finishIncremental(List<org.hl7.davinci.api.service.DeletionEntry> deletions) {
					return new PersistCounts(1, 0, 0, 1);
				}
			};
		}
	}

	private static class RecordingPersistence extends CrawlPersistenceService {
		final AtomicInteger accepted = new AtomicInteger();

		RecordingPersistence() {
			super(resourceRepo(), serverRegistry());
		}

		@Override
		public SnapshotSession openSession(String serverKey, String serverLabel) {
			return new SnapshotSession() {
				@Override
				public void accept(List<FetchedResource> batch) {
					accepted.addAndGet(batch.size());
				}

				@Override
				public PersistCounts finishFullSnapshot() {
					return new PersistCounts(accepted.get(), 0, 0, accepted.get());
				}

				@Override
				public PersistCounts finishIncremental(List<org.hl7.davinci.api.service.DeletionEntry> deletions) {
					return finishFullSnapshot();
				}
			};
		}
	}

	private static class EmittingAfterCancelFhirCrawlClient extends FhirCrawlClient {
		private final FetchedResource first;
		private final FetchedResource second;
		final CountDownLatch firstEmitted = new CountDownLatch(1);
		final CountDownLatch releaseSecond = new CountDownLatch(1);
		final CountDownLatch secondAttempted = new CountDownLatch(1);

		EmittingAfterCancelFhirCrawlClient(FetchedResource first, FetchedResource second) {
			super(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
			this.first = first;
			this.second = second;
		}

		@Override
		public ServerTime getServerTime(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
			return new ServerTime("2026-01-01T00:00:00Z", "test");
		}

		@Override
		public SearchResult searchTypes(
				String serverUrl,
				String serverKey,
				int pageSize,
				String since,
				java.util.function.Consumer<StepEvent> steps,
				java.util.function.Consumer<List<FetchedResource>> resourceSink) {
			resourceSink.accept(List.of(first));
			firstEmitted.countDown();
			try {
				releaseSecond.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			secondAttempted.countDown();
			resourceSink.accept(List.of(second));
			return new SearchResult(2, 4, 1, 1);
		}
	}

	private static class RecordingManifestService extends ManifestService {
		int createCalls;
		long lastCarryoverMs;

		RecordingManifestService() {
			super(null, null, null, null);
		}

		@Override
		public ManifestRecord createManifest(
				CrawlJob job,
				String batchId,
				String windowSince,
				List<String> serverKeys,
				long operationStartNanos,
				long carryoverMs) {
			createCalls++;
			lastCarryoverMs = carryoverMs;
			return new ManifestRecord();
		}
	}

	private static class NoopEvents extends CrawlEventService {
		NoopEvents() {
			super(null);
		}

		@Override
		public void start(String batchId) {}

		@Override
		public void publish(String batchId, String runId, String serverKey, int seq, StepEvent event) {}

		@Override
		public void complete(String batchId) {}
	}

	private static class RecordingEvents extends NoopEvents {
		final List<StepEvent> published = new ArrayList<>();
		final List<Integer> persistedSeqs = new ArrayList<>();

		@Override
		public void publish(String batchId, String runId, String serverKey, int seq, StepEvent event) {
			published.add(event);
			if (!event.progress()) {
				persistedSeqs.add(seq);
			}
		}
	}

	private static class CompletionAwareEvents extends NoopEvents {
		final CountDownLatch completed = new CountDownLatch(1);

		@Override
		public void complete(String batchId) {
			completed.countDown();
		}
	}

	/** Fails the first server interaction with the given HAPI server exception. */
	private static class ThrowingFhirCrawlClient extends FhirCrawlClient {
		private final BaseServerResponseException failure;

		ThrowingFhirCrawlClient(BaseServerResponseException failure) {
			super(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
			this.failure = failure;
		}

		@Override
		public ServerTime getServerTime(String serverUrl, java.util.function.Consumer<StepEvent> steps) {
			throw failure;
		}
	}

	private static CrawlJobRepository jobRepo() {
		return proxy(CrawlJobRepository.class);
	}

	/** No checkpoints exist and writes are ignored: crawls behave as never-interrupted. */
	private static CrawlCheckpointRepository checkpointRepo() {
		return (CrawlCheckpointRepository) Proxy.newProxyInstance(
				CrawlCheckpointRepository.class.getClassLoader(),
				new Class<?>[] {CrawlCheckpointRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByJobIdAndServerKey" -> List.of();
					case "deleteByJobIdAndServerKey", "save" -> null;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	/** Records saved runs so write suppression is observable. */
	private static CrawlRunRepository recordingRunRepo(List<CrawlRun> saved) {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc",
							"findTop1ByJobIdAndServerKeyOrderByStartedAtDesc" -> Optional.empty();
					case "save" -> {
						saved.add((CrawlRun) args[0]);
						yield args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	/** Records saved runs and reports a prior completed run, so incremental paths engage. */
	private static CrawlRunRepository runRepoWithPriorRun(List<CrawlRun> saved, String serverTimeAtStart) {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findTop1ByJobIdAndServerKeyOrderByStartedAtDesc" -> Optional.empty();
					case "findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc" -> {
						CrawlRun prior = new CrawlRun();
						prior.setServerTimeAtStart(serverTimeAtStart);
						yield Optional.of(prior);
					}
					case "save" -> {
						saved.add((CrawlRun) args[0]);
						yield args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	/** A repo with no stored jobs, so the trigger path's markRunning is a no-op. */
	private static CrawlJobRepository idleJobRepo() {
		return (CrawlJobRepository) Proxy.newProxyInstance(
				CrawlJobRepository.class.getClassLoader(),
				new Class<?>[] {CrawlJobRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findById" -> Optional.empty();
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlRunRepository runRepo() {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc",
							"findTop1ByJobIdAndServerKeyOrderByStartedAtDesc" -> Optional.empty();
					case "save" -> args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlResourceRepository resourceRepo() {
		return proxy(CrawlResourceRepository.class);
	}

	/** Never actually invoked: every fake persistence subclass overrides openSession directly. */
	private static ServerRegistry serverRegistry() {
		return new ServerRegistry(null);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type) {
		return (T) Proxy.newProxyInstance(
				type.getClassLoader(),
				new Class<?>[] {type},
				(proxy, method, args) -> throwUnsupported(method.getName()));
	}

	private static Object throwUnsupported(String methodName) {
		throw new UnsupportedOperationException(methodName);
	}
}

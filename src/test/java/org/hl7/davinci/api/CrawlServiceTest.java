package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
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
import org.hl7.davinci.api.service.StepEvent;
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
				good + "|Organization/a",
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
				server + "|Organization/a",
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
		assertEquals(List.of(), savedRuns, "a cancelled run must not be persisted");
		assertEquals(0, manifestService.createCalls, "a cancelled run must not publish a manifest");
	}

	@Test
	void cancelJobStopsStreamedResourceBatches() throws Exception {
		String server = "http://good.example/fhir";
		FetchedResource first = new FetchedResource(server + "|Organization/a", "Organization", "a", "1", null, "{}", 2);
		FetchedResource second = new FetchedResource(server + "|Organization/b", "Organization", "b", "1", null, "{}", 2);
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
	void triggerAsyncExposesTheActiveBatchUntilTheRunCompletes() throws Exception {
		String server = "http://good.example/fhir";
		FetchedResource fetched = new FetchedResource(
				server + "|Organization/a",
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
		public ServerTime getServerTime(String serverUrl) {
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
		public ServerTime getServerTime(String serverUrl) {
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
			super(resourceRepo());
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
			super(resourceRepo());
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
		public ServerTime getServerTime(String serverUrl) {
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

		RecordingManifestService() {
			super(null, null, null);
		}

		@Override
		public ManifestRecord createManifest(
				CrawlJob job, String batchId, String windowSince, List<String> serverKeys, long operationStartNanos) {
			createCalls++;
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
		public ServerTime getServerTime(String serverUrl) {
			throw failure;
		}
	}

	private static CrawlJobRepository jobRepo() {
		return proxy(CrawlJobRepository.class);
	}

	/** Records saved runs so write suppression is observable. */
	private static CrawlRunRepository recordingRunRepo(List<CrawlRun> saved) {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc" -> Optional.empty();
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
					case "findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc" -> Optional.empty();
					case "save" -> args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlResourceRepository resourceRepo() {
		return proxy(CrawlResourceRepository.class);
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

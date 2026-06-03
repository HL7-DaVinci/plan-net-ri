package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.uhn.fhir.context.FhirContext;
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
import org.hl7.davinci.api.service.FetchedResource;
import org.hl7.davinci.api.service.FhirCrawlClient;
import org.hl7.davinci.api.service.ManifestService;
import org.hl7.davinci.api.service.StepEvent;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

	private static class FakeFhirCrawlClient extends FhirCrawlClient {
		private final String good;
		private final String bad;
		private final FetchedResource fetched;

		FakeFhirCrawlClient(String good, String bad, FetchedResource fetched) {
			super(FhirContext.forR4(), new ObjectMapper());
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
				java.util.function.Consumer<StepEvent> steps) {
			if (!good.equals(serverUrl)) {
				throw new IllegalStateException("unexpected server " + serverUrl);
			}
			return new SearchResult(List.of(fetched), 40, 1, 1);
		}
	}

	private static class FakePersistence extends CrawlPersistenceService {
		FakePersistence() {
			super(resourceRepo());
		}

		@Override
		public PersistCounts persistFullSnapshot(String serverKey, String serverLabel, List<FetchedResource> fetched) {
			return new PersistCounts(1, 0, 0);
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

	private static CrawlJobRepository jobRepo() {
		return proxy(CrawlJobRepository.class);
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

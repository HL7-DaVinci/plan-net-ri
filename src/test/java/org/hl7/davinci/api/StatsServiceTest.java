package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.model.JobStatsResponse;
import org.hl7.davinci.api.model.OverallStatsResponse;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.hl7.davinci.api.service.ServerRegistry;
import org.hl7.davinci.api.service.StatsService;
import org.hl7.davinci.common.PlanNetTypes;
import org.junit.jupiter.api.Test;

class StatsServiceTest {

	@Test
	void pausedSegmentsAreExcludedFromJobAggregates() {
		// The completed run already absorbed the paused segment's numbers, so counting the
		// segment row again would double count.
		CrawlRun paused = run(RunStatus.PAUSED, 400, 4_000);
		CrawlRun completed = run(RunStatus.COMPLETED, 1_000, 10_000);

		StatsService service =
				new StatsService(manifestRepo(), runRepo(List.of(completed, paused)), null, null, null);
		JobStatsResponse stats = service.computeStats("job-1");

		assertEquals(1, stats.runCount(), "a paused segment is not a finished run");
		assertEquals(1, stats.completedRuns());
		assertEquals(0, stats.erroredRuns());
		assertEquals(1_000, stats.totalRecords(), "the completed run already carries the segment's records");
		assertEquals(10_000, stats.totalBytes());
	}

	@Test
	void overallStatsCountByPrimaryKeyRangeAndAreCached() {
		CrawlJob job = new CrawlJob();
		job.setId("job-1");
		// Trailing slash exercises server-key normalization; server ids below use the normalized form.
		job.setServers("[{\"url\":\"http://a.example/fhir/\"},{\"url\":\"http://b.example/fhir\"}]");
		AtomicInteger countQueries = new AtomicInteger();
		Map<String, Long> countsByServerAndType =
				Map.of("1:Practitioner", 5L, "1:Organization", 2L, "2:Organization", 7L);

		StatsService service = new StatsService(
				manifestRepo(),
				null,
				resourceRepo(countsByServerAndType, countQueries),
				serverRegistry(Map.of("http://a.example/fhir", 1, "http://b.example/fhir", 2)),
				jobRepo(List.of(job)));
		OverallStatsResponse stats = service.computeOverall();

		assertEquals(14, stats.totalResources());
		assertEquals(2, stats.serverCount(), "both servers hold data");
		assertEquals(1, stats.jobCount());
		assertEquals(
				List.of(
						new OverallStatsResponse.TypeCount("Organization", 9),
						new OverallStatsResponse.TypeCount("Practitioner", 5)),
				stats.byType());

		int queriesForFirstCall = countQueries.get();
		assertEquals(stats, service.computeOverall(), "a second call inside the TTL is served from cache");
		assertEquals(queriesForFirstCall, countQueries.get(), "the cached call issues no count queries");
	}

	private static CrawlRun run(RunStatus status, long records, long bytes) {
		CrawlRun run = new CrawlRun();
		run.setStatus(status);
		run.setRecords(records);
		run.setBytes(bytes);
		return run;
	}

	private static ManifestRepository manifestRepo() {
		return (ManifestRepository) Proxy.newProxyInstance(
				ManifestRepository.class.getClassLoader(),
				new Class<?>[] {ManifestRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByJobIdOrderByGeneratedAtDescIdDesc" -> List.of();
					case "count" -> 1L;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlJobRepository jobRepo(List<CrawlJob> jobs) {
		return (CrawlJobRepository) Proxy.newProxyInstance(
				CrawlJobRepository.class.getClassLoader(),
				new Class<?>[] {CrawlJobRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findAll" -> jobs;
					case "count" -> (long) jobs.size();
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	/** Answers {@code countByIdServerIdAndIdTypeId} from the given map keyed by {@code serverId:type}. */
	private static CrawlResourceRepository resourceRepo(Map<String, Long> countsByServerAndType, AtomicInteger queries) {
		return (CrawlResourceRepository) Proxy.newProxyInstance(
				CrawlResourceRepository.class.getClassLoader(),
				new Class<?>[] {CrawlResourceRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "countByIdServerIdAndIdTypeId" -> {
						queries.incrementAndGet();
						int serverId = (int) args[0];
						String type = PlanNetTypes.typeOf((int) args[1]);
						yield countsByServerAndType.getOrDefault(serverId + ":" + type, 0L);
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static ServerRegistry serverRegistry(Map<String, Integer> idsByServerKey) {
		return new ServerRegistry(null) {
			@Override
			public OptionalInt idIfExists(String serverKey) {
				Integer id = idsByServerKey.get(serverKey);
				return id == null ? OptionalInt.empty() : OptionalInt.of(id);
			}
		};
	}

	private static CrawlRunRepository runRepo(List<CrawlRun> runs) {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByJobIdOrderByStartedAtDesc" -> runs;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}

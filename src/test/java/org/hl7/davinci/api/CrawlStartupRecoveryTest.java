package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.service.CrawlService;
import org.hl7.davinci.api.service.CrawlStartupRecovery;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrawlStartupRecoveryTest {

	@Test
	void doesNothingWhenFlagDisabled() {
		List<CrawlJob> saved = new ArrayList<>();
		CrawlJobRepository jobRepo = jobRepo(List.of(runningJob("j1", "Job One")), saved);

		new CrawlStartupRecovery(jobRepo, neverInFlight(), new ApiProperties()).unblockStaleJobs();

		assertTrue(saved.isEmpty(), "with the flag off, stale flags are left untouched");
	}

	@Test
	void clearsStaleRunningFlagWhenEnabled() {
		List<CrawlJob> saved = new ArrayList<>();
		CrawlJobRepository jobRepo = jobRepo(List.of(runningJob("j1", "Job One")), saved);
		ApiProperties props = new ApiProperties();
		props.setResumeCrawlsOnStartup(true);

		new CrawlStartupRecovery(jobRepo, neverInFlight(), props).unblockStaleJobs();

		assertEquals(1, saved.size());
		assertFalse(saved.get(0).isRunning(), "the stale flag is cleared so the job can run again");
	}

	@Test
	void leavesAGenuinelyInFlightJobAlone() {
		List<CrawlJob> saved = new ArrayList<>();
		CrawlJobRepository jobRepo = jobRepo(List.of(runningJob("j1", "Job One")), saved);
		ApiProperties props = new ApiProperties();
		props.setResumeCrawlsOnStartup(true);

		CrawlService inFlight = new CrawlService(null, null, null, null, null, null, null, null) {
			@Override
			public String getActiveBatchId(String jobId) {
				return "live-batch"; // a worker is actually running this job
			}
		};

		new CrawlStartupRecovery(jobRepo, inFlight, props).unblockStaleJobs();

		assertTrue(saved.isEmpty(), "a job with a live worker must not be cleared");
	}

	private static CrawlService neverInFlight() {
		return new CrawlService(null, null, null, null, null, null, null, null) {
			@Override
			public String getActiveBatchId(String jobId) {
				return null;
			}
		};
	}

	private static CrawlJob runningJob(String id, String name) {
		CrawlJob job = new CrawlJob();
		job.setId(id);
		job.setName(name);
		job.setRunning(true);
		return job;
	}

	private static CrawlJobRepository jobRepo(List<CrawlJob> running, List<CrawlJob> saved) {
		return (CrawlJobRepository) Proxy.newProxyInstance(
				CrawlJobRepository.class.getClassLoader(),
				new Class<?>[] {CrawlJobRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByRunningTrue" -> new ArrayList<>(running);
					case "save" -> {
						saved.add((CrawlJob) args[0]);
						yield args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}

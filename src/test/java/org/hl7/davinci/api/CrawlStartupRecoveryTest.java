package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.CrawlStep;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.CrawlStepRepository;
import org.hl7.davinci.api.service.CrawlService;
import org.hl7.davinci.api.service.CrawlStartupRecovery;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CrawlStartupRecoveryTest {

	@Test
	void clearsStaleFlagWithoutResumingWhenFlagDisabled() {
		List<CrawlJob> saved = new ArrayList<>();
		List<String> triggered = new ArrayList<>();
		CrawlJobRepository jobRepo = jobRepo(List.of(runningJob("j1", "Job One")), saved);
		ApiProperties props = new ApiProperties();
		props.setResumeCrawlsOnStartup(false);

		new CrawlStartupRecovery(jobRepo, runRepo(List.of(), new ArrayList<>()), stepRepo(null), idleService(triggered), props).unblockStaleJobs();

		assertEquals(1, saved.size(), "the stale flag is cleared even when resume is off");
		assertFalse(saved.get(0).isRunning());
		assertTrue(triggered.isEmpty(), "with resume off, the interrupted job is not re-triggered");
	}

	@Test
	void clearsStaleFlagAndResumesWhenFlagEnabled() {
		List<CrawlJob> saved = new ArrayList<>();
		List<String> triggered = new ArrayList<>();
		CrawlJobRepository jobRepo = jobRepo(List.of(runningJob("j1", "Job One")), saved);
		ApiProperties props = new ApiProperties();
		props.setResumeCrawlsOnStartup(true);

		new CrawlStartupRecovery(jobRepo, runRepo(List.of(), new ArrayList<>()), stepRepo(null), idleService(triggered), props).unblockStaleJobs();

		assertEquals(1, saved.size());
		assertFalse(saved.get(0).isRunning(), "the stale flag is cleared so the job can run again");
		assertEquals(List.of("j1"), triggered, "the interrupted job is re-triggered to resume the crawl");
	}

	@Test
	void aFailedResumeStillProcessesRemainingJobs() {
		List<CrawlJob> saved = new ArrayList<>();
		List<String> triggered = new ArrayList<>();
		CrawlJobRepository jobRepo =
				jobRepo(List.of(runningJob("j1", "Job One"), runningJob("j2", "Job Two")), saved);
		ApiProperties props = new ApiProperties();
		props.setResumeCrawlsOnStartup(true);

		CrawlService failsFirst = new CrawlService(null, null, null, null, null, null, null, null, null) {
			@Override
			public String getActiveBatchId(String jobId) {
				return null;
			}

			@Override
			public String triggerAsync(CrawlJob job) {
				if ("j1".equals(job.getId())) {
					throw new IllegalStateException("boom");
				}
				triggered.add(job.getId());
				return "batch-" + job.getId();
			}
		};

		new CrawlStartupRecovery(jobRepo, runRepo(List.of(), new ArrayList<>()), stepRepo(null), failsFirst, props).unblockStaleJobs();

		assertEquals(2, saved.size(), "both stale flags are cleared despite the failed resume");
		assertEquals(List.of("j2"), triggered);
	}

	@Test
	void leavesAGenuinelyInFlightJobAlone() {
		List<CrawlJob> saved = new ArrayList<>();
		List<String> triggered = new ArrayList<>();
		CrawlJobRepository jobRepo = jobRepo(List.of(runningJob("j1", "Job One")), saved);
		ApiProperties props = new ApiProperties();
		props.setResumeCrawlsOnStartup(true);

		CrawlService inFlight = new CrawlService(null, null, null, null, null, null, null, null, null) {
			@Override
			public String getActiveBatchId(String jobId) {
				return "live-batch"; // a worker is actually running this job
			}

			@Override
			public String triggerAsync(CrawlJob job) {
				triggered.add(job.getId());
				return "batch";
			}
		};

		new CrawlStartupRecovery(jobRepo, runRepo(List.of(), new ArrayList<>()), stepRepo(null), inFlight, props).unblockStaleJobs();

		assertTrue(saved.isEmpty(), "a job with a live worker must not be cleared");
		assertTrue(triggered.isEmpty(), "a job with a live worker must not be re-triggered");
	}

	@Test
	void convertsADanglingRunningRowToAPausedSegmentWithStepDerivedDuration() {
		Instant startedAt = Instant.parse("2026-07-11T10:00:00Z");
		CrawlRun dangling = new CrawlRun();
		dangling.setId("run-1");
		dangling.setJobId("j1");
		dangling.setBatchId("batch-1");
		dangling.setStartedAt(startedAt);
		dangling.setStatus(RunStatus.RUNNING);
		CrawlStep lastStep = new CrawlStep();
		lastStep.setAt(startedAt.plusSeconds(90));
		List<CrawlRun> savedRuns = new ArrayList<>();

		new CrawlStartupRecovery(
						jobRepo(List.of(), new ArrayList<>()),
						runRepo(List.of(dangling), savedRuns),
						stepRepo(lastStep),
						idleService(new ArrayList<>()),
						new ApiProperties())
				.unblockStaleJobs();

		assertEquals(1, savedRuns.size(), "the dangling row is rewritten");
		assertEquals(RunStatus.PAUSED, savedRuns.get(0).getStatus(), "a workerless RUNNING row becomes a paused segment");
		assertEquals(90_000, savedRuns.get(0).getDurationMs(), "elapsed time comes from the last persisted step");
	}

	private static CrawlRunRepository runRepo(List<CrawlRun> running, List<CrawlRun> saved) {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByStatus" -> new ArrayList<>(running);
					case "save" -> {
						saved.add((CrawlRun) args[0]);
						yield args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlStepRepository stepRepo(CrawlStep lastStep) {
		return (CrawlStepRepository) Proxy.newProxyInstance(
				CrawlStepRepository.class.getClassLoader(),
				new Class<?>[] {CrawlStepRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findTop1ByBatchIdOrderBySeqDesc" -> Optional.ofNullable(lastStep);
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlService idleService(List<String> triggered) {
		return new CrawlService(null, null, null, null, null, null, null, null, null) {
			@Override
			public String getActiveBatchId(String jobId) {
				return null;
			}

			@Override
			public String triggerAsync(CrawlJob job) {
				triggered.add(job.getId());
				return "batch-" + job.getId();
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

package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.CrawlStepRepository;
import org.hl7.davinci.api.service.CrawlService;
import org.hl7.davinci.api.service.JobDeletionService;
import org.hl7.davinci.api.service.ManifestService;
import org.junit.jupiter.api.Test;

class JobDeletionServiceTest {

	@Test
	void deletesManifestsThenStepsThenRunsThenTheJob() {
		Recorder rec = new Recorder();
		JobDeletionService service = new JobDeletionService(
				jobRepo(job("job-1", false), rec),
				runRepo(List.of("batch-1", "batch-2"), rec),
				stepRepo(rec),
				resourceRepo(rec),
				manifestService(rec),
				crawlService(rec));

		service.deleteJob("job-1");

		assertEquals("job-1", rec.manifestsClearedFor);
		assertEquals(List.of("batch-1", "batch-2"), rec.stepsDeletedForBatchIds);
		assertEquals("job-1", rec.runsDeletedForJob);
		assertEquals("job-1", rec.jobDeleted);
	}

	@Test
	void cancelsTheActiveRunAndStillCascadesWhenRunning() {
		Recorder rec = new Recorder();
		JobDeletionService service = new JobDeletionService(
				jobRepo(job("job-1", true), rec),
				runRepo(List.of("batch-1"), rec),
				stepRepo(rec),
				resourceRepo(rec),
				manifestService(rec),
				crawlService(rec));

		service.deleteJob("job-1");

		assertEquals("job-1", rec.cancelledJob, "the in-flight run must be cancelled before the cascade");
		assertEquals("job-1", rec.manifestsClearedFor);
		assertEquals(List.of("batch-1"), rec.stepsDeletedForBatchIds);
		assertEquals("job-1", rec.runsDeletedForJob);
		assertEquals("job-1", rec.jobDeleted);
	}

	@Test
	void doesNothingWhenTheJobDoesNotExist() {
		Recorder rec = new Recorder();
		JobDeletionService service = new JobDeletionService(
				jobRepo(null, rec),
				runRepo(List.of(), rec),
				stepRepo(rec),
				resourceRepo(rec),
				manifestService(rec),
				crawlService(rec));

		service.deleteJob("missing");

		assertNull(rec.cancelledJob);
		assertNull(rec.manifestsClearedFor);
		assertNull(rec.runsDeletedForJob);
		assertNull(rec.jobDeleted);
	}

	@Test
	void skipsStepDeletionWhenTheJobHasNoRuns() {
		Recorder rec = new Recorder();
		JobDeletionService service = new JobDeletionService(
				jobRepo(job("job-1", false), rec),
				runRepo(List.of(), rec),
				stepRepo(rec),
				resourceRepo(rec),
				manifestService(rec),
				crawlService(rec));

		service.deleteJob("job-1");

		assertNull(rec.stepsDeletedForBatchIds, "no batch ids means no empty IN delete should be issued");
		assertEquals("job-1", rec.runsDeletedForJob);
		assertEquals("job-1", rec.jobDeleted);
	}

	@Test
	void clearsResourcesForServersNoRemainingJobTargets() {
		Recorder rec = new Recorder();
		CrawlJob deleted = job("job-1", false);
		CrawlJob other = job("job-2", false);
		// job-1 targets serverA (now orphaned) and serverB (still used by job-2); only serverA is cleared.
		Map<String, Set<String>> serverKeys = Map.of(
				"job-1", Set.of("https://a.example", "https://b.example"),
				"job-2", Set.of("https://b.example"));
		JobDeletionService service = new JobDeletionService(
				jobRepo(deleted, rec, List.of(other)),
				runRepo(List.of(), rec),
				stepRepo(rec),
				resourceRepo(rec),
				manifestService(rec),
				crawlService(rec, serverKeys));

		service.deleteJob("job-1");

		assertEquals(
				List.of("https://a.example"),
				rec.deletedServers,
				"only the server no remaining job targets is cleared");
	}

	private static final class Recorder {
		String cancelledJob;
		String manifestsClearedFor;
		List<String> stepsDeletedForBatchIds;
		String runsDeletedForJob;
		String jobDeleted;
		final List<String> deletedServers = new ArrayList<>();
	}

	private static CrawlService crawlService(Recorder rec) {
		return crawlService(rec, Map.of());
	}

	private static CrawlService crawlService(Recorder rec, Map<String, Set<String>> serverKeysByJob) {
		return new CrawlService(null, null, null, null, null, null, null, null) {
			@Override
			public void cancelJob(String jobId) {
				rec.cancelledJob = jobId;
			}

			@Override
			public Set<String> serverKeys(CrawlJob job) {
				return serverKeysByJob.getOrDefault(job.getId(), Set.of());
			}
		};
	}

	private static CrawlJob job(String id, boolean running) {
		CrawlJob job = new CrawlJob();
		job.setId(id);
		job.setRunning(running);
		return job;
	}

	private static ManifestService manifestService(Recorder rec) {
		return new ManifestService(null, null, null) {
			@Override
			public int deleteManifestsForJob(String jobId) {
				rec.manifestsClearedFor = jobId;
				return 0;
			}
		};
	}

	private static CrawlJobRepository jobRepo(CrawlJob job, Recorder rec) {
		return jobRepo(job, rec, List.of());
	}

	private static CrawlJobRepository jobRepo(CrawlJob job, Recorder rec, List<CrawlJob> remaining) {
		return (CrawlJobRepository) Proxy.newProxyInstance(
				CrawlJobRepository.class.getClassLoader(),
				new Class<?>[] {CrawlJobRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findById" -> Optional.ofNullable(job);
					case "findAll" -> remaining;
					case "deleteById" -> {
						rec.jobDeleted = (String) args[0];
						yield null;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlResourceRepository resourceRepo(Recorder rec) {
		return (CrawlResourceRepository) Proxy.newProxyInstance(
				CrawlResourceRepository.class.getClassLoader(),
				new Class<?>[] {CrawlResourceRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "deleteByServerKey" -> {
						rec.deletedServers.add((String) args[0]);
						yield 0;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlRunRepository runRepo(List<String> batchIds, Recorder rec) {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findBatchIdsByJobId" -> batchIds;
					case "deleteByJobId" -> {
						rec.runsDeletedForJob = (String) args[0];
						yield null;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlStepRepository stepRepo(Recorder rec) {
		return (CrawlStepRepository) Proxy.newProxyInstance(
				CrawlStepRepository.class.getClassLoader(),
				new Class<?>[] {CrawlStepRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "deleteByBatchIdIn" -> {
						@SuppressWarnings("unchecked")
						Collection<String> ids = (Collection<String>) args[0];
						rec.stepsDeletedForBatchIds = new ArrayList<>(ids);
						yield null;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}

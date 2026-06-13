package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.model.JobResponse;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.service.CrawlService;
import org.hl7.davinci.api.service.JobDeletionService;
import org.hl7.davinci.api.web.ApiJobController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ApiJobControllerTest {

	@Test
	void pauseDisablesAndPersistsTheJob() {
		CrawlJob[] saved = new CrawlJob[1];
		ApiJobController controller =
				controller(jobRepo(job("job-1", true, "0 0 2 * * *"), saved), deletionService(new boolean[1]));

		JobResponse response = controller.pause("job-1");

		assertFalse(response.enabled());
		assertNotNull(saved[0], "pause must persist the job");
		assertFalse(saved[0].isEnabled());
	}

	@Test
	void resumeEnablesPersistsAndRecomputesTheNextRun() {
		CrawlJob[] saved = new CrawlJob[1];
		ApiJobController controller =
				controller(jobRepo(job("job-1", false, "0 0 2 * * *"), saved), deletionService(new boolean[1]));

		JobResponse response = controller.resume("job-1");

		assertTrue(response.enabled());
		assertNotNull(response.nextRunAt(), "next run should be computed for a cron job");
		assertNotNull(saved[0], "resume must persist the job");
		assertTrue(saved[0].isEnabled());
		assertNotNull(saved[0].getNextRunAt());
	}

	@Test
	void resumeLeavesTheNextRunUnsetForAManualJob() {
		CrawlJob[] saved = new CrawlJob[1];
		ApiJobController controller =
				controller(jobRepo(job("job-1", false, ""), saved), deletionService(new boolean[1]));

		JobResponse response = controller.resume("job-1");

		assertTrue(response.enabled());
		assertNull(response.nextRunAt());
		assertNotNull(saved[0], "resume must persist the job");
		assertNull(saved[0].getNextRunAt());
	}

	@Test
	void deleteReturns204AndDelegatesTheCascade() {
		boolean[] deleted = new boolean[1];
		ApiJobController controller =
				controller(jobRepo(job("job-1", false, ""), new CrawlJob[1]), deletionService(deleted));

		ResponseEntity<Void> response = controller.delete("job-1");

		assertEquals(204, response.getStatusCode().value());
		assertTrue(deleted[0]);
	}

	@Test
	void jobResponseCarriesTheActiveBatchIdWhileRunning() {
		ApiJobController controller = controller(
				jobRepo(job("job-1", true, ""), new CrawlJob[1]),
				deletionService(new boolean[1]),
				"batch-42");

		assertEquals("batch-42", controller.get("job-1").currentBatchId());
	}

	@Test
	void jobResponseHasNoBatchIdWhenIdle() {
		ApiJobController controller =
				controller(jobRepo(job("job-1", true, ""), new CrawlJob[1]), deletionService(new boolean[1]));

		assertNull(controller.get("job-1").currentBatchId());
	}

	private static ApiJobController controller(CrawlJobRepository jobRepo, JobDeletionService deletion) {
		return controller(jobRepo, deletion, null);
	}

	private static ApiJobController controller(
			CrawlJobRepository jobRepo, JobDeletionService deletion, String activeBatchId) {
		return new ApiJobController(jobRepo, null, crawlService(activeBatchId), null, new ObjectMapper(), deletion);
	}

	private static CrawlService crawlService(String activeBatchId) {
		return new CrawlService(null, null, null, null, null, null, null, null) {
			@Override
			public String getActiveBatchId(String jobId) {
				return activeBatchId;
			}
		};
	}

	private static JobDeletionService deletionService(boolean[] deletedFlag) {
		return new JobDeletionService(null, null, null, null, null) {
			@Override
			public void deleteJob(String jobId) {
				deletedFlag[0] = true;
			}
		};
	}

	private static CrawlJob job(String id, boolean enabled, String cron) {
		CrawlJob job = new CrawlJob();
		job.setId(id);
		job.setName("Job " + id);
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setCronExpression(cron);
		job.setEnabled(enabled);
		return job;
	}

	private static CrawlJobRepository jobRepo(CrawlJob job, CrawlJob[] saved) {
		return (CrawlJobRepository) Proxy.newProxyInstance(
				CrawlJobRepository.class.getClassLoader(),
				new Class<?>[] {CrawlJobRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findById" -> Optional.of(job);
					case "save" -> {
						saved[0] = (CrawlJob) args[0];
						yield args[0];
					}
					case "existsById" -> Boolean.TRUE;
					case "deleteById" -> null;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}

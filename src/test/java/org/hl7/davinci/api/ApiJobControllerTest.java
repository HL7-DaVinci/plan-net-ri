package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.model.JobResponse;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.service.JobAlreadyRunningException;
import org.hl7.davinci.api.service.JobDeletionService;
import org.hl7.davinci.api.web.ApiJobController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class ApiJobControllerTest {

	@Test
	void pauseDisablesAndPersistsTheJob() {
		CrawlJob[] saved = new CrawlJob[1];
		ApiJobController controller =
				controller(jobRepo(job("job-1", true, "0 0 2 * * *"), saved), deletionService(new boolean[1], false));

		JobResponse response = controller.pause("job-1");

		assertFalse(response.enabled());
		assertNotNull(saved[0], "pause must persist the job");
		assertFalse(saved[0].isEnabled());
	}

	@Test
	void resumeEnablesPersistsAndRecomputesTheNextRun() {
		CrawlJob[] saved = new CrawlJob[1];
		ApiJobController controller =
				controller(jobRepo(job("job-1", false, "0 0 2 * * *"), saved), deletionService(new boolean[1], false));

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
				controller(jobRepo(job("job-1", false, ""), saved), deletionService(new boolean[1], false));

		JobResponse response = controller.resume("job-1");

		assertTrue(response.enabled());
		assertNull(response.nextRunAt());
		assertNotNull(saved[0], "resume must persist the job");
		assertNull(saved[0].getNextRunAt());
	}

	@Test
	void deleteReturns409WhileACrawlIsRunning() {
		ApiJobController controller =
				controller(jobRepo(job("job-1", true, ""), new CrawlJob[1]), deletionService(new boolean[1], true));

		ResponseStatusException thrown =
				assertThrows(ResponseStatusException.class, () -> controller.delete("job-1"));

		assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
	}

	@Test
	void deleteReturns204AndDelegatesTheCascade() {
		boolean[] deleted = new boolean[1];
		ApiJobController controller =
				controller(jobRepo(job("job-1", false, ""), new CrawlJob[1]), deletionService(deleted, false));

		ResponseEntity<Void> response = controller.delete("job-1");

		assertEquals(204, response.getStatusCode().value());
		assertTrue(deleted[0]);
	}

	private static ApiJobController controller(CrawlJobRepository jobRepo, JobDeletionService deletion) {
		return new ApiJobController(jobRepo, null, null, null, new ObjectMapper(), deletion);
	}

	private static JobDeletionService deletionService(boolean[] deletedFlag, boolean running) {
		return new JobDeletionService(null, null, null, null) {
			@Override
			public void deleteJob(String jobId) {
				if (running) {
					throw new JobAlreadyRunningException(jobId);
				}
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

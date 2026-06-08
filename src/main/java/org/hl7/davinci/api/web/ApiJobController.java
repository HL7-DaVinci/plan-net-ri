package org.hl7.davinci.api.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.model.JobRequest;
import org.hl7.davinci.api.model.JobResponse;
import org.hl7.davinci.api.model.JobStatsResponse;
import org.hl7.davinci.api.model.RunPage;
import org.hl7.davinci.api.model.RunResponse;
import org.hl7.davinci.api.model.RunTriggerResponse;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.service.CrawlService;
import org.hl7.davinci.api.service.CronSupport;
import org.hl7.davinci.api.service.JobAlreadyRunningException;
import org.hl7.davinci.api.service.JobDeletionService;
import org.hl7.davinci.api.service.ServerScope;
import org.hl7.davinci.api.service.StatsService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** CRUD and trigger endpoints for crawl jobs. */
@RestController
@RequestMapping("/api")
public class ApiJobController {

	private final CrawlJobRepository jobRepo;
	private final CrawlRunRepository runRepo;
	private final CrawlService crawlService;
	private final StatsService statsService;
	private final ObjectMapper objectMapper;
	private final JobDeletionService jobDeletionService;

	public ApiJobController(
			CrawlJobRepository jobRepo,
			CrawlRunRepository runRepo,
			CrawlService crawlService,
			StatsService statsService,
			ObjectMapper objectMapper,
			JobDeletionService jobDeletionService) {
		this.jobRepo = jobRepo;
		this.runRepo = runRepo;
		this.crawlService = crawlService;
		this.statsService = statsService;
		this.objectMapper = objectMapper;
		this.jobDeletionService = jobDeletionService;
	}

	@GetMapping("/jobs")
	public List<JobResponse> list() {
		return jobRepo.findAll().stream().map(this::toResponse).toList();
	}

	@GetMapping("/jobs/{id}")
	public JobResponse get(@PathVariable("id") String id) {
		return toResponse(requireJob(id));
	}

	@PostMapping("/jobs")
	public ResponseEntity<JobResponse> create(@RequestBody JobRequest request) {
		validate(request);
		CrawlJob job = new CrawlJob();
		job.setId(UUID.randomUUID().toString());
		job.setCreatedAt(Instant.now());
		apply(job, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(jobRepo.save(job)));
	}

	@PutMapping("/jobs/{id}")
	public JobResponse update(@PathVariable("id") String id, @RequestBody JobRequest request) {
		validate(request);
		CrawlJob job = requireJob(id);
		apply(job, request);
		return toResponse(jobRepo.save(job));
	}

	@DeleteMapping("/jobs/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		try {
			jobDeletionService.deleteJob(id);
		} catch (JobAlreadyRunningException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
		}
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/jobs/{id}/run")
	public ResponseEntity<RunTriggerResponse> run(@PathVariable("id") String id) {
		CrawlJob job = requireJob(id);
		try {
			return ResponseEntity.accepted().body(new RunTriggerResponse(crawlService.triggerAsync(job)));
		} catch (JobAlreadyRunningException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
		}
	}

	@PostMapping("/jobs/{id}/pause")
	public JobResponse pause(@PathVariable("id") String id) {
		CrawlJob job = requireJob(id);
		job.setEnabled(false);
		return toResponse(jobRepo.save(job));
	}

	@PostMapping("/jobs/{id}/resume")
	public JobResponse resume(@PathVariable("id") String id) {
		CrawlJob job = requireJob(id);
		job.setEnabled(true);
		job.setNextRunAt(CronSupport.nextRun(job.getCronExpression()));
		return toResponse(jobRepo.save(job));
	}

	@GetMapping("/runs")
	public RunPage runs(
			@RequestParam("jobId") String jobId,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "25") int size) {
		int safeSize = Math.min(Math.max(size, 1), 200);
		Page<CrawlRun> result =
				runRepo.findByJobIdOrderByStartedAtDesc(jobId, PageRequest.of(Math.max(0, page), safeSize));
		return new RunPage(
				result.getContent().stream().map(this::toRunResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	@GetMapping("/jobs/{id}/stats")
	public JobStatsResponse stats(@PathVariable("id") String id) {
		requireJob(id);
		return statsService.computeStats(id);
	}

	private void apply(CrawlJob job, JobRequest request) {
		job.setName(request.name());
		job.setServers(writeServers(request.servers()));
		job.setStrategy(request.strategy());
		job.setCronExpression(request.cronExpression());
		job.setEnabled(request.enabled() == null || request.enabled());
		job.setNextRunAt(CronSupport.nextRun(request.cronExpression()));
	}

	private void validate(JobRequest request) {
		if (request == null
				|| request.strategy() == null
				|| request.servers() == null
				|| request.servers().isEmpty()) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "strategy and at least one server are required");
		}
		if (!CronSupport.isValid(request.cronExpression())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cron expression");
		}
	}

	private CrawlJob requireJob(String id) {
		return jobRepo
				.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
	}

	private String writeServers(List<ServerScope> servers) {
		try {
			return objectMapper.writeValueAsString(servers);
		} catch (JsonProcessingException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid servers");
		}
	}

	private List<ServerScope> readServers(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return List.of(objectMapper.readValue(json, ServerScope[].class));
		} catch (Exception e) {
			return List.of();
		}
	}

	private JobResponse toResponse(CrawlJob job) {
		return new JobResponse(
				job.getId(),
				job.getName(),
				readServers(job.getServers()),
				job.getStrategy(),
				job.getCronExpression(),
				job.isEnabled(),
				job.isRunning(),
				str(job.getLastRunAt()),
				str(job.getNextRunAt()),
				str(job.getCreatedAt()));
	}

	private RunResponse toRunResponse(CrawlRun run) {
		return new RunResponse(
				run.getId(),
				run.getJobId(),
				run.getBatchId(),
				run.getServerKey(),
				run.getServerLabel(),
				run.getMode() != null ? run.getMode().name() : null,
				str(run.getStartedAt()),
				run.getServerTimeAtStart(),
				run.getDurationMs(),
				run.getStatus() != null ? run.getStatus().name() : null,
				run.getAdded(),
				run.getUpdated(),
				run.getDeleted(),
				run.getRecords(),
				run.getBytes(),
				run.getRequests(),
				run.getPages(),
				run.getHistorySupported(),
				run.getError());
	}

	private static String str(Instant instant) {
		return instant != null ? instant.toString() : null;
	}
}

package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlMode;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.model.CrawlStepResponse;
import org.hl7.davinci.api.model.JobRequest;
import org.hl7.davinci.api.model.JobResponse;
import org.hl7.davinci.api.model.JobStatsResponse;
import org.hl7.davinci.api.model.ManifestJson;
import org.hl7.davinci.api.model.ManifestSummary;
import org.hl7.davinci.api.model.RunPage;
import org.hl7.davinci.api.repository.CrawlJobRepository;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.hl7.davinci.api.service.CrawlService;
import org.hl7.davinci.api.service.ManifestService;
import org.hl7.davinci.api.service.ServerScope;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full server and crawls its own /fhir endpoint, then verifies the served
 * manifest. Exercises the CRAWLER_PU wiring, SEARCH crawl, persistence, NDJSON
 * generation, and manifest rendering against the seeded Plan-Net data.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class, NicknameServiceConfig.class, RepositoryConfig.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:crawlerit",
			"api.enabled=false",
			"spring.ai.mcp.server.enabled=false",
			"hapi.fhir.fhir_version=r4",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class DirectoryCrawlerSelfCrawlIT {

	@LocalServerPort
	private int port;

	@Autowired
	private CrawlService crawlService;

	@Autowired
	private ManifestService manifestService;

	@Autowired
	private CrawlJobRepository jobRepo;

	@Autowired
	private ManifestRepository manifestRepo;

	@Autowired
	private CrawlResourceRepository resourceRepo;

	@Autowired
	private CrawlRunRepository runRepo;

	@Autowired
	private FhirContext fhirContext;

	@Autowired
	private TestRestTemplate rest;

	@Test
	void jobCrudOverHttp() {
		String origin = "http://localhost:" + port;
		String fhir = origin + "/fhir";
		JobRequest create = new JobRequest(
				"CRUD Job", List.of(new ServerScope(null, "self", fhir)), CrawlStrategy.SEARCH, null, true);

		ResponseEntity<JobResponse> created = rest.postForEntity(origin + "/api/jobs", create, JobResponse.class);
		assertEquals(HttpStatus.CREATED, created.getStatusCode());
		String id = created.getBody().id();
		assertNotNull(id);
		assertEquals("CRUD Job", created.getBody().name());
		assertEquals(CrawlStrategy.SEARCH, created.getBody().strategy());
		assertEquals(1, created.getBody().servers().size());

		ResponseEntity<JobResponse> fetched = rest.getForEntity(origin + "/api/jobs/" + id, JobResponse.class);
		assertEquals(HttpStatus.OK, fetched.getStatusCode());
		assertEquals("CRUD Job", fetched.getBody().name());

		JobRequest update = new JobRequest(
				"CRUD Job v2", create.servers(), CrawlStrategy.HISTORY, "0 0 3 * * *", false);
		rest.put(origin + "/api/jobs/" + id, update);

		JobResponse afterUpdate = rest.getForObject(origin + "/api/jobs/" + id, JobResponse.class);
		assertEquals("CRUD Job v2", afterUpdate.name());
		assertEquals(CrawlStrategy.HISTORY, afterUpdate.strategy());
		assertFalse(afterUpdate.enabled());
		assertNotNull(afterUpdate.nextRunAt());

		rest.delete(origin + "/api/jobs/" + id);
		assertEquals(
				HttpStatus.NOT_FOUND,
				rest.getForEntity(origin + "/api/jobs/" + id, String.class).getStatusCode());
	}

	@Test
	void selfCrawlProducesServedManifest() {
		String base = "http://localhost:" + port + "/fhir";

		CrawlJob job = new CrawlJob();
		job.setId("self-crawl");
		job.setName("Self crawl");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setEnabled(true);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverKey\":\"" + base + "\",\"serverLabel\":\"self\",\"url\":\"" + base + "\"}]");
		jobRepo.save(job);

		List<CrawlRun> runs = crawlService.crawlJob(job);

		assertEquals(1, runs.size());
		CrawlRun run = runs.get(0);
		assertEquals(RunStatus.COMPLETED, run.getStatus(), "crawl errored: " + run.getError());
		assertTrue(run.getRecords() > 0, "expected the seeded directory to yield resources");

		List<ManifestSummary> manifests = manifestService.listManifests();
		assertFalse(manifests.isEmpty(), "manifests should be listed");
		assertEquals(
				"Self crawl",
				manifests.stream()
						.filter(m -> "self-crawl".equals(m.jobId()))
						.findFirst()
						.orElseThrow()
						.jobName(),
				"manifest summary should include the job name");

		ManifestRecord record = manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc("self-crawl").get(0);
		ManifestJson manifest = manifestService.render(record, "http://example.test");

		assertFalse(manifest.requiresAccessToken());
		assertFalse(manifest.output().isEmpty(), "manifest should list per-type NDJSON files");

		long manifestCount = manifest.output().stream().mapToLong(ManifestJson.OutputEntry::count).sum();
		assertEquals(record.getTotalResources(), manifestCount, "NDJSON line counts must match the snapshot total");
		assertTrue(manifestCount > 0);

		for (ManifestJson.OutputEntry entry : manifest.output()) {
			assertTrue(entry.count() > 0, "each type file should have resources: " + entry.type());
			assertTrue(
					entry.url().startsWith("http://example.test/api/manifests/" + record.getId() + "/files/"),
					"url should point at the API file endpoint");
		}

		// Serve the manifest and a file over HTTP through the controller.
		String origin = "http://localhost:" + port;
		ResponseEntity<ManifestJson> served =
				rest.getForEntity(origin + "/api/manifests/" + record.getId() + "/manifest.json", ManifestJson.class);
		assertEquals(HttpStatus.OK, served.getStatusCode());
		assertFalse(served.getBody().output().isEmpty());
		assertNull(served.getBody().request(), "SEARCH should omit the deprecated request field");

		String fileUrl = served.getBody().output().get(0).url();
		assertTrue(fileUrl.startsWith(origin + "/api/manifests/" + record.getId() + "/files/"));
		ResponseEntity<String> ndjson = rest.getForEntity(fileUrl, String.class);
		assertEquals(HttpStatus.OK, ndjson.getStatusCode());
		assertFalse(ndjson.getBody().isBlank(), "served NDJSON should not be empty");

		ContentDisposition disposition = ndjson.getHeaders().getContentDisposition();
		assertTrue(disposition.isInline(), "NDJSON should be served inline, not as a generic attachment");
		assertEquals(
				fileUrl.substring(fileUrl.lastIndexOf('/') + 1),
				disposition.getFilename(),
				"download filename should match the URL file name (e.g. Endpoint.ndjson), not f.txt");

		JobStatsResponse stats =
				rest.getForObject(origin + "/api/jobs/self-crawl/stats", JobStatsResponse.class);
		assertTrue(stats.manifestCount() >= 1, "stats should report at least one manifest");
		assertTrue(stats.totalBuildMs() > 0, "stats should record manifest build time");
		assertTrue(stats.completedRuns() >= 1);
		assertTrue(stats.latestTotalResources() > 0);

		CrawlStepResponse[] steps = rest.getForObject(
				origin + "/api/crawl/" + run.getBatchId() + "/steps", CrawlStepResponse[].class);
		assertTrue(steps.length > 0, "play-by-play steps should be recorded");
		assertTrue(
				Arrays.stream(steps).anyMatch(s -> "SEARCH".equals(s.phase())),
				"should record a SEARCH step");
		assertTrue(
				Arrays.stream(steps).anyMatch(s -> "DONE".equals(s.phase())), "should record a DONE step");
	}

	@Test
	void incrementalCrawlDetectsAddsAndDeletes() {
		String base = "http://localhost:" + port + "/fhir";
		IGenericClient client = fhirContext.newRestfulGenericClient(base);

		CrawlJob job = new CrawlJob();
		job.setId("self-crawl-incr");
		job.setName("Self crawl incremental");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setEnabled(true);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverKey\":\"" + base + "\",\"serverLabel\":\"self\",\"url\":\"" + base + "\"}]");
		jobRepo.save(job);

		CrawlRun first = crawlService.crawlJob(job).get(0);
		assertEquals(CrawlMode.FULL, first.getMode());
		assertEquals(RunStatus.COMPLETED, first.getStatus());

		Organization org = new Organization();
		org.setName("Crawler IT Temp Org");
		org.setActive(true);
		String orgId = client.create().resource(org).execute().getId().getIdPart();
		String key = base + "|Organization/" + orgId;

		CrawlRun added = crawlService.crawlJob(job).get(0);
		assertEquals(CrawlMode.INCREMENTAL, added.getMode());
		assertEquals(RunStatus.COMPLETED, added.getStatus());
		assertEquals(Boolean.TRUE, added.getHistorySupported(), "server should support system _history");
		assertTrue(added.getAdded() >= 1, "the new resource should be detected as added");
		assertTrue(resourceRepo.findById(key).isPresent(), "new resource should be stored");
		assertTrue(
				added.getRecords() < first.getRecords(),
				"incremental run should fetch fewer than a full crawl ("
						+ added.getRecords()
						+ " vs "
						+ first.getRecords()
						+ ")");
		assertTrue(
				added.getRecords() < 100,
				"incremental run should fetch only the delta, was " + added.getRecords());

		ManifestRecord incrManifest =
				manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc("self-crawl-incr").get(0);
		assertNull(incrManifest.getRequest(), "SEARCH manifest should omit the deprecated request field");

		client.delete().resourceById("Organization", orgId).execute();

		CrawlRun deleted = crawlService.crawlJob(job).get(0);
		assertEquals(CrawlMode.INCREMENTAL, deleted.getMode());
		assertEquals(RunStatus.COMPLETED, deleted.getStatus());
		assertTrue(deleted.getDeleted() >= 1, "the deletion should be detected via system _history");
		assertTrue(resourceRepo.findById(key).isEmpty(), "deleted resource should be removed from the store");
	}

	@Test
	void incrementalHistoryDetectsAddsAndDeletes() {
		String base = "http://localhost:" + port + "/fhir";
		IGenericClient client = fhirContext.newRestfulGenericClient(base);

		CrawlJob job = new CrawlJob();
		job.setId("hist-incr");
		job.setName("History incremental");
		job.setStrategy(CrawlStrategy.HISTORY);
		job.setEnabled(true);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverKey\":\"" + base + "\",\"serverLabel\":\"self\",\"url\":\"" + base + "\"}]");
		jobRepo.save(job);

		CrawlRun first = crawlService.crawlJob(job).get(0);
		assertEquals(CrawlMode.FULL, first.getMode());
		assertEquals(RunStatus.COMPLETED, first.getStatus(), "full history errored: " + first.getError());

		Organization org = new Organization();
		org.setName("History IT Temp Org");
		org.setActive(true);
		String orgId = client.create().resource(org).execute().getId().getIdPart();
		String key = base + "|Organization/" + orgId;

		CrawlRun added = crawlService.crawlJob(job).get(0);
		assertEquals(CrawlMode.INCREMENTAL, added.getMode());
		assertEquals(RunStatus.COMPLETED, added.getStatus(), "incremental history errored: " + added.getError());
		assertTrue(added.getAdded() >= 1, "the new resource should be detected as added");
		assertTrue(resourceRepo.findById(key).isPresent(), "new resource should be stored");
		assertTrue(
				added.getRecords() < 100,
				"incremental history should process only the delta, was " + added.getRecords());

		client.delete().resourceById("Organization", orgId).execute();

		CrawlRun deleted = crawlService.crawlJob(job).get(0);
		assertEquals(CrawlMode.INCREMENTAL, deleted.getMode());
		assertEquals(RunStatus.COMPLETED, deleted.getStatus());
		assertTrue(deleted.getDeleted() >= 1, "the deletion should be detected via _history?_since");
		assertTrue(resourceRepo.findById(key).isEmpty(), "deleted resource should be removed from the store");
	}

	@Test
	void allStrategiesProduceEquivalentSnapshots() {
		String base = "http://localhost:" + port + "/fhir";

		Set<String> searchKeys = runFullStrategy("eq-search", CrawlStrategy.SEARCH, base);
		Set<String> historyKeys = runFullStrategy("eq-history", CrawlStrategy.HISTORY, base);
		Set<String> bulkKeys = runFullStrategy("eq-bulk", CrawlStrategy.BULK_EXPORT, base);

		assertFalse(searchKeys.isEmpty(), "SEARCH should find seeded resources");
		assertEquals(searchKeys, historyKeys, "HISTORY should fetch the same resource set as SEARCH");
		assertEquals(searchKeys, bulkKeys, "BULK_EXPORT should fetch the same resource set as SEARCH");
	}

	@Test
	void deleteManifestRemovesRowAndFiles() {
		String base = "http://localhost:" + port + "/fhir";

		CrawlJob job = new CrawlJob();
		job.setId("delete-manifest");
		job.setName("Delete manifest job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setEnabled(true);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverKey\":\"" + base + "\",\"serverLabel\":\"self\",\"url\":\"" + base + "\"}]");
		jobRepo.save(job);

		crawlService.crawlJob(job);

		ManifestRecord record = manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc("delete-manifest").get(0);
		Path storageDir = Path.of(record.getStorageDir());
		assertTrue(Files.isDirectory(storageDir), "snapshot directory should exist before delete");

		String origin = "http://localhost:" + port;
		String manifestBase = origin + "/api/manifests/" + record.getId();

		ResponseEntity<Void> deleted = rest.exchange(manifestBase, HttpMethod.DELETE, null, Void.class);
		assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());

		assertTrue(manifestRepo.findById(record.getId()).isEmpty(), "manifest row should be removed");
		assertFalse(Files.exists(storageDir), "snapshot files should be removed from disk");
		assertEquals(
				HttpStatus.NOT_FOUND,
				rest.getForEntity(manifestBase + "/manifest.json", String.class).getStatusCode());

		ResponseEntity<Void> again = rest.exchange(manifestBase, HttpMethod.DELETE, null, Void.class);
		assertEquals(HttpStatus.NOT_FOUND, again.getStatusCode(), "deleting a missing manifest should 404");
	}

	@Test
	void retentionKeepsNewestFivePerJob() {
		CrawlJob job = new CrawlJob();
		job.setId("retention-job");
		job.setName("Retention job");
		job.setStrategy(CrawlStrategy.SEARCH);
		job.setEnabled(true);
		job.setCreatedAt(Instant.now());
		job.setServers("[]");
		jobRepo.save(job);

		for (int i = 0; i < 8; i++) {
			manifestService.createManifest(job, "batch-" + i, null, List.of(), System.nanoTime());
		}

		assertEquals(
				5,
				manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc("retention-job").size(),
				"default retention should keep only the newest five manifests per job");
	}

	@Test
	void runHistoryIsPagedWithDefaultAndSelectableSize() {
		String jobId = "paging-job";
		Instant base = Instant.parse("2026-01-01T00:00:00Z");
		for (int i = 0; i < 7; i++) {
			CrawlRun run = new CrawlRun();
			run.setId("paging-run-" + i);
			run.setJobId(jobId);
			run.setBatchId("paging-batch-" + i);
			run.setServerKey("self");
			run.setServerLabel("self");
			run.setMode(CrawlMode.FULL);
			run.setStatus(RunStatus.COMPLETED);
			run.setStartedAt(base.plusSeconds(i));
			runRepo.save(run);
		}

		String origin = "http://localhost:" + port;

		// Explicit small page size: 3 per page over 7 runs -> 3 pages, newest first.
		RunPage first = rest.getForObject(origin + "/api/runs?jobId=" + jobId + "&page=0&size=3", RunPage.class);
		assertEquals(7, first.totalElements());
		assertEquals(3, first.totalPages());
		assertEquals(0, first.page());
		assertEquals(3, first.size());
		assertEquals(3, first.runs().size());
		assertEquals("paging-run-6", first.runs().get(0).id(), "runs should be ordered newest first");

		RunPage last = rest.getForObject(origin + "/api/runs?jobId=" + jobId + "&page=2&size=3", RunPage.class);
		assertEquals(2, last.page());
		assertEquals(1, last.runs().size(), "the third page should hold the remaining run");
		assertEquals("paging-run-0", last.runs().get(0).id());

		// Omitting size falls back to the default of 25, so all seven fit on one page.
		RunPage defaulted = rest.getForObject(origin + "/api/runs?jobId=" + jobId, RunPage.class);
		assertEquals(25, defaulted.size(), "default page size should be 25");
		assertEquals(1, defaulted.totalPages());
		assertEquals(7, defaulted.runs().size());
	}

	/** Run a full crawl and return the set of fetched resource identities (Type/id) for that server. */
	private Set<String> runFullStrategy(String jobId, CrawlStrategy strategy, String base) {
		CrawlJob job = new CrawlJob();
		job.setId(jobId);
		job.setName(jobId);
		job.setStrategy(strategy);
		job.setEnabled(true);
		job.setCreatedAt(Instant.now());
		job.setServers("[{\"serverKey\":\"" + base + "\",\"serverLabel\":\"self\",\"url\":\"" + base + "\"}]");
		jobRepo.save(job);

		CrawlRun run = crawlService.crawlJob(job).get(0);
		assertEquals(RunStatus.COMPLETED, run.getStatus(), strategy + " errored: " + run.getError());
		assertEquals(CrawlMode.FULL, run.getMode());

		// Capture the aggregate before the next strategy clears and reloads this server's rows.
		return resourceRepo.findByServerKey(base).stream()
				.map(r -> r.getResourceType() + "/" + r.getResId())
				.collect(Collectors.toSet());
	}
}

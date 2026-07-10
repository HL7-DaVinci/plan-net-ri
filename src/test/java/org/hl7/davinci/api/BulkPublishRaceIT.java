package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.hl7.davinci.common.PlanNetTypes;
import org.hl7.davinci.publish.BulkPublishManifestJson;
import org.hl7.davinci.publish.PublishProperties;
import org.hl7.davinci.publish.feed.WriteFrontier;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the $bulk-publish engine's consistency guarantee under concurrent writes: every published
 * snapshot's {id -> versionId} state, for every type, matches an independent oracle reconstructed
 * from point version-reads as of the snapshot's transactionTime. Covers bootstrap under load, a
 * deterministic stamp inversion (a superseded version must never publish once its successor has
 * committed and the frontier has passed it), a rolled-back write producing no spurious publish, and
 * an unreadable pinned version aborting a tick while the previous snapshot keeps serving.
 *
 * <p>Tests are ordered: the first leaves publishing enabled and the system quiescent, which the
 * later tests rely on.
 */
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class, NicknameServiceConfig.class, RepositoryConfig.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:bulkpublishraceit",
			"api.enabled=false",
			"publish.enabled=false",
			"publish.interval-ms=1500",
			"publish.frontier-grace-ms=250",
			"publish.overlap-ms=60000",
			"publish.export-page-size=25",
			"publish.storage-path=./target/bulkpublishraceit-data",
			"spring.ai.mcp.server.enabled=false",
			"hapi.fhir.fhir_version=r4",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class BulkPublishRaceIT {

	private static final int HISTORY_SCAN_CAP = 100_000;

	@LocalServerPort
	private int port;

	@Autowired
	private DaoRegistry daoRegistry;

	@Autowired
	private IFhirSystemDao<Bundle, Meta> systemDao;

	@Autowired
	private PublishProperties publishProperties;

	@Autowired
	private WriteFrontier writeFrontier;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private TestRestTemplate rest;

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ObjectMapper jsonMapper = new ObjectMapper();
	private final Deque<String> pendingLocationIds = new ArrayDeque<>();
	private final AtomicInteger updateCounter = new AtomicInteger();

	@Test
	@Order(1)
	void bootstrapUnderLoadThenDeterministicStampInversion() throws Exception {
		List<String> orgIds = seedOrganizations(50);
		seedLocations(20);

		AtomicBoolean running = new AtomicBoolean(true);
		List<Throwable> writerErrors = Collections.synchronizedList(new ArrayList<>());
		List<Thread> writers = startWriters(orgIds, running, writerErrors);

		publishProperties.setEnabled(true);

		BulkPublishManifestJson bootstrapManifest = awaitNewManifest(null, "the bootstrap publish under load");
		assertSnapshotMatchesOracle(bootstrapManifest);

		String lastTransactionTime = bootstrapManifest.transactionTime();
		for (int i = 0; i < 3; i++) {
			BulkPublishManifestJson manifest =
					awaitNewManifest(lastTransactionTime, "publish tick " + (i + 1) + " while writers keep running");
			assertSnapshotMatchesOracle(manifest);
			lastTransactionTime = manifest.transactionTime();
		}

		running.set(false);
		for (Thread writer : writers) {
			writer.join(TimeUnit.SECONDS.toMillis(10));
			assertFalse(writer.isAlive(), writer.getName() + " should have stopped");
		}
		assertTrue(writerErrors.isEmpty(), "writer threads must not throw: " + writerErrors);

		BulkPublishManifestJson quiescentManifest = awaitQuiescentManifest();
		assertSnapshotMatchesOracle(quiescentManifest);

		IFhirResourceDao<Organization> orgDao = daoRegistry.getResourceDao(Organization.class);
		Organization seedR = new Organization();
		seedR.setName("BulkPublishRaceIT-R-" + UUID.randomUUID());
		String rId = orgDao.create(seedR, new SystemRequestDetails()).getId().getIdPart();
		awaitManifestWithVersion("Organization", rId, 1);

		Organization v2 = new Organization();
		v2.setId(rId);
		v2.setName("BulkPublishRaceIT-R-v2-" + UUID.randomUUID());
		orgDao.update(v2, new SystemRequestDetails());

		Organization v3 = new Organization();
		v3.setId(rId);
		v3.setName("BulkPublishRaceIT-R-v3-" + UUID.randomUUID());
		orgDao.update(v3, new SystemRequestDetails());

		long v2UpdatedMillis = historyVersionUpdatedMillis("Organization", rId, 2);
		int rewrittenRows = rewriteHistoryVersionTimestampViaJdbc("Organization", rId, 3, v2UpdatedMillis - 500);
		assertEquals(1, rewrittenRows, "expected exactly one HFJ_RES_VER row rewritten for version 3");

		BulkPublishManifestJson inversionManifest = awaitManifestWithVersion("Organization", rId, 3);
		Long publishedVersion = versionOf(inversionManifest, "Organization", rId);
		assertEquals(Long.valueOf(3), publishedVersion, "R must publish as v3; a superseded v2 must never appear");
		assertSnapshotMatchesOracle(inversionManifest);
	}

	@Test
	@Order(2)
	void rollbackLeavesNoPinAndNoSpuriousPublish() throws Exception {
		awaitQuiescentManifest();
		String etagBefore = currentManifestEtag();

		Bundle bundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
		Organization invalid = new Organization();
		invalid.setName("BulkPublishRaceIT-rollback-" + UUID.randomUUID());
		invalid.setPartOf(new Reference("Organization/does-not-exist-" + UUID.randomUUID()));
		bundle.addEntry().setResource(invalid).getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("Organization");

		try {
			systemDao.transaction(new SystemRequestDetails(), bundle);
			fail("a transaction referencing a nonexistent Organization should have been rejected");
		} catch (BaseServerResponseException expected) {
			// referential integrity violation; the transaction must roll back cleanly
		}

		Awaitility.await("no pin left behind after the rolled-back transaction")
				.atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertEquals(0, writeFrontier.inFlightCount()));

		Thread.sleep(publishProperties.getIntervalMs() * 3);

		assertEquals(0, writeFrontier.inFlightCount());
		assertEquals(
				etagBefore, currentManifestEtag(), "no spurious publish should have occurred from a rolled-back transaction");
	}

	@Test
	@Order(3)
	void unreadablePinnedVersionFailsTickButPreviousSnapshotKeepsServing() throws Exception {
		awaitQuiescentManifest();
		ResponseEntity<String> beforeResponse = rest.getForEntity(bulkPublishUrl(), String.class);
		assertEquals(HttpStatus.OK, beforeResponse.getStatusCode());
		String etagBefore = beforeResponse.getHeaders().getFirst(HttpHeaders.ETAG);
		String bodyBefore = beforeResponse.getBody();
		assertNotNull(etagBefore);

		publishProperties.setEnabled(false);
		Thread.sleep(publishProperties.getIntervalMs());

		IFhirResourceDao<Organization> orgDao = daoRegistry.getResourceDao(Organization.class);
		Organization created = new Organization();
		created.setName("BulkPublishRaceIT-unreadable-" + UUID.randomUUID());
		String orgId = orgDao.create(created, new SystemRequestDetails()).getId().getIdPart();
		Organization updated = new Organization();
		updated.setId(orgId);
		updated.setName("BulkPublishRaceIT-unreadable-updated-" + UUID.randomUUID());
		orgDao.update(updated, new SystemRequestDetails());

		int corruptedRows = corruptHistoryVersionViaJdbc("Organization", orgId, 2);
		assertEquals(1, corruptedRows, "expected exactly one HFJ_RES_VER row corrupted for version 2");

		publishProperties.setEnabled(true);
		Thread.sleep(publishProperties.getIntervalMs() * 3);

		ResponseEntity<String> afterResponse = rest.getForEntity(bulkPublishUrl(), String.class);
		assertEquals(HttpStatus.OK, afterResponse.getStatusCode());
		assertEquals(
				etagBefore,
				afterResponse.getHeaders().getFirst(HttpHeaders.ETAG),
				"the manifest should keep serving the previous snapshot's ETag when a tick aborts");
		assertEquals(
				bodyBefore,
				afterResponse.getBody(),
				"the manifest body should be byte-identical to the previous snapshot when a tick aborts");

		ResponseEntity<String> metadataResponse =
				rest.getForEntity("http://localhost:" + port + "/fhir/metadata", String.class);
		assertEquals(
				HttpStatus.OK,
				metadataResponse.getStatusCode(),
				"the server should keep answering other requests normally after an aborted tick");
	}

	/**
	 * Independent correctness reference: for {@code type}, the live {id -> versionId} state as of
	 * {@code instantMillis}, built only from point version-reads (never from ChangeFeedReader or
	 * type-level history paging).
	 */
	@SuppressWarnings("rawtypes")
	private Map<String, Long> expectedStateAsOf(String type, long instantMillis) {
		IFhirResourceDao dao = daoRegistry.getResourceDao(type);
		SystemRequestDetails details = new SystemRequestDetails();

		List<IBaseResource> allHistory =
				dao.history(null, null, null, details).getResources(0, HISTORY_SCAN_CAP);
		assertTrue(
				allHistory.size() < HISTORY_SCAN_CAP,
				"type history scan hit its cap for " + type + ": " + allHistory.size());
		Set<String> ids = new HashSet<>();
		for (IBaseResource resource : allHistory) {
			ids.add(resource.getIdElement().getIdPart());
		}

		Map<String, Long> expected = new HashMap<>();
		for (String id : ids) {
			long asOfVersion = -1;
			boolean asOfDeleted = false;
			for (long version = 1; ; version++) {
				IBaseResource resource;
				try {
					resource = dao.read(new IdType(type, id, Long.toString(version)), details, true);
				} catch (ResourceNotFoundException e) {
					break;
				}
				long stamp = resource.getMeta().getLastUpdated().getTime();
				if (stamp <= instantMillis) {
					asOfVersion = version;
					asOfDeleted = ResourceMetadataKeyEnum.DELETED_AT.get(resource) != null;
				}
			}
			if (asOfVersion > 0 && !asOfDeleted) {
				expected.put(id, asOfVersion);
			}
		}
		return expected;
	}

	/** Fetches the manifest's files for {@code type} over HTTP and parses each NDJSON line's id/versionId. */
	private Map<String, Long> snapshotState(String type, BulkPublishManifestJson manifest)
			throws IOException, InterruptedException {
		Map<String, Long> state = new HashMap<>();
		Set<String> seenIds = new HashSet<>();
		for (BulkPublishManifestJson.OutputEntry entry : manifest.output()) {
			if (!type.equals(entry.type())) {
				continue;
			}
			for (String line : linesOf(entry.url())) {
				JsonNode node = jsonMapper.readTree(line);
				String id = node.get("id").asText();
				long version = Long.parseLong(node.get("meta").get("versionId").asText());
				assertTrue(seenIds.add(id), "id appeared twice in the " + type + " snapshot: " + id);
				state.put(id, version);
			}
		}
		return state;
	}

	private Long versionOf(BulkPublishManifestJson manifest, String type, String id)
			throws IOException, InterruptedException {
		for (BulkPublishManifestJson.OutputEntry entry : manifest.output()) {
			if (!type.equals(entry.type())) {
				continue;
			}
			for (String line : linesOf(entry.url())) {
				JsonNode node = jsonMapper.readTree(line);
				if (id.equals(node.get("id").asText())) {
					return node.get("meta").get("versionId").asLong();
				}
			}
		}
		return null;
	}

	private void assertSnapshotMatchesOracle(BulkPublishManifestJson manifest) throws IOException, InterruptedException {
		long instantMillis = Instant.parse(manifest.transactionTime()).toEpochMilli();
		StringBuilder diagnostics = new StringBuilder();
		boolean mismatch = false;
		for (String type : PlanNetTypes.TYPES) {
			Map<String, Long> expected = expectedStateAsOf(type, instantMillis);
			Map<String, Long> actual = snapshotState(type, manifest);
			if (!expected.equals(actual)) {
				mismatch = true;
				diagnostics.append(diffReport(type, expected, actual));
			}
		}
		if (mismatch) {
			fail("Snapshot at transactionTime=" + manifest.transactionTime() + " diverged from the oracle:\n" + diagnostics);
		}
	}

	private static String diffReport(String type, Map<String, Long> expected, Map<String, Long> actual) {
		Set<String> onlyInOracle = new TreeSet<>(expected.keySet());
		onlyInOracle.removeAll(actual.keySet());
		Set<String> onlyInSnapshot = new TreeSet<>(actual.keySet());
		onlyInSnapshot.removeAll(expected.keySet());
		List<String> versionMismatches = new ArrayList<>();
		for (Map.Entry<String, Long> entry : expected.entrySet()) {
			Long actualVersion = actual.get(entry.getKey());
			if (actualVersion != null && !actualVersion.equals(entry.getValue())) {
				versionMismatches.add(entry.getKey() + ": oracle=v" + entry.getValue() + " snapshot=v" + actualVersion);
			}
		}
		return type + ":\n"
				+ "  only in oracle: " + onlyInOracle + "\n"
				+ "  only in snapshot: " + onlyInSnapshot + "\n"
				+ "  version mismatches: " + versionMismatches + "\n";
	}

	private String bulkPublishUrl() {
		return "http://localhost:" + port + "/fhir/$bulk-publish";
	}

	private BulkPublishManifestJson currentManifest() {
		ResponseEntity<BulkPublishManifestJson> response =
				rest.getForEntity(bulkPublishUrl(), BulkPublishManifestJson.class);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		BulkPublishManifestJson manifest = response.getBody();
		assertNotNull(manifest);
		return manifest;
	}

	private String currentManifestEtag() {
		ResponseEntity<String> response = rest.getForEntity(bulkPublishUrl(), String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
		assertNotNull(etag);
		return etag;
	}

	private BulkPublishManifestJson awaitNewManifest(String previousTransactionTimeOrNull, String description) {
		AtomicReference<BulkPublishManifestJson> ref = new AtomicReference<>();
		Awaitility.await(description)
				.atMost(Duration.ofSeconds(60))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					BulkPublishManifestJson manifest = currentManifest();
					if (previousTransactionTimeOrNull != null) {
						assertNotEquals(previousTransactionTimeOrNull, manifest.transactionTime());
					}
					ref.set(manifest);
				});
		return ref.get();
	}

	private BulkPublishManifestJson awaitManifestWithVersion(String type, String id, long expectedVersion) {
		AtomicReference<BulkPublishManifestJson> ref = new AtomicReference<>();
		Awaitility.await("a snapshot with " + type + "/" + id + " at version " + expectedVersion)
				.atMost(Duration.ofSeconds(60))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					BulkPublishManifestJson manifest = currentManifest();
					assertEquals(Long.valueOf(expectedVersion), versionOf(manifest, type, id));
					ref.set(manifest);
				});
		return ref.get();
	}

	/** Waits until the manifest stops changing across two full publish intervals, then returns it. */
	private BulkPublishManifestJson awaitQuiescentManifest() throws InterruptedException {
		long intervalMs = publishProperties.getIntervalMs();
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(90).toMillis();
		BulkPublishManifestJson previous = currentManifest();
		while (System.currentTimeMillis() < deadline) {
			Thread.sleep(intervalMs * 2);
			BulkPublishManifestJson current = currentManifest();
			if (current.transactionTime().equals(previous.transactionTime())) {
				return current;
			}
			previous = current;
		}
		fail("manifest never reached a quiescent state within the deadline");
		return null;
	}

	private List<String> linesOf(String url) throws IOException, InterruptedException {
		byte[] bytes = downloadPlain(url);
		List<String> lines = new ArrayList<>();
		for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
			if (!line.isBlank()) {
				lines.add(line);
			}
		}
		return lines;
	}

	private byte[] downloadPlain(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, response.statusCode(), "failed to download " + url);
		return response.body();
	}

	private List<String> seedOrganizations(int count) {
		IFhirResourceDao<Organization> dao = daoRegistry.getResourceDao(Organization.class);
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Organization org = new Organization();
			org.setName("BulkPublishRaceIT-seed-org-" + i);
			ids.add(dao.create(org, new SystemRequestDetails()).getId().getIdPart());
		}
		return ids;
	}

	private void seedLocations(int count) {
		IFhirResourceDao<Location> dao = daoRegistry.getResourceDao(Location.class);
		for (int i = 0; i < count; i++) {
			Location location = new Location();
			location.setName("BulkPublishRaceIT-seed-location-" + i);
			dao.create(location, new SystemRequestDetails());
		}
	}

	private List<Thread> startWriters(List<String> orgIds, AtomicBoolean running, List<Throwable> errors) {
		List<Thread> threads = new ArrayList<>();
		threads.add(startWriterThread("race-updater", running, errors, 20, () -> updateRandomSeededOrganization(orgIds)));
		threads.add(startWriterThread("race-creator-deleter", running, errors, 50, this::createThenDeleteLocation));
		threads.add(startWriterThread("race-systemtx", running, errors, 100, this::runSystemTransaction));
		return threads;
	}

	private Thread startWriterThread(
			String name, AtomicBoolean running, List<Throwable> errors, long sleepMillis, Runnable action) {
		Thread thread = new Thread(
				() -> {
					while (running.get()) {
						try {
							action.run();
							Thread.sleep(sleepMillis);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						} catch (Throwable t) {
							errors.add(t);
						}
					}
				},
				name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private void updateRandomSeededOrganization(List<String> orgIds) {
		String id = orgIds.get(ThreadLocalRandom.current().nextInt(orgIds.size()));
		Organization org = new Organization();
		org.setId(id);
		org.setName("race-update-" + updateCounter.incrementAndGet());
		daoRegistry.getResourceDao(Organization.class).update(org, new SystemRequestDetails());
	}

	/** Only the creator-deleter thread ever touches this deque; a plain ArrayDeque is safe. */
	private void createThenDeleteLocation() {
		IFhirResourceDao<Location> dao = daoRegistry.getResourceDao(Location.class);
		Location location = new Location();
		location.setName("race-location-" + UUID.randomUUID());
		String newId = dao.create(location, new SystemRequestDetails()).getId().getIdPart();
		pendingLocationIds.addLast(newId);
		if (pendingLocationIds.size() > 5) {
			dao.delete(new IdType("Location", pendingLocationIds.pollFirst()), new SystemRequestDetails());
		}
	}

	private void runSystemTransaction() {
		Bundle bundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
		for (int i = 0; i < 2; i++) {
			Organization org = new Organization();
			org.setName("race-systemtx-" + UUID.randomUUID());
			bundle.addEntry().setResource(org).getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("Organization");
		}
		systemDao.transaction(new SystemRequestDetails(), bundle);
	}

	/**
	 * A plain DELETE of the HFJ_RES_VER row would make that version invisible to ChangeFeedReader's
	 * own history scan too, so the tick would silently pick the next surviving version as the winner
	 * instead of failing. Corrupting the row's content in place keeps it visible as the winner while
	 * making its body unreadable, which is what actually fails the merge's pinned read.
	 */
	private int corruptHistoryVersionViaJdbc(String resourceType, String fhirId, long version) throws SQLException {
		String sql = "UPDATE HFJ_RES_VER SET RES_TEXT_VC = 'CORRUPTED-BY-BulkPublishRaceIT', RES_TEXT = NULL "
				+ "WHERE RES_VER = ? AND RES_ID = (SELECT RES_ID FROM HFJ_RESOURCE WHERE FHIR_ID = ? AND RES_TYPE = ?)";
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, version);
			statement.setString(2, fhirId);
			statement.setString(3, resourceType);
			return statement.executeUpdate();
		}
	}

	@SuppressWarnings("rawtypes")
	private long historyVersionUpdatedMillis(String type, String id, long version) {
		IFhirResourceDao dao = daoRegistry.getResourceDao(type);
		IBaseResource resource = dao.read(new IdType(type, id, Long.toString(version)), new SystemRequestDetails(), true);
		return resource.getMeta().getLastUpdated().getTime();
	}

	/**
	 * Racing a live concurrent write against a resource updated within the same latched transaction
	 * bundle hits Hibernate's normal optimistic locking (HAPI resolves a bundle's target resources
	 * before its per-entry storage hooks fire, so a competing commit in between is correctly rejected,
	 * not silently absorbed). Rewriting an already-committed version's timestamp constructs the same
	 * inverted-stamp database state a slow concurrent writer would otherwise produce, so
	 * ChangeFeedReader's winner-by-version selection is proven against real HFJ_RES_VER rows.
	 */
	private int rewriteHistoryVersionTimestampViaJdbc(String resourceType, String fhirId, long version, long newUpdatedMillis)
			throws SQLException {
		String sql = "UPDATE HFJ_RES_VER SET RES_UPDATED = ? "
				+ "WHERE RES_VER = ? AND RES_ID = (SELECT RES_ID FROM HFJ_RESOURCE WHERE FHIR_ID = ? AND RES_TYPE = ?)";
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setTimestamp(1, new Timestamp(newUpdatedMillis));
			statement.setLong(2, version);
			statement.setString(3, fhirId);
			statement.setString(4, resourceType);
			return statement.executeUpdate();
		}
	}
}

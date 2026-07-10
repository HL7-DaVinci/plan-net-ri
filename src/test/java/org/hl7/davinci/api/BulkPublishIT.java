package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.awaitility.Awaitility;
import org.hl7.davinci.publish.PublishProperties;
import org.hl7.davinci.publish.BulkPublishManifestJson;
import org.hl7.davinci.publish.PublishService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full server with a fast publish interval and exercises the FHIR Bulk Data
 * $bulk-publish operation end to end: manifest shape, ETag/If-None-Match, gzip content
 * negotiation on output files, that a resource update produces a fresh snapshot while
 * the prior snapshot's files remain servable (grace period), and that the operation is
 * declared in the CapabilityStatement.
 *
 * <p>The endpoint is {@code GET /fhir/$bulk-publish}, served by
 * {@link org.hl7.davinci.provider.BulkPublishProvider}. Output files remain served from
 * {@code /api/publish/...}.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class, NicknameServiceConfig.class, RepositoryConfig.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:bulkpublishit",
			"api.enabled=false",
			"publish.enabled=true",
			"publish.interval-ms=2000",
			"publish.frontier-grace-ms=250",
			"publish.overlap-ms=60000",
			"publish.export-page-size=2",
			"publish.storage-path=./target/bulkpublishit-data",
			"spring.ai.mcp.server.enabled=false",
			"hapi.fhir.fhir_version=r4",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class BulkPublishIT {

	@LocalServerPort
	private int port;

	@Autowired
	private FhirContext fhirContext;

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private PublishProperties publishProperties;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void bulkPublishReflectsCreateThenUpdateWithGzipNegotiationAndConditionalGet() throws Exception {
		String fhirBase = "http://localhost:" + port + "/fhir";
		String bulkPublishUrl = fhirBase + "/$bulk-publish";
		IGenericClient client = fhirContext.newRestfulGenericClient(fhirBase);

		String marker = "BulkPublishIT-" + UUID.randomUUID();
		Organization org = new Organization();
		org.setName(marker);
		String orgId = client.create().resource(org).execute().getId().getIdPart();

		// Created alongside the Organization so it is untouched by the later Organization-only
		// update below, proving an unchanged type's file URL is reused rather than re-exported.
		String locationMarker = "BulkPublishIT-location-" + UUID.randomUUID();
		Location location = new Location();
		location.setName(locationMarker);
		client.create().resource(location).execute();

		AtomicReference<BulkPublishManifestJson> manifestRef = new AtomicReference<>();
		Awaitility.await("a published snapshot whose Organization and Location files include the new records")
				.atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					ResponseEntity<BulkPublishManifestJson> response =
							rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
					assertEquals(HttpStatus.OK, response.getStatusCode());
					BulkPublishManifestJson manifest = response.getBody();
					assertNotNull(manifest);
					String orgFileUrl = organizationFileUrl(manifest);
					assertNotNull(orgFileUrl, "manifest should include an Organization output entry");
					String ndjson = new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8);
					assertTrue(ndjson.contains(marker), "Organization ndjson should include the newly created org");
					String locationFileUrl = fileUrl(manifest, "Location");
					assertNotNull(locationFileUrl, "manifest should include a Location output entry");
					String locationNdjson = new String(downloadPlain(locationFileUrl), StandardCharsets.UTF_8);
					assertTrue(
							locationNdjson.contains(locationMarker), "Location ndjson should include the newly created location");
					manifestRef.set(manifest);
				});

		BulkPublishManifestJson firstManifest = manifestRef.get();
		assertEquals(PublishService.MANIFEST_TYPE, firstManifest.manifestType());
		assertNotNull(firstManifest.transactionTime());
		assertFalse(firstManifest.requiresAccessToken());
		assertEquals(
				Duration.ofMillis(publishProperties.getIntervalMs()).toString(),
				firstManifest.updateCadence());
		assertFalse(firstManifest.output().isEmpty());
		for (BulkPublishManifestJson.OutputEntry entry : firstManifest.output()) {
			assertTrue(entry.count() > 0, "each output entry should report a positive count: " + entry.type());
			assertTrue(entry.fileSize() > 0, "each output entry should report a positive fileSize: " + entry.type());
		}

		ResponseEntity<BulkPublishManifestJson> manifestResponse =
				rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
		assertEquals(HttpStatus.OK, manifestResponse.getStatusCode());
		assertTrue(
				manifestResponse.getHeaders().getContentType().toString().startsWith("application/json"),
				"manifest should be served as application/json");
		String etag = manifestResponse.getHeaders().getFirst(HttpHeaders.ETAG);
		assertNotNull(etag, "manifest response should carry an ETag");

		String orgFileUrl = organizationFileUrl(firstManifest);

		byte[] gzipDecoded = downloadGzip(orgFileUrl);
		assertTrue(
				new String(gzipDecoded, StandardCharsets.UTF_8).contains(marker),
				"gzip-negotiated download should decompress to the same NDJSON content");

		byte[] plain = downloadPlain(orgFileUrl);
		assertTrue(new String(plain, StandardCharsets.UTF_8).contains(marker));

		RequestEntity<Void> conditionalGet =
				RequestEntity.get(URI.create(bulkPublishUrl)).header(HttpHeaders.IF_NONE_MATCH, etag).build();
		ResponseEntity<String> conditionalResponse = rest.exchange(conditionalGet, String.class);
		assertEquals(HttpStatus.NOT_MODIFIED, conditionalResponse.getStatusCode());

		String updatedMarker = marker + "-updated";
		Organization updated = new Organization();
		updated.setId(orgId);
		updated.setName(updatedMarker);
		client.update().resource(updated).execute();

		AtomicReference<BulkPublishManifestJson> secondManifestRef = new AtomicReference<>();
		Awaitility.await("a new snapshot reflecting the updated Organization")
				.atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					ResponseEntity<BulkPublishManifestJson> response =
							rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
					assertEquals(HttpStatus.OK, response.getStatusCode());
					BulkPublishManifestJson manifest = response.getBody();
					assertNotNull(manifest);
					String url = organizationFileUrl(manifest);
					assertNotNull(url);
					assertFalse(url.equals(orgFileUrl), "a new snapshot should not reuse the previous file URL");
					String ndjson = new String(downloadPlain(url), StandardCharsets.UTF_8);
					assertTrue(ndjson.contains(updatedMarker), "Organization ndjson should reflect the update");
					secondManifestRef.set(manifest);
				});

		BulkPublishManifestJson secondManifest = secondManifestRef.get();
		assertTrue(
				Instant.parse(secondManifest.transactionTime()).isAfter(Instant.parse(firstManifest.transactionTime())),
				"transactionTime should advance with the new snapshot");

		ResponseEntity<byte[]> priorSnapshotStillServed = rest.getForEntity(orgFileUrl, byte[].class);
		assertEquals(
				HttpStatus.OK,
				priorSnapshotStillServed.getStatusCode(),
				"the previous snapshot's file URL should remain servable during its retention grace period");

		// Only the Organization changed between the two manifests; Location's file URL must be
		// reused byte-identical rather than re-exported into the new snapshot.
		String locationFileUrl = fileUrl(firstManifest, "Location");
		assertEquals(
				locationFileUrl,
				fileUrl(secondManifest, "Location"),
				"an untouched type's file URL must not change across a publish that only touched another type");
		ResponseEntity<byte[]> reusedLocationFile = rest.getForEntity(locationFileUrl, byte[].class);
		assertEquals(
				HttpStatus.OK,
				reusedLocationFile.getStatusCode(),
				"the reused type's file must still be servable at its unchanged URL");

		CapabilityStatement capabilityStatement =
				client.capabilities().ofType(CapabilityStatement.class).execute();
		boolean declaresBulkPublish = capabilityStatement.getRest().stream()
				.flatMap(restComponent -> restComponent.getOperation().stream())
				.anyMatch(op -> op.getName() != null && op.getName().contains("bulk-publish"));
		assertTrue(declaresBulkPublish, "CapabilityStatement should declare the bulk-publish operation");
	}

	/**
	 * With publish.export-page-size=2, 6 Organizations created in a single transaction land at the
	 * exact same lastUpdated instant (HAPI stamps one transaction time for every resource in a
	 * transaction Bundle), forcing a same-instant cluster spanning multiple pages. Every one must
	 * still appear in the export, and exactly once, proving the watermark/pinned-drain paging never
	 * drops or duplicates a row.
	 */
	@Test
	void clusteredOrganizationsSpanningMultiplePagesAllAppearExactlyOnce() throws Exception {
		String fhirBase = "http://localhost:" + port + "/fhir";
		String bulkPublishUrl = fhirBase + "/$bulk-publish";
		IGenericClient client = fhirContext.newRestfulGenericClient(fhirBase);

		String marker = "BulkPublishIT-cluster-" + UUID.randomUUID();
		List<String> names = new ArrayList<>();
		Bundle transaction = new Bundle().setType(Bundle.BundleType.TRANSACTION);
		for (int i = 0; i < 6; i++) {
			String name = marker + "-" + i;
			names.add(name);
			Organization org = new Organization();
			org.setName(name);
			transaction
					.addEntry()
					.setResource(org)
					.getRequest()
					.setMethod(Bundle.HTTPVerb.POST)
					.setUrl("Organization");
		}
		client.transaction().withBundle(transaction).execute();

		AtomicReference<String> ndjsonRef = new AtomicReference<>();
		Awaitility.await("a published snapshot whose Organization file includes every clustered record")
				.atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					ResponseEntity<BulkPublishManifestJson> response =
							rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
					assertEquals(HttpStatus.OK, response.getStatusCode());
					String orgFileUrl = organizationFileUrl(response.getBody());
					assertNotNull(orgFileUrl, "manifest should include an Organization output entry");
					String ndjson = new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8);
					for (String name : names) {
						assertTrue(ndjson.contains(name), "export should include " + name);
					}
					ndjsonRef.set(ndjson);
				});

		String ndjson = ndjsonRef.get();
		for (String name : names) {
			assertEquals(
					ndjson.indexOf(name),
					ndjson.lastIndexOf(name),
					name + " should appear exactly once in the export");
		}
	}

	/** History-based change detection sees deletes; the deleted Organization must drop out of the next snapshot. */
	@Test
	void deletedOrganizationIsAbsentFromTheNextSnapshot() throws Exception {
		String fhirBase = "http://localhost:" + port + "/fhir";
		String bulkPublishUrl = fhirBase + "/$bulk-publish";
		IGenericClient client = fhirContext.newRestfulGenericClient(fhirBase);

		String marker = "BulkPublishIT-delete-" + UUID.randomUUID();
		Organization org = new Organization();
		org.setName(marker);
		String orgId = client.create().resource(org).execute().getId().getIdPart();

		Awaitility.await("a published snapshot whose Organization file includes the record to be deleted")
				.atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					ResponseEntity<BulkPublishManifestJson> response =
							rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
					assertEquals(HttpStatus.OK, response.getStatusCode());
					String orgFileUrl = organizationFileUrl(response.getBody());
					assertNotNull(orgFileUrl);
					String ndjson = new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8);
					assertTrue(ndjson.contains(marker));
				});

		client.delete().resourceById("Organization", orgId).execute();

		Awaitility.await("a new snapshot that no longer contains the deleted Organization")
				.atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					ResponseEntity<BulkPublishManifestJson> response =
							rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
					assertEquals(HttpStatus.OK, response.getStatusCode());
					String orgFileUrl = organizationFileUrl(response.getBody());
					assertNotNull(orgFileUrl, "other retained Organizations keep the file present");
					String ndjson = new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8);
					assertFalse(ndjson.contains(marker), "deleted Organization should be absent from the new export");
				});
	}

	/** Accept header content negotiation on file downloads: only application/fhir+ndjson (or a wildcard) is served. */
	@Test
	void fileDownloadRejectsUnsupportedAcceptHeader() throws Exception {
		String fhirBase = "http://localhost:" + port + "/fhir";
		String bulkPublishUrl = fhirBase + "/$bulk-publish";
		IGenericClient client = fhirContext.newRestfulGenericClient(fhirBase);

		String marker = "BulkPublishIT-accept-" + UUID.randomUUID();
		Organization org = new Organization();
		org.setName(marker);
		client.create().resource(org).execute();

		AtomicReference<String> orgFileUrlRef = new AtomicReference<>();
		Awaitility.await("a published snapshot whose Organization file includes the new record")
				.atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					ResponseEntity<BulkPublishManifestJson> response =
							rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
					assertEquals(HttpStatus.OK, response.getStatusCode());
					String orgFileUrl = organizationFileUrl(response.getBody());
					assertNotNull(orgFileUrl);
					String ndjson = new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8);
					assertTrue(ndjson.contains(marker));
					orgFileUrlRef.set(orgFileUrl);
				});
		String orgFileUrl = orgFileUrlRef.get();

		HttpResponse<byte[]> rejected = httpClient.send(
				HttpRequest.newBuilder(URI.create(orgFileUrl))
						.header("Accept", "text/csv")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(406, rejected.statusCode());
		assertTrue(
				rejected.headers().firstValue("Content-Type").orElse("").startsWith("application/fhir+json"),
				"an unsupported Accept header should get an OperationOutcome as application/fhir+json");

		HttpResponse<byte[]> accepted = httpClient.send(
				HttpRequest.newBuilder(URI.create(orgFileUrl))
						.header("Accept", "application/fhir+ndjson")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, accepted.statusCode());
	}

	/**
	 * The manifest ETag is a content hash: it stays identical across repeated GETs with no writes in
	 * between, and differs when the request's Host changes, since the manifest embeds request-derived
	 * absolute file URLs and public-base-url is unset in this test's properties.
	 */
	@Test
	void manifestEtagIsAStableContentHashThatVariesByHost() throws Exception {
		String fhirBase = "http://localhost:" + port + "/fhir";
		String bulkPublishUrl = fhirBase + "/$bulk-publish";
		IGenericClient client = fhirContext.newRestfulGenericClient(fhirBase);

		String marker = "BulkPublishIT-etag-" + UUID.randomUUID();
		Organization org = new Organization();
		org.setName(marker);
		client.create().resource(org).execute();

		Awaitility.await("a published snapshot including the new Organization")
				.atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(300))
				.ignoreExceptions()
				.untilAsserted(() -> {
					ResponseEntity<BulkPublishManifestJson> response =
							rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
					assertEquals(HttpStatus.OK, response.getStatusCode());
					String orgFileUrl = organizationFileUrl(response.getBody());
					assertNotNull(orgFileUrl);
					String ndjson = new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8);
					assertTrue(ndjson.contains(marker));
				});

		ResponseEntity<byte[]> first = rest.getForEntity(bulkPublishUrl, byte[].class);
		ResponseEntity<byte[]> second = rest.getForEntity(bulkPublishUrl, byte[].class);
		String firstEtag = first.getHeaders().getFirst(HttpHeaders.ETAG);
		String secondEtag = second.getHeaders().getFirst(HttpHeaders.ETAG);
		assertNotNull(firstEtag);
		assertEquals(firstEtag, secondEtag, "repeated GETs with no writes between them should carry the same ETag");
		assertArrayEquals(
				first.getBody(), second.getBody(), "repeated GETs with no writes between them should be byte-identical");
		assertTrue(
				firstEtag.matches("\"[0-9a-f]{64}\""),
				"ETag should be a quoted 64-character lowercase hex SHA-256 digest: " + firstEtag);

		String loopbackUrl = "http://127.0.0.1:" + port + "/fhir/$bulk-publish";
		ResponseEntity<byte[]> viaLoopback = rest.getForEntity(loopbackUrl, byte[].class);
		String loopbackEtag = viaLoopback.getHeaders().getFirst(HttpHeaders.ETAG);
		assertNotNull(loopbackEtag);
		assertFalse(
				firstEtag.equals(loopbackEtag),
				"a different Host should embed a different absolute base URL and yield a different ETag");
	}

	private static String organizationFileUrl(BulkPublishManifestJson manifest) {
		return fileUrl(manifest, "Organization");
	}

	private static String fileUrl(BulkPublishManifestJson manifest, String type) {
		return manifest.output().stream()
				.filter(entry -> type.equals(entry.type()))
				.map(BulkPublishManifestJson.OutputEntry::url)
				.findFirst()
				.orElse(null);
	}

	/** GET without an Accept-Encoding header; the server should decompress and serve plain NDJSON. */
	private byte[] downloadPlain(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, response.statusCode());
		assertTrue(
				response.headers().firstValue("Content-Type").orElse("").startsWith("application/fhir+ndjson"));
		assertTrue(
				response.headers().firstValue("Content-Encoding").isEmpty(),
				"a request without Accept-Encoding should not receive a Content-Encoding header");
		assertTrue(
				response.headers().allValues("Vary").stream().anyMatch(v -> v.contains("Accept-Encoding")),
				"file responses should declare Vary: Accept-Encoding");
		return response.body();
	}

	/**
	 * GET with an explicit Accept-Encoding: gzip header, using a raw byte-array body handler so the
	 * JDK client neither adds its own Accept-Encoding nor auto-decompresses; the raw gzip bytes are
	 * decompressed here so the test controls and observes the Content-Encoding negotiation itself.
	 */
	private byte[] downloadGzip(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, response.statusCode());
		assertTrue(
				response.headers().firstValue("Content-Type").orElse("").startsWith("application/fhir+ndjson"));
		assertEquals(
				"gzip",
				response.headers().firstValue("Content-Encoding").orElse(null),
				"requesting gzip should receive a Content-Encoding: gzip response");
		assertTrue(
				response.headers().allValues("Vary").stream().anyMatch(v -> v.contains("Accept-Encoding")),
				"file responses should declare Vary: Accept-Encoding");
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
			return gzip.readAllBytes();
		}
	}
}

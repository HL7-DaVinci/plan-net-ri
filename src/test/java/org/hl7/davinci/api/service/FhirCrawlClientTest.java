package org.hl7.davinci.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.common.PlanNetTypes;
import org.junit.jupiter.api.Test;

class FhirCrawlClientTest {

	/** Concurrency 1 so every existing test exercises the exact-today inline task path. */
	private static ApiProperties props() {
		ApiProperties props = new ApiProperties();
		props.setCrawlConcurrency(1);
		return props;
	}

	private static org.hl7.fhir.r4.model.CapabilityStatement capabilityDeclaring(String... types) {
		org.hl7.fhir.r4.model.CapabilityStatement cs = new org.hl7.fhir.r4.model.CapabilityStatement();
		org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent rest = cs.addRest();
		rest.setMode(org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode.SERVER);
		for (String type : types) {
			rest.addResource().setType(type);
		}
		return cs;
	}

	@Test
	void reportUndeclaredTypesListsMissingPlanNetTypes() {
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.reportUndeclaredTypes(capabilityDeclaring("Organization", "Practitioner"), steps::add);

		assertEquals(1, steps.size());
		assertTrue(steps.get(0).message().contains("Endpoint"));
		assertTrue(steps.get(0).message().contains("attempting anyway"));
		assertFalse(steps.get(0).message().contains("Organization,"));
	}

	@Test
	void reportUndeclaredTypesSilentWhenAllDeclared() {
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.reportUndeclaredTypes(
				capabilityDeclaring(PlanNetTypes.TYPES.toArray(new String[0])), steps::add);

		assertTrue(steps.isEmpty());
	}

	@Test
	void reportUndeclaredTypesSilentWhenStatementDeclaresNothing() {
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.reportUndeclaredTypes(capabilityDeclaring(), steps::add);

		assertTrue(steps.isEmpty());
	}

	@Test
	void bulkHttpClientFollowsRedirects() {
		// A Content-Location poll URL can 301 http->https behind a TLS-terminating proxy.
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props());
		assertEquals(HttpClient.Redirect.NORMAL, client.newBulkHttpClient().followRedirects());
	}

	@Test
	void bulkExportFailsWhenOutputFileDownloadIsNotSuccessful() {
		FakeHttpClient http = new FakeHttpClient(List.of(
				response(202, "", "Content-Location", "http://example.test/export-status"),
				response(
						200,
						"{\"output\":[{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization.ndjson\"}]}"),
				response(410, stream("expired"))));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		IllegalStateException error = assertThrows(
				IllegalStateException.class,
				() -> client.bulkExport("http://example.test/fhir", "server", null, steps::add, batch -> {}));

		assertTrue(error.getMessage().contains("HTTP 410"));
		assertTrue(
				steps.stream().anyMatch(step -> Integer.valueOf(410).equals(step.status())),
				"failed output download should be recorded with its actual status");
		assertTrue(
				steps.stream().anyMatch(step -> "expired".equals(step.errorBody())),
				"the raw error response body should be retained on the failure step");
	}

	@Test
	void historyDeletionsAreMarkedByTheDeleteMethodPerSpec() {
		org.hl7.fhir.r4.model.Bundle bundle = new org.hl7.fhir.r4.model.Bundle();
		// Spec-marked deletion: request.method DELETE and no resource body.
		bundle.addEntry()
				.getRequest()
				.setMethod(org.hl7.fhir.r4.model.Bundle.HTTPVerb.DELETE)
				.setUrl("Organization/gone/_history/2");
		// An update entry without a resource body is not a deletion.
		bundle.addEntry()
				.getRequest()
				.setMethod(org.hl7.fhir.r4.model.Bundle.HTTPVerb.PUT)
				.setUrl("Organization/still-here/_history/3");
		// The normative method marker wins even over a contradictory resource body.
		org.hl7.fhir.r4.model.Organization conflicted = new org.hl7.fhir.r4.model.Organization();
		conflicted.setId("conflicted");
		bundle.addEntry()
				.setResource(conflicted)
				.getRequest()
				.setMethod(org.hl7.fhir.r4.model.Bundle.HTTPVerb.DELETE)
				.setUrl("Organization/conflicted/_history/4");
		// Lenient fallback for servers that omit request entirely.
		bundle.addEntry().setFullUrl("http://example.test/fhir/Organization/bare");

		List<DeletionEntry> out = new ArrayList<>();
		FhirCrawlClient.extractDeletions(bundle, out);

		assertEquals(
				List.of(
						new DeletionEntry("Organization", "gone"),
						new DeletionEntry("Organization", "conflicted"),
						new DeletionEntry("Organization", "bare")),
				out);
	}

	@Test
	void bulkExportRetriesATransientTimeoutAndSucceeds() {
		FakeHttpClient http = new FakeHttpClient(List.of(
				new HttpTimeoutException("request timed out"),
				response(202, "", "Content-Location", "http://example.test/export-status"),
				response(
						200,
						"{\"output\":[{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization.ndjson\"}]}"),
				response(200, stream("{\"resourceType\":\"Organization\",\"id\":\"a\"}"))));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.SearchResult result = client.bulkExport("http://example.test/fhir", "server", null, steps::add, batch -> {});

		assertEquals(1, result.records(), "the export should succeed after the retried kick-off");
		assertTrue(
				steps.stream().anyMatch(step -> step.message().contains("retrying in")),
				"the retry should be narrated into the play-by-play");
	}

	@Test
	void bulkExportSendsSinceOnTheKickoff() {
		FakeHttpClient http = new FakeHttpClient(List.of(
				response(202, "", "Content-Location", "http://example.test/export-status"),
				response(
						200,
						"{\"output\":[{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization.ndjson\"}]}"),
				response(200, stream("{\"resourceType\":\"Organization\",\"id\":\"a\"}"))));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.SearchResult result = client.bulkExport(
				"http://example.test/fhir", "server", "2026-07-01T00:00:00Z", steps::add, batch -> {});

		assertEquals(1, result.records());
		assertTrue(
				http.requestedUrls.get(0).contains("&_since=2026-07-01T00%3A00%3A00Z"),
				"the kick-off should carry the URL-encoded _since parameter: " + http.requestedUrls.get(0));
	}

	@Test
	void sinceKickoffRejectionThrowsSinceUnsupported() {
		FakeHttpClient http = new FakeHttpClient(List.of(response(400, "unsupported parameter _since")));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		assertThrows(
				SinceUnsupportedException.class,
				() -> client.bulkExport(
						"http://example.test/fhir", "server", "2026-07-01T00:00:00Z", steps::add, batch -> {}));
		assertTrue(
				steps.stream().anyMatch(step -> Integer.valueOf(400).equals(step.status())),
				"the rejected kick-off should be recorded with its status");
	}

	@Test
	void bareKickoffRejectionThrowsStrategyUnsupported() {
		FakeHttpClient http = new FakeHttpClient(List.of(response(404, "unknown operation $export")));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		assertThrows(
				StrategyUnsupportedException.class,
				() -> client.bulkExport("http://example.test/fhir", "server", null, steps::add, batch -> {}));
		assertTrue(
				steps.stream().anyMatch(step -> "unknown operation $export".equals(step.errorBody())),
				"the rejection body should be retained for the UI's Response viewer");
	}

	@Test
	void kickoffRetriesAServerErrorAndSucceeds() {
		// A 500 arrives as a normal response from the raw HttpClient, not as an exception; it
		// must still get the same bounded retries as the HAPI-client call sites.
		FakeHttpClient http = new FakeHttpClient(List.of(
				response(500, "transient blip"),
				response(202, "", "Content-Location", "http://example.test/export-status"),
				response(
						200,
						"{\"output\":[{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization.ndjson\"}]}"),
				response(200, stream("{\"resourceType\":\"Organization\",\"id\":\"a\"}"))));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.SearchResult result =
				client.bulkExport("http://example.test/fhir", "server", null, steps::add, batch -> {});

		assertEquals(1, result.records(), "the export should succeed after the retried kick-off");
		assertTrue(
				steps.stream().anyMatch(step -> step.message().contains("retrying in")),
				"the retry should be narrated into the play-by-play");
	}

	@Test
	void persistentKickoffServerErrorSignalsUnsupportedAfterRetries() {
		// Some servers answer an unknown operation with 500 rather than 404, so a 5xx that
		// survives every retry is classified as unsupported: an AUTO run falls back to search
		// instead of failing outright. A 5xx is never read as a _since rejection either.
		FakeHttpClient http = new FakeHttpClient(List.of(
				response(500, "Internal Server Error"),
				response(500, "Internal Server Error"),
				response(500, "Internal Server Error")));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		assertThrows(
				StrategyUnsupportedException.class,
				() -> client.bulkExport(
						"http://example.test/fhir", "server", "2026-07-01T00:00:00Z", steps::add, batch -> {}));

		assertEquals(3, http.requestedUrls.size(), "the kick-off should be retried up to the attempt limit");
		assertTrue(
				steps.stream().anyMatch(step -> "Internal Server Error".equals(step.errorBody())),
				"the final response body should be retained for the UI's Response viewer");
	}

	@Test
	void persistentKickoffRateLimitFailsTheRunInsteadOfSignalingUnsupported() {
		// A 429 means the server supports the operation but wants less load; falling back to
		// search would send more requests, so the run fails rather than demoting the server.
		FakeHttpClient http = new FakeHttpClient(
				List.of(response(429, "slow down"), response(429, "slow down"), response(429, "slow down")));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		assertThrows(
				IllegalStateException.class,
				() -> client.bulkExport("http://example.test/fhir", "server", null, steps::add, batch -> {}));
	}

	@Test
	void probePartitionedSearchStopsAtTheFirstViableType() {
		List<String> queries = new ArrayList<>();
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				queries.add(url);
				if (url.startsWith("Endpoint")) {
					throw new InvalidRequestException("HTTP 400: unsupported");
				}
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		assertTrue(client.probePartitionedSearch("http://example.test/fhir", steps::add));
		assertEquals(2, queries.size(), "the probe should stop at the first type that succeeds");
		assertEquals("HealthcareService?_count=1&_total=none&_sort=_lastUpdated&_lastUpdated=ge1970-01-01", queries.get(1));
		assertTrue(
				steps.stream().anyMatch(step -> Integer.valueOf(400).equals(step.status())),
				"the rejected probe should be recorded with its status");
		assertTrue(
				steps.stream().anyMatch(step -> "Probe query succeeded".equals(step.message())),
				"the viable probe should persist a request step");
	}

	@Test
	void probeReturnsFalseOnlyWhenEveryTypeIsRejected() {
		List<String> queries = new ArrayList<>();
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				queries.add(url);
				throw new InvalidRequestException("HTTP 400: unsupported");
			}
		};

		assertFalse(client.probePlainSearch("http://example.test/fhir", ev -> {}));
		assertEquals(PlanNetTypes.TYPES.size(), queries.size(), "every type should have been probed before giving up");
	}

	@Test
	void probePropagatesATransientFailureInsteadOfFallingBack() {
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				throw new UnclassifiedServerFailureException(503, "Service Unavailable");
			}
		};

		assertThrows(
				UnclassifiedServerFailureException.class,
				() -> client.probePlainSearch("http://example.test/fhir", ev -> {}));
	}

	@Test
	void retriesTransientFailuresAndFailsFastOnPermanentOnes() throws Exception {
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props());
		List<StepEvent> steps = new ArrayList<>();
		int[] transientCalls = {0};

		String result = client.withRetry("SEARCH", "test call", steps::add, () -> {
			if (transientCalls[0]++ == 0) {
				throw new FhirClientConnectionException(new SocketTimeoutException("Read timed out"));
			}
			return "ok";
		});

		assertEquals("ok", result);
		assertEquals(2, transientCalls[0], "the transient failure should be retried");
		assertTrue(steps.get(0).message().contains("retrying in"));

		int[] permanentCalls = {0};
		assertThrows(InvalidRequestException.class, () -> client.withRetry("SEARCH", "test call", steps::add, () -> {
			permanentCalls[0]++;
			throw new InvalidRequestException("HTTP 400");
		}));
		assertEquals(1, permanentCalls[0], "a 4xx must fail fast without retries");

		assertTrue(
				FhirCrawlClient.isTransient(new InternalErrorException("HTTP 500")),
				"a 500 is retried; servers use it for internal timeouts that resolve on a later attempt");
		assertTrue(FhirCrawlClient.isTransient(new UnclassifiedServerFailureException(503, "Service Unavailable")));
	}

	@Test
	void rateLimitingIsRetriedHonoringRetryAfter() throws Exception {
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props());
		List<StepEvent> steps = new ArrayList<>();
		UnclassifiedServerFailureException tooMany =
				new UnclassifiedServerFailureException(429, "Too Many Requests");
		tooMany.addResponseHeader("Retry-After", "1");
		int[] calls = {0};

		String result = client.withRetry("SEARCH", "test call", steps::add, () -> {
			if (calls[0]++ == 0) {
				throw tooMany;
			}
			return "ok";
		});

		assertEquals("ok", result);
		assertEquals(2, calls[0], "a 429 should be retried rather than failing the run");
		assertTrue(steps.get(0).message().contains("retrying in 1s"), "the Retry-After wait should be narrated");
	}

	@Test
	void retryDelayPrefersTheRetryAfterHeaderClamped() {
		UnclassifiedServerFailureException tooMany =
				new UnclassifiedServerFailureException(429, "Too Many Requests");
		tooMany.addResponseHeader("Retry-After", "7");
		assertEquals(7_000L, FhirCrawlClient.retryDelayMs(tooMany, 2_000L));

		UnclassifiedServerFailureException greedy =
				new UnclassifiedServerFailureException(429, "Too Many Requests");
		greedy.addResponseHeader("Retry-After", "3600");
		assertEquals(60_000L, FhirCrawlClient.retryDelayMs(greedy, 2_000L), "hostile waits are clamped");

		assertEquals(
				2_000L,
				FhirCrawlClient.retryDelayMs(new UnclassifiedServerFailureException(429, "no header"), 2_000L),
				"a missing header falls back to the fixed backoff");
	}

	@Test
	void maxLastUpdatedReturnsTheLatestEntry() {
		org.hl7.fhir.r4.model.Bundle bundle = new org.hl7.fhir.r4.model.Bundle();
		addOrg(bundle, "a", "2026-06-20T10:00:00.000+00:00");
		org.hl7.fhir.r4.model.Organization latest = addOrg(bundle, "b", "2026-06-23T08:30:00.000+00:00");
		addOrg(bundle, "c", "2026-06-22T23:59:59.000+00:00");
		String expected = latest.getMeta().getLastUpdatedElement().getValueAsString();
		assertEquals(
				expected,
				FhirCrawlClient.maxLastUpdated(bundle, "Organization").value(),
				"returns the newest entry's lastUpdated");
	}

	@Test
	void maxLastUpdatedIgnoresOutcomeAndForeignTypeEntries() {
		org.hl7.fhir.r4.model.Bundle bundle = new org.hl7.fhir.r4.model.Bundle();
		org.hl7.fhir.r4.model.Organization match = addOrg(bundle, "a", "2024-12-18T03:41:54.997+00:00");
		addLocation(bundle, "elsewhere", "2026-01-01T00:00:00.000Z");
		withOutcomeEntry(bundle, "2026-07-10T18:13:06.035+00:00");

		FhirCrawlClient.LastUpdated max = FhirCrawlClient.maxLastUpdated(bundle, "Organization");

		assertEquals(
				match.getMeta().getLastUpdatedElement().getValueAsString(),
				max.value(),
				"only match entries of the requested type may feed the watermark");
	}

	@Test
	void lastUpdatedQueryEncodesThePlusOffsetSoItIsNotReadAsASpace() {
		String query = FhirCrawlClient.lastUpdatedQuery("Organization", 500, "2024-06-03T22:08:54.557+00:00");
		assertTrue(
				query.contains("_lastUpdated=ge2024-06-03T22%3A08%3A54.557%2B00%3A00"),
				"the + offset is percent-encoded so the server does not read it as a space");
		assertTrue(
				query.contains("_total=none") && query.contains("_sort=_lastUpdated"),
				"keeps the keyset query params");
	}

	@Test
	void maxLastUpdatedIsNullWhenNoEntriesCarryLastUpdated() {
		assertNull(FhirCrawlClient.maxLastUpdated(new org.hl7.fhir.r4.model.Bundle(), "Organization"));
	}

	@Test
	void searchByLastUpdatedKeepsPagingWhenTheServerCapsPagesBelowTheRequestedCount() {
		// Requested _count is 10, but the server returns only 2 rows per page (its own cap). The data
		// spans five instants, so the keyset watermark must keep advancing; a short page is NOT the end.
		java.util.Deque<org.hl7.fhir.r4.model.Bundle> searchPages = new java.util.ArrayDeque<>(java.util.List.of(
				pageOf(false, "a", "2026-01-01T00:00:00.000Z", "b", "2026-01-02T00:00:00.000Z"),
				pageOf(false, "b", "2026-01-02T00:00:00.000Z", "c", "2026-01-03T00:00:00.000Z"),
				pageOf(false, "c", "2026-01-03T00:00:00.000Z", "d", "2026-01-04T00:00:00.000Z"),
				pageOf(false, "d", "2026-01-04T00:00:00.000Z", "e", "2026-01-05T00:00:00.000Z"),
				pageOf(false, "e", "2026-01-05T00:00:00.000Z")));

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (!url.startsWith("Organization") || searchPages.isEmpty()) {
					return new org.hl7.fhir.r4.model.Bundle();
				}
				return searchPages.poll();
			}
		};

		java.util.Set<String> keys = new java.util.HashSet<>();
		client.searchTypesByLastUpdated(
				"http://x", "s", 10, null, s -> {}, batch -> batch.forEach(r -> keys.add(r.key())));

		assertEquals(
				java.util.Set.of(
						"s|Organization/a",
						"s|Organization/b",
						"s|Organization/c",
						"s|Organization/d",
						"s|Organization/e"),
				keys,
				"all five resources are captured even though every page is shorter than the requested _count");
	}

	@Test
	void searchByLastUpdatedIsNotPoisonedByOperationOutcomeEntries() {
		// A server that caps _count appends a search.mode=outcome OperationOutcome to every page,
		// stamped at render time. Taking that stamp as the watermark leaps past the remaining data;
		// an outcome-only page must read as empty instead of paging forever.
		String outcomeStamp = "2026-07-10T18:13:06.035+00:00";
		java.util.List<org.hl7.fhir.r4.model.Organization> data = new java.util.ArrayList<>();
		org.hl7.fhir.r4.model.Bundle pool = new org.hl7.fhir.r4.model.Bundle();
		for (int i = 1; i <= 5; i++) {
			data.add(addOrg(pool, "org" + i, "2026-01-0" + i + "T00:00:00.000Z"));
		}
		int[] calls = {0};

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (++calls[0] > 40) {
					throw new IllegalStateException("watermark poisoned, paging forever: " + url);
				}
				org.hl7.fhir.r4.model.Bundle page = new org.hl7.fhir.r4.model.Bundle();
				if (url.startsWith("Organization")) {
					String ge = null;
					for (String param : url.substring(url.indexOf('?') + 1).split("&")) {
						if (param.startsWith("_lastUpdated=ge")) {
							ge = java.net.URLDecoder.decode(
									param.substring("_lastUpdated=ge".length()),
									java.nio.charset.StandardCharsets.UTF_8);
						}
					}
					int served = 0;
					for (org.hl7.fhir.r4.model.Organization org : data) {
						String stamp = org.getMeta().getLastUpdatedElement().getValueAsString();
						if ((ge == null || stamp.compareTo(ge) >= 0) && served < 2) {
							addOrg(page, org.getIdElement().getIdPart(), stamp);
							served++;
						}
					}
				}
				return withOutcomeEntry(page, outcomeStamp);
			}
		};

		java.util.Set<String> keys = new java.util.HashSet<>();
		client.searchTypesByLastUpdated(
				"http://x", "s", 10, null, s -> {}, batch -> batch.forEach(r -> keys.add(r.key())));

		assertEquals(
				java.util.Set.of(
						"s|Organization/org1",
						"s|Organization/org2",
						"s|Organization/org3",
						"s|Organization/org4",
						"s|Organization/org5"),
				keys,
				"all resources are captured and the OperationOutcome is neither followed nor persisted");
	}

	@Test
	void partitionedWindowEndsLoudlyWhenTheServerReturnsEntriesPastTheWindowBound() {
		// A window bounded lt 2026-01-10 gets back a resource stamped 2026-02-01: the server did not
		// honor the filter. Advancing the watermark from that stamp would invert the window; the run
		// must surface the violation and end the window instead.
		java.util.List<StepEvent> steps = new java.util.ArrayList<>();

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (!url.startsWith("Organization")) {
					return new org.hl7.fhir.r4.model.Bundle();
				}
				if (url.contains("_summary=count")) {
					org.hl7.fhir.r4.model.Bundle census = new org.hl7.fhir.r4.model.Bundle();
					census.setTotal(10);
					return census;
				}
				if (url.contains("_count=1&")) {
					return pageOf(false, "seed", "2026-01-01T00:00:00.000Z");
				}
				return pageOf(false, "a", "2026-01-02T00:00:00.000Z", "x", "2026-02-01T00:00:00.000Z");
			}
		};

		java.util.Set<String> keys = new java.util.HashSet<>();
		client.searchTypesPartitioned(
				"http://x",
				"s",
				10,
				null,
				new FhirCrawlClient.ServerTime("2026-01-10T00:00:00.000Z", "date-header"),
				steps::add,
				batch -> batch.forEach(r -> keys.add(r.key())));

		assertTrue(
				steps.stream().anyMatch(s -> s.message() != null && s.message().contains("did not honor")),
				"the filter violation must be surfaced as a step");
		assertTrue(
				keys.containsAll(java.util.Set.of("s|Organization/a", "s|Organization/x")),
				"the page's resources are still captured");
	}

	@Test
	void partitionedTypePersistsOneRollupInsteadOfPerWindowSteps() {
		java.util.List<StepEvent> steps = new java.util.ArrayList<>();
		int[] censusCalls = {0};

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (!url.substring(0, url.indexOf('?')).equals("Organization")) {
					return countBundle(0);
				}
				if (url.contains("_summary=count")) {
					// The total is over the window cap, so the range is sliced into two windows.
					org.hl7.fhir.r4.model.Bundle census = new org.hl7.fhir.r4.model.Bundle();
					census.setTotal(++censusCalls[0] == 1 ? 60_000 : 100);
					return census;
				}
				if (url.contains("_count=1&")) {
					return pageOf(false, "seed", "2026-01-01T00:00:00.000Z");
				}
				String ge = null;
				String lt = null;
				for (String param : url.substring(url.indexOf('?') + 1).split("&")) {
					if (param.startsWith("_lastUpdated=ge")) {
						ge = java.net.URLDecoder.decode(
								param.substring("_lastUpdated=ge".length()), java.nio.charset.StandardCharsets.UTF_8);
					}
					if (param.startsWith("_lastUpdated=lt")) {
						lt = java.net.URLDecoder.decode(
								param.substring("_lastUpdated=lt".length()), java.nio.charset.StandardCharsets.UTF_8);
					}
				}
				org.hl7.fhir.r4.model.Bundle page = new org.hl7.fhir.r4.model.Bundle();
				for (String[] org : java.util.List.of(
						new String[] {"a", "2026-01-02T00:00:00.000Z"},
						new String[] {"b", "2026-01-06T00:00:00.000Z"})) {
					if ((ge == null || org[1].compareTo(ge) >= 0) && (lt == null || org[1].compareTo(lt) < 0)) {
						addOrg(page, org[0], org[1]);
					}
				}
				return page;
			}
		};

		java.util.Set<String> keys = new java.util.HashSet<>();
		client.searchTypesPartitioned(
				"http://x",
				"s",
				10,
				null,
				new FhirCrawlClient.ServerTime("2026-01-09T00:00:00.000Z", "date-header"),
				steps::add,
				batch -> batch.forEach(r -> keys.add(r.key())));

		java.util.List<StepEvent> persisted = steps.stream()
				.filter(s -> !s.progress() && s.message() != null && s.message().startsWith("Searched Organization"))
				.toList();
		assertEquals(1, persisted.size(), "a multi-window type persists one rollup, not one step per window");
		assertTrue(persisted.get(0).message().contains("across 2 windows"), persisted.get(0).message());
		assertTrue(
				keys.containsAll(java.util.Set.of("s|Organization/a", "s|Organization/b")),
				"both windows' resources are captured");
	}

	@Test
	void searchByLastUpdatedTerminatesWhenOneInstantIsRenderedWithDifferentStrings() {
		// a, b, c all share one instant but the server renders it two ways (Z vs +00:00). Comparing
		// verbatim strings would oscillate the watermark forever; comparing parsed instants must settle.
		org.hl7.fhir.r4.model.Bundle clusterHead = pageOf(true, "a", "2026-01-01T00:00:00.000Z");
		addOrg(clusterHead, "b", "2026-01-01T00:00:00.000Z");
		int[] searchCalls = {0};

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (++searchCalls[0] > 20) {
					throw new IllegalStateException("watermark is oscillating: " + url);
				}
				return clusterHead; // every ge{instant} query re-returns the same first-page cluster
			}

			@Override
			org.hl7.fhir.r4.model.Bundle loadNextPage(
					ca.uhn.fhir.rest.client.api.IGenericClient c, org.hl7.fhir.r4.model.Bundle bundle) {
				return pageOf(false, "c", "2026-01-01T00:00:00+00:00"); // same instant, different rendering
			}
		};

		java.util.Set<String> keys = new java.util.HashSet<>();
		client.searchTypesByLastUpdated("http://x", "s", 2, null, s -> {}, batch -> batch.forEach(r -> keys.add(r.key())));

		assertTrue(
				keys.containsAll(java.util.Set.of("s|Organization/a", "s|Organization/b", "s|Organization/c")),
				"all three same-instant resources captured without looping");
	}

	@Test
	void searchByLastUpdatedPagesThroughAClusterLargerThanThePageSize() {
		// Three resources share instant t1 (a cluster larger than pageSize 2), then one at t2. The keyset
		// strategy must page through the cluster via link[next] rather than dropping c and d.
		String t1 = "2026-01-01T00:00:00.000Z";
		String t2 = "2026-01-02T00:00:00.000Z";
		java.util.Deque<org.hl7.fhir.r4.model.Bundle> searchPages = new java.util.ArrayDeque<>(java.util.List.of(
				pageOf(false, "a", t1, "b", t1), // first ge query: full page at t1
				pageOf(true, "a", t1, "b", t1), // re-anchored ge{t1}: same cluster, carries a next link
				pageOf(false, "d", t2))); // re-anchored ge{t2}: short page, exhausted
		java.util.Deque<org.hl7.fhir.r4.model.Bundle> nextPages = new java.util.ArrayDeque<>(
				java.util.List.of(pageOf(false, "c", t1, "d", t2))); // link[next] of the cluster: rest of t1 plus t2

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (!url.startsWith("Organization") || searchPages.isEmpty()) {
					return new org.hl7.fhir.r4.model.Bundle();
				}
				return searchPages.poll();
			}

			@Override
			org.hl7.fhir.r4.model.Bundle loadNextPage(
					ca.uhn.fhir.rest.client.api.IGenericClient c, org.hl7.fhir.r4.model.Bundle bundle) {
				return nextPages.poll();
			}
		};

		java.util.Set<String> keys = new java.util.HashSet<>();
		client.searchTypesByLastUpdated("http://x", "s", 2, null, s -> {}, batch -> batch.forEach(r -> keys.add(r.key())));

		assertTrue(keys.contains("s|Organization/a") && keys.contains("s|Organization/b"), "first page captured");
		assertTrue(keys.contains("s|Organization/c"), "the cluster member past the first page is captured, not dropped");
		assertTrue(keys.contains("s|Organization/d"), "the resource after the cluster is captured");
	}

	@Test
	void perTypeCountersAreSummedAcrossAllTypes() {
		org.hl7.fhir.r4.model.Bundle orgBundle = new org.hl7.fhir.r4.model.Bundle();
		org.hl7.fhir.r4.model.Organization orgA = addOrg(orgBundle, "a", "2026-01-01T00:00:00.000Z");
		org.hl7.fhir.r4.model.Organization orgB = addOrg(orgBundle, "b", "2026-01-01T00:00:01.000Z");
		org.hl7.fhir.r4.model.Bundle locationBundle = new org.hl7.fhir.r4.model.Bundle();
		org.hl7.fhir.r4.model.Location locC = addLocation(locationBundle, "c", "2026-01-01T00:00:00.000Z");
		ca.uhn.fhir.parser.IParser parser = FhirContext.forR4().newJsonParser();
		long expectedBytes = parser.encodeResourceToString(orgA).length()
				+ parser.encodeResourceToString(orgB).length()
				+ parser.encodeResourceToString(locC).length();

		java.util.Map<String, java.util.Deque<org.hl7.fhir.r4.model.Bundle>> queues = new java.util.HashMap<>();
		queues.put("Organization", new java.util.ArrayDeque<>(java.util.List.of(orgBundle)));
		queues.put("Location", new java.util.ArrayDeque<>(java.util.List.of(locationBundle)));

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				String type = url.substring(0, url.indexOf('?'));
				java.util.Deque<org.hl7.fhir.r4.model.Bundle> queue = queues.get(type);
				return queue == null || queue.isEmpty() ? new org.hl7.fhir.r4.model.Bundle() : queue.poll();
			}
		};

		FhirCrawlClient.SearchResult result =
				client.searchTypesByLastUpdated("http://x", "s", 10, null, ev -> {}, batch -> {});

		assertEquals(3, result.records(), "records should sum across both active types");
		assertEquals(2, result.requests(), "one request per active type (the empty requery ends each without counting)");
		assertEquals(2, result.pages(), "one page per active type");
		assertEquals(expectedBytes, result.bytes(), "bytes should sum to exactly the per-type totals");
	}

	@Test
	void perTypeFailureDoesNotAbortSiblingTypesAtConcurrency2() {
		org.hl7.fhir.r4.model.Bundle locationPage = new org.hl7.fhir.r4.model.Bundle();
		addLocation(locationPage, "loc-a", "2026-01-01T00:00:00.000Z");
		addLocation(locationPage, "loc-b", "2026-01-01T00:00:01.000Z");
		java.util.Deque<org.hl7.fhir.r4.model.Bundle> locationQueue = new java.util.ArrayDeque<>(java.util.List.of(locationPage));

		ApiProperties props = props();
		props.setCrawlConcurrency(2);
		List<StepEvent> steps = java.util.Collections.synchronizedList(new ArrayList<>());
		java.util.Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				String type = url.substring(0, url.indexOf('?'));
				if (type.equals("Organization")) {
					throw new ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException("not found");
				}
				if (type.equals("Location")) {
					return locationQueue.isEmpty() ? new org.hl7.fhir.r4.model.Bundle() : locationQueue.poll();
				}
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};

		client.searchTypesByLastUpdated(
				"http://x", "s", 10, null, steps::add, batch -> batch.forEach(r -> keys.add(r.key())));

		assertTrue(
				keys.containsAll(java.util.Set.of("s|Location/loc-a", "s|Location/loc-b")),
				"the succeeding type's resources should all arrive despite the sibling type's failure");
		assertTrue(
				steps.stream()
						.anyMatch(s -> "SEARCH".equals(s.phase())
								&& Integer.valueOf(404).equals(s.status())
								&& s.message() != null
								&& s.message().contains("Organization")),
				"a failure step for the failing type should exist");
	}

	@Test
	void rateGatePausesSiblingTypesAfterA429() {
		java.util.concurrent.atomic.AtomicInteger orgCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger locationCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicLong orgFailureNanos = new java.util.concurrent.atomic.AtomicLong();
		java.util.concurrent.atomic.AtomicLong locationSecondCallNanos = new java.util.concurrent.atomic.AtomicLong();
		java.util.concurrent.CountDownLatch orgFailed = new java.util.concurrent.CountDownLatch(1);
		org.hl7.fhir.r4.model.Bundle locationPage1 = new org.hl7.fhir.r4.model.Bundle();
		addLocation(locationPage1, "loc-a", "2026-01-01T00:00:00.000Z");

		ApiProperties props = props();
		props.setCrawlConcurrency(2);
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				String type = url.substring(0, url.indexOf('?'));
				if (type.equals("Organization")) {
					if (orgCalls.incrementAndGet() == 1) {
						orgFailureNanos.set(System.nanoTime());
						UnclassifiedServerFailureException tooMany =
								new UnclassifiedServerFailureException(429, "Too Many Requests");
						tooMany.addResponseHeader("Retry-After", "1");
						orgFailed.countDown();
						throw tooMany;
					}
					return new org.hl7.fhir.r4.model.Bundle();
				}
				if (type.equals("Location")) {
					if (locationCalls.incrementAndGet() == 1) {
						// Hold page 1 until the sibling's 429 has actually paused the shared gate, so
						// the follow-up request deterministically meets an active pause instead of
						// racing its registration.
						await(orgFailed);
						while (!gateFor("http://x").isPaused()) {
							Thread.onSpinWait();
						}
						return locationPage1;
					}
					locationSecondCallNanos.set(System.nanoTime());
					return new org.hl7.fhir.r4.model.Bundle();
				}
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};

		client.searchTypesByLastUpdated("http://x", "s", 10, null, ev -> {}, batch -> {});

		assertTrue(orgFailureNanos.get() > 0, "the 429 should have fired");
		assertTrue(locationSecondCallNanos.get() > 0, "Location should have made a second request");
		long observedPauseMs = (locationSecondCallNanos.get() - orgFailureNanos.get()) / 1_000_000;
		assertTrue(
				observedPauseMs >= 500,
				"Location's next request should be held back by the shared rate gate (observed " + observedPauseMs
						+ "ms)");
	}

	@Test
	void transientFailureDoesNotPauseSiblingTypes() {
		// Inverse of rateGatePausesSiblingTypesAfterA429: a 500 is one chain's problem, not a
		// server-wide rate-limit signal, so it must not hold back a sibling type's next request.
		java.util.concurrent.atomic.AtomicInteger orgCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger locationCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicLong orgFailureNanos = new java.util.concurrent.atomic.AtomicLong();
		java.util.concurrent.atomic.AtomicLong locationSecondCallNanos = new java.util.concurrent.atomic.AtomicLong();
		java.util.concurrent.CountDownLatch orgFailed = new java.util.concurrent.CountDownLatch(1);
		org.hl7.fhir.r4.model.Bundle locationPage1 = new org.hl7.fhir.r4.model.Bundle();
		addLocation(locationPage1, "loc-a", "2026-01-01T00:00:00.000Z");

		ApiProperties props = props();
		props.setCrawlConcurrency(2);
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				String type = url.substring(0, url.indexOf('?'));
				if (type.equals("Organization")) {
					if (orgCalls.incrementAndGet() == 1) {
						orgFailureNanos.set(System.nanoTime());
						orgFailed.countDown();
						throw new InternalErrorException("HTTP 500 Internal Server Error");
					}
					return new org.hl7.fhir.r4.model.Bundle();
				}
				if (type.equals("Location")) {
					if (locationCalls.incrementAndGet() == 1) {
						return locationPage1;
					}
					// Wait for the 500 to be registered so this second request is guaranteed to race
					// the (absent) pause rather than possibly slipping in before it would be set.
					await(orgFailed);
					locationSecondCallNanos.set(System.nanoTime());
					return new org.hl7.fhir.r4.model.Bundle();
				}
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};

		client.searchTypesByLastUpdated("http://x", "s", 10, null, ev -> {}, batch -> {});

		assertTrue(orgFailureNanos.get() > 0, "the transient failure should have fired");
		assertTrue(locationSecondCallNanos.get() > 0, "Location should have made a second request");
		long observedGapMs = (locationSecondCallNanos.get() - orgFailureNanos.get()) / 1_000_000;
		assertTrue(
				observedGapMs < 500,
				"a transient 500 on one type must not pause a sibling type (observed " + observedGapMs + "ms)");
	}

	@Test
	void rateGateRegistryIsSharedPerNormalizedServerAndSeparatePerServer() {
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props());

		RateGate gateA1 = client.gateFor("http://a.example/fhir");
		RateGate gateA2 = client.gateFor("http://a.example/fhir/");
		RateGate gateB = client.gateFor("http://b.example/fhir");

		assertSame(gateA1, gateA2, "the same normalized server should reuse one gate");
		assertNotSame(gateA1, gateB, "different servers should get separate gates");
	}

	@Test
	void inFlightRequestsAreCappedAtTheConfiguredConcurrency() {
		java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();

		ApiProperties props = props();
		props.setCrawlConcurrency(4);
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				int current = inFlight.incrementAndGet();
				maxInFlight.accumulateAndGet(current, Math::max);
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					inFlight.decrementAndGet();
				}
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};

		long serialEstimateMs = PlanNetTypes.TYPES.size() * 50L;
		long start = System.nanoTime();
		client.searchTypesByLastUpdated("http://latency.test", "s", 10, null, ev -> {}, batch -> {});
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertEquals(4, maxInFlight.get(), "in-flight requests should be capped at the configured concurrency");
		assertTrue(
				elapsedMs < serialEstimateMs * 0.6,
				"parallel wall-clock (" + elapsedMs + "ms) should be well under the serial sum (" + serialEstimateMs
						+ "ms)");
	}

	@Test
	void taskEmittedStepsCarryTheTypeAsTrack() {
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};

		List<StepEvent> steps = new ArrayList<>();
		client.searchTypesByLastUpdated("http://x", "s", 10, null, steps::add, batch -> {});

		assertTrue(
				steps.stream().anyMatch(s -> "Organization".equals(s.track())),
				"task-emitted steps should carry the type as track");
		assertTrue(
				steps.stream().anyMatch(s -> "Location".equals(s.track())),
				"every type's task should stamp its own track, not a shared one");
	}

	@Test
	void cancellationPropagatesDirectlyRatherThanBeingWrapped() {
		ApiProperties props = props();
		props.setCrawlConcurrency(2);
		org.hl7.fhir.r4.model.Bundle page = new org.hl7.fhir.r4.model.Bundle();
		addOrg(page, "a", "2026-01-01T00:00:00.000Z");

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				return page;
			}
		};

		assertThrows(
				java.util.concurrent.CancellationException.class,
				() -> client.searchTypesByLastUpdated("http://x", "s", 10, null, ev -> {}, batch -> {
					throw new java.util.concurrent.CancellationException();
				}));
	}

	@Test
	void pipeliningOverlapsTheNextPageFetchWithTheCurrentPagesProcessing() {
		// Page 1 is exactly one emitter batch, so its flush (and this test's slow sink) fires
		// synchronously at the very end of processing it. The next page's fetch is submitted
		// before that processing starts; if it is truly prefetched on a background thread, its
		// start timestamp will be well before the sink is ever entered, proving the overlap.
		int emitBatchSize = 1000;
		java.time.Instant base = java.time.Instant.parse("2026-01-01T00:00:00.000Z");
		org.hl7.fhir.r4.model.Bundle page1 = new org.hl7.fhir.r4.model.Bundle();
		for (int i = 0; i < emitBatchSize; i++) {
			addOrg(page1, "a" + i, base.plusMillis(i).toString());
		}
		org.hl7.fhir.r4.model.Bundle page2 = new org.hl7.fhir.r4.model.Bundle(); // empty: ends the loop

		java.util.concurrent.atomic.AtomicInteger orgCalls = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicLong page2FetchStartNanos = new java.util.concurrent.atomic.AtomicLong();
		java.util.concurrent.atomic.AtomicLong sinkCallStartNanos = new java.util.concurrent.atomic.AtomicLong();

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				String type = url.substring(0, url.indexOf('?'));
				if (!type.equals("Organization")) {
					return new org.hl7.fhir.r4.model.Bundle();
				}
				if (orgCalls.incrementAndGet() == 1) {
					return page1;
				}
				page2FetchStartNanos.compareAndSet(0, System.nanoTime());
				return page2;
			}
		};

		client.searchTypesByLastUpdated("http://x", "s", 10, null, ev -> {}, batch -> {
			sinkCallStartNanos.compareAndSet(0, System.nanoTime());
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		assertTrue(page2FetchStartNanos.get() > 0, "the second page should have been fetched");
		assertTrue(sinkCallStartNanos.get() > 0, "the sink should have been called once page 1's batch filled");
		assertTrue(
				page2FetchStartNanos.get() < sinkCallStartNanos.get(),
				"the next page's fetch should start before the current page's sink call, proving the overlap");
	}

	@Test
	void bulkExportDownloadsMultipleFilesInParallelAndMergesCounts() {
		Map<String, Object> byUrl = new java.util.concurrent.ConcurrentHashMap<>();
		byUrl.put(
				"http://example.test/file/Organization-1.ndjson",
				response(200, stream("{\"resourceType\":\"Organization\",\"id\":\"a\"}")));
		byUrl.put(
				"http://example.test/file/Organization-2.ndjson",
				response(200, stream("{\"resourceType\":\"Organization\",\"id\":\"b\"}")));
		byUrl.put(
				"http://example.test/file/Location.ndjson",
				response(200, stream("{\"resourceType\":\"Location\",\"id\":\"c\"}")));
		List<?> sequential = List.of(
				response(202, "", "Content-Location", "http://example.test/export-status"),
				response(
						200,
						"{\"output\":["
								+ "{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization-1.ndjson\"},"
								+ "{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization-2.ndjson\"},"
								+ "{\"type\":\"Location\",\"url\":\"http://example.test/file/Location.ndjson\"}"
								+ "]}"));
		FakeHttpClient http = new FakeHttpClient(sequential, byUrl);

		ApiProperties props = props();
		props.setCrawlConcurrency(2);
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = java.util.Collections.synchronizedList(new ArrayList<>());
		java.util.Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();

		FhirCrawlClient.SearchResult result = client.bulkExport(
				"http://example.test/fhir", "server", null, steps::add, batch -> batch.forEach(r -> keys.add(r.key())));

		assertEquals(3, result.records(), "records should sum across all three files");
		assertEquals(0, result.pages(), "bulk export never reports a page count");
		assertTrue(result.bytes() > 0, "downloaded bytes should be counted");
		assertEquals(
				java.util.Set.of("server|Organization/a", "server|Organization/b", "server|Location/c"),
				keys,
				"every file's resource should have been persisted");
		assertTrue(
				steps.stream().anyMatch(s -> "Organization-1".equals(s.track())),
				"a type with more than one file should get an index-suffixed track");
		assertTrue(
				steps.stream().anyMatch(s -> "Organization-2".equals(s.track())),
				"the second file of the same type should get its own index");
		assertTrue(
				steps.stream().anyMatch(s -> "Location".equals(s.track())),
				"a type with only one file keeps its plain type name as the track");
	}

	@Test
	void bulkExportFailsTheWholeExportWhenOneFileStillFailsAfterRetries() {
		// A file that fails after retries must fail the whole export (no finishFullSnapshot on the
		// CrawlService side): the sibling file must not silently paper over the missing one, which
		// bulkExport throwing rather than returning a SearchResult guarantees.
		Map<String, Object> byUrl = new java.util.concurrent.ConcurrentHashMap<>();
		byUrl.put(
				"http://example.test/file/Organization.ndjson",
				response(200, stream("{\"resourceType\":\"Organization\",\"id\":\"a\"}")));
		byUrl.put("http://example.test/file/Location.ndjson", response(410, stream("expired")));
		List<?> sequential = List.of(
				response(202, "", "Content-Location", "http://example.test/export-status"),
				response(
						200,
						"{\"output\":["
								+ "{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization.ndjson\"},"
								+ "{\"type\":\"Location\",\"url\":\"http://example.test/file/Location.ndjson\"}"
								+ "]}"));
		FakeHttpClient http = new FakeHttpClient(sequential, byUrl);

		ApiProperties props = props();
		props.setCrawlConcurrency(2);
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = java.util.Collections.synchronizedList(new ArrayList<>());

		IllegalStateException error = assertThrows(
				IllegalStateException.class,
				() -> client.bulkExport("http://example.test/fhir", "server", null, steps::add, batch -> {}));

		assertTrue(error.getMessage().contains("HTTP 410"), "the failure should surface the file's actual status");
		assertTrue(
				steps.stream().anyMatch(step -> Integer.valueOf(410).equals(step.status())),
				"the failing file's failure should be recorded with its actual status");
	}

	@Test
	void uniformWindowsSlicesTheRangeContiguously() {
		String lo = java.time.Instant.ofEpochMilli(0).toString();
		String hi = java.time.Instant.ofEpochMilli(200_000).toString();

		List<FhirCrawlClient.Window> windows = FhirCrawlClient.uniformWindows(lo, hi, 4, false);

		assertEquals(4, windows.size());
		assertEquals(lo, windows.get(0).lo(), "the first window's lo is the passed lo");
		assertEquals(hi, windows.get(windows.size() - 1).hi(), "the last window's hi is the passed hi");
		for (int i = 0; i < windows.size() - 1; i++) {
			assertEquals(
					windows.get(i).hi(), windows.get(i + 1).lo(), "windows should be contiguous with no gaps");
		}
	}

	@Test
	void uniformWindowsKeepsASingleWindowForSmallOrDegenerateRanges() {
		assertEquals(
				List.of(new FhirCrawlClient.Window("2026-01-01T00:00:00.000Z", "2026-01-02T00:00:00.000Z")),
				FhirCrawlClient.uniformWindows("2026-01-01T00:00:00.000Z", "2026-01-02T00:00:00.000Z", 1, false));

		String instant = java.time.Instant.ofEpochMilli(1000).toString();
		assertEquals(
				List.of(new FhirCrawlClient.Window(instant, instant)),
				FhirCrawlClient.uniformWindows(instant, instant, 8, false),
				"a zero-width range cannot be cut and stays one window");
	}

	@Test
	void uniformWindowsSkipsCutsThatCollapseAtMillisecondResolution() {
		String lo = java.time.Instant.ofEpochMilli(1000).toString();
		String hi = java.time.Instant.ofEpochMilli(1002).toString();

		List<FhirCrawlClient.Window> windows = FhirCrawlClient.uniformWindows(lo, hi, 8, false);

		assertEquals(
				List.of(
						new FhirCrawlClient.Window(lo, java.time.Instant.ofEpochMilli(1001).toString()),
						new FhirCrawlClient.Window(java.time.Instant.ofEpochMilli(1001).toString(), hi)),
				windows,
				"a 2ms range can only support one distinct cut");
	}

	@Test
	void openTailNullsOnlyTheLastWindowsHi() {
		String lo = java.time.Instant.ofEpochMilli(0).toString();
		String hi = java.time.Instant.ofEpochMilli(200_000).toString();

		List<FhirCrawlClient.Window> closed = FhirCrawlClient.uniformWindows(lo, hi, 4, false);
		List<FhirCrawlClient.Window> open = FhirCrawlClient.uniformWindows(lo, hi, 4, true);

		assertEquals(hi, closed.get(closed.size() - 1).hi(), "a closed plan keeps its final boundary");
		assertNull(open.get(open.size() - 1).hi(), "an open-tail plan clears only the final window's hi");
		for (int i = 0; i < open.size() - 1; i++) {
			assertEquals(closed.get(i), open.get(i), "every window but the last is unaffected by openTail");
		}
	}

	@Test
	void countFallsBackThroughCountZeroToTotalAccurate() {
		java.util.List<StepEvent> steps = new java.util.ArrayList<>();
		java.util.List<String> countUrls = new java.util.ArrayList<>();

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (!url.substring(0, url.indexOf('?')).equals("Organization")) {
					return countBundle(0);
				}
				if (url.contains("_count=1&_sort")) {
					return pageOf(false, "seed", "2026-01-01T00:00:00.000Z");
				}
				if (url.contains("_summary=count") || url.contains("_count=0")) {
					countUrls.add(url);
					throw new UnclassifiedServerFailureException(504, "Gateway Time-out");
				}
				if (url.contains("_total=accurate")) {
					countUrls.add(url);
					org.hl7.fhir.r4.model.Bundle page = pageOf(false, "first", "2026-01-02T00:00:00.000Z");
					page.setTotal(60_000);
					return page;
				}
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};

		client.searchTypesPartitioned(
				"http://x",
				"s",
				10,
				null,
				new FhirCrawlClient.ServerTime("2026-01-09T00:00:00.000Z", "date-header"),
				steps::add,
				batch -> {});

		assertEquals(3, countUrls.size(), "each count form is tried once, in order, with no per-form retries");
		assertTrue(countUrls.get(0).contains("_summary=count"));
		assertTrue(countUrls.get(1).contains("_count=0"));
		assertTrue(countUrls.get(2).contains("_total=accurate"));
		assertTrue(
				countUrls.stream().noneMatch(u -> u.contains("_lastUpdated")),
				"a first crawl counts the whole type with no date bounds");
		assertEquals(
				2,
				steps.stream()
						.filter(s -> s.message() != null && s.message().startsWith("Count query failed"))
						.count(),
				"each failed count form leaves a diagnosable failure step");
		assertTrue(
				steps.stream()
						.anyMatch(s -> s.message() != null
								&& s.message().contains("Searched Organization by last updated across 2 windows")),
				"the fallback total still sizes the windows");
	}

	@Test
	void allCountFormsFailingSlicesUniformlyInsteadOfOneGiantWindow() {
		java.util.List<StepEvent> steps = new java.util.ArrayList<>();
		ApiProperties props = props();
		props.setCrawlConcurrency(2);

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (!url.startsWith("Organization")) {
					return new org.hl7.fhir.r4.model.Bundle();
				}
				if (url.contains("_count=1&_sort")) {
					return pageOf(false, "seed", "2026-01-01T00:00:00.000Z");
				}
				if (url.contains("_summary=count") || url.contains("_count=0") || url.contains("_total=accurate")) {
					throw new UnclassifiedServerFailureException(504, "Gateway Time-out");
				}
				return new org.hl7.fhir.r4.model.Bundle();
			}
		};

		java.util.List<StepEvent> synced = java.util.Collections.synchronizedList(steps);
		client.searchTypesPartitioned(
				"http://x",
				"s",
				10,
				null,
				new FhirCrawlClient.ServerTime("2026-01-09T00:00:00.000Z", "date-header"),
				synced::add,
				batch -> {});

		assertTrue(
				synced.stream()
						.anyMatch(s -> s.message() != null
								&& s.message().contains("No count form worked for Organization")
								&& s.message().contains("2 uniform windows")),
				"a countless type is still sliced across the worker pool");
	}

	@Test
	void lastUpdatedQueryWithUpperBoundEncodesBothBounds() {
		String query = FhirCrawlClient.lastUpdatedQuery(
				"Organization", 500, "2024-06-03T22:08:54.557+00:00", "2024-06-04T00:00:00.000+00:00");

		assertTrue(
				query.contains("_lastUpdated=ge2024-06-03T22%3A08%3A54.557%2B00%3A00"),
				"the lower bound stays encoded as before");
		assertTrue(
				query.contains("_lastUpdated=lt2024-06-04T00%3A00%3A00.000%2B00%3A00"),
				"the upper bound is percent-encoded the same way");
	}

	@Test
	void lastUpdatedQueryWithoutAnUpperBoundMatchesTheThreeArgOverload() {
		assertEquals(
				FhirCrawlClient.lastUpdatedQuery("Organization", 500, "2024-06-03T22:08:54.557+00:00"),
				FhirCrawlClient.lastUpdatedQuery("Organization", 500, "2024-06-03T22:08:54.557+00:00", null));
	}

	@Test
	void searchTypesPartitionedSplitsATypeIntoWindowsAndCapturesEveryResourceExactlyOnce() {
		java.time.Instant t0Instant = java.time.Instant.parse("2026-01-01T00:00:00Z");
		String t0 = t0Instant.toString();
		String mid = t0Instant.plusMillis(100_000).toString();
		String hi = t0Instant.plusMillis(200_000).toString();

		org.hl7.fhir.r4.model.Bundle window1Page = pageOf(false, "a", t0);
		org.hl7.fhir.r4.model.Bundle window2Page = pageOf(false, "b", mid);

		Map<String, org.hl7.fhir.r4.model.Bundle> byUrl = new java.util.HashMap<>();
		byUrl.put(censusUrl("Organization", t0, hi), countBundle(60_000));
		byUrl.put(censusUrl("Organization", t0, mid), countBundle(1));
		byUrl.put(censusUrl("Organization", mid, hi), countBundle(1));
		byUrl.put(FhirCrawlClient.lastUpdatedQuery("Organization", 10, t0, mid), window1Page);
		byUrl.put(FhirCrawlClient.lastUpdatedQuery("Organization", 10, mid, hi), window2Page);

		List<String> queriesSeen = java.util.Collections.synchronizedList(new ArrayList<>());
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				queriesSeen.add(url);
				String type = url.substring(0, url.indexOf('?'));
				if (!type.equals("Organization")) {
					return countBundle(0); // nothing to crawl for the other types
				}
				org.hl7.fhir.r4.model.Bundle canned = byUrl.get(url);
				if (canned == null) {
					throw new IllegalStateException("no fake response for " + url);
				}
				return canned;
			}
		};

		java.util.Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();
		List<StepEvent> steps = java.util.Collections.synchronizedList(new ArrayList<>());
		FhirCrawlClient.ServerTime anchor = new FhirCrawlClient.ServerTime(hi, "date-header");

		client.searchTypesPartitioned(
				"http://x", "s", 10, t0, anchor, steps::add, batch -> batch.forEach(r -> keys.add(r.key())));

		assertEquals(
				java.util.Set.of("s|Organization/a", "s|Organization/b"),
				keys,
				"each window's resource should arrive exactly once");
		assertTrue(
				queriesSeen.stream()
						.filter(u -> u.startsWith("Organization?_count="))
						.allMatch(u -> u.contains("_lastUpdated=lt")),
				"every partitioned page query should carry the lt bound under a trusted anchor");
		assertTrue(
				steps.stream()
						.anyMatch(s -> s.message() != null
								&& s.message().contains("Partitioned Organization")
								&& s.message().contains("into 2 windows")),
				"the plan should be narrated");
	}

	@Test
	void anchorFromAnUntrustedSourceLeavesTheFinalWindowOpenEnded() {
		List<String> pageFetchUrls = java.util.Collections.synchronizedList(new ArrayList<>());
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				if (url.contains("_summary=count")) {
					return countBundle(10); // small: every type plans a single window
				}
				pageFetchUrls.add(url);
				return new org.hl7.fhir.r4.model.Bundle(); // empty: terminates the chain immediately
			}
		};

		List<StepEvent> steps = new ArrayList<>();
		FhirCrawlClient.ServerTime anchor =
				new FhirCrawlClient.ServerTime(java.time.Instant.now().toString(), "bundle-meta");

		client.searchTypesPartitioned("http://x", "s", 10, "2026-01-01T00:00:00Z", anchor, steps::add, batch -> {});

		assertFalse(pageFetchUrls.isEmpty(), "at least one type's window should have been fetched");
		assertTrue(
				pageFetchUrls.stream().noneMatch(u -> u.contains("_lastUpdated=lt")),
				"an untrusted anchor must leave every type's final (only) window open-ended");
	}

	@Test
	void censusWithNoTotalDegradesToASingleWindowAndStillCrawlsTheType() {
		org.hl7.fhir.r4.model.Bundle organizationPage = pageOf(false, "a", "2026-01-01T00:00:00Z");
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				String type = url.substring(0, url.indexOf('?'));
				if (!type.equals("Organization")) {
					return new org.hl7.fhir.r4.model.Bundle();
				}
				if (url.contains("_summary=count")) {
					return new org.hl7.fhir.r4.model.Bundle(); // no total set: unsupported by silence
				}
				return organizationPage;
			}
		};

		List<StepEvent> steps = java.util.Collections.synchronizedList(new ArrayList<>());
		java.util.Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();
		FhirCrawlClient.ServerTime anchor = new FhirCrawlClient.ServerTime("2026-01-02T00:00:00Z", "date-header");

		client.searchTypesPartitioned(
				"http://x", "s", 10, "2026-01-01T00:00:00Z", anchor, steps::add, batch -> batch.forEach(
						r -> keys.add(r.key())));

		assertEquals(java.util.Set.of("s|Organization/a"), keys);
		assertTrue(
				steps.stream()
						.anyMatch(s -> s.message() != null && s.message().contains("No count form worked for Organization")),
				"a missing total should degrade to uniform windows rather than fail the type");
	}

	@Test
	void censusRejectionDegradesToASingleWindowWithoutSkippingTheType() {
		org.hl7.fhir.r4.model.Bundle organizationPage = pageOf(false, "a", "2026-01-01T00:00:00Z");
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), props()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				String type = url.substring(0, url.indexOf('?'));
				if (!type.equals("Organization")) {
					return new org.hl7.fhir.r4.model.Bundle();
				}
				if (url.contains("_summary=count")) {
					throw new InvalidRequestException("HTTP 400: _summary=count not supported");
				}
				return organizationPage;
			}
		};

		List<StepEvent> steps = java.util.Collections.synchronizedList(new ArrayList<>());
		java.util.Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();
		FhirCrawlClient.ServerTime anchor = new FhirCrawlClient.ServerTime("2026-01-02T00:00:00Z", "date-header");

		client.searchTypesPartitioned(
				"http://x", "s", 10, "2026-01-01T00:00:00Z", anchor, steps::add, batch -> batch.forEach(
						r -> keys.add(r.key())));

		assertEquals(java.util.Set.of("s|Organization/a"), keys, "the type must not be skipped when census is rejected");
		assertTrue(
				steps.stream()
						.anyMatch(s -> s.message() != null && s.message().startsWith("Count query failed for Organization")),
				"the rejected count form should leave a failure step");
		assertTrue(
				steps.stream()
						.anyMatch(s -> s.message() != null && s.message().contains("No count form worked for Organization")),
				"the degradation should be narrated");
	}

	private static String censusUrl(String type, String lo, String hi) {
		return type + "?_summary=count&_lastUpdated=ge"
				+ java.net.URLEncoder.encode(lo, java.nio.charset.StandardCharsets.UTF_8)
				+ "&_lastUpdated=lt"
				+ java.net.URLEncoder.encode(hi, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static org.hl7.fhir.r4.model.Bundle countBundle(long total) {
		org.hl7.fhir.r4.model.Bundle bundle = new org.hl7.fhir.r4.model.Bundle();
		bundle.setTotal((int) total);
		return bundle;
	}

	private static void await(java.util.concurrent.CountDownLatch latch) {
		try {
			latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static org.hl7.fhir.r4.model.Bundle pageOf(boolean hasNext, String... idThenLastUpdated) {
		org.hl7.fhir.r4.model.Bundle bundle = new org.hl7.fhir.r4.model.Bundle();
		for (int i = 0; i < idThenLastUpdated.length; i += 2) {
			addOrg(bundle, idThenLastUpdated[i], idThenLastUpdated[i + 1]);
		}
		if (hasNext) {
			bundle.addLink().setRelation("next").setUrl("http://x/next/" + idThenLastUpdated[0]);
		}
		return bundle;
	}

	/** Appends a search.mode=outcome OperationOutcome entry, as some servers do on every page. */
	private static org.hl7.fhir.r4.model.Bundle withOutcomeEntry(
			org.hl7.fhir.r4.model.Bundle bundle, String lastUpdated) {
		org.hl7.fhir.r4.model.OperationOutcome outcome = new org.hl7.fhir.r4.model.OperationOutcome();
		outcome.setId("count-warning");
		outcome.getMeta().setLastUpdatedElement(new org.hl7.fhir.r4.model.InstantType(lastUpdated));
		bundle.addEntry()
				.setResource(outcome)
				.getSearch()
				.setMode(org.hl7.fhir.r4.model.Bundle.SearchEntryMode.OUTCOME);
		return bundle;
	}

	private static org.hl7.fhir.r4.model.Organization addOrg(
			org.hl7.fhir.r4.model.Bundle bundle, String id, String lastUpdated) {
		org.hl7.fhir.r4.model.Organization org = new org.hl7.fhir.r4.model.Organization();
		org.setId(id);
		org.getMeta().setLastUpdatedElement(new org.hl7.fhir.r4.model.InstantType(lastUpdated));
		bundle.addEntry().setResource(org);
		return org;
	}

	/** A second resource type so multi-type tests can route by type without ambiguity. */
	private static org.hl7.fhir.r4.model.Location addLocation(
			org.hl7.fhir.r4.model.Bundle bundle, String id, String lastUpdated) {
		org.hl7.fhir.r4.model.Location loc = new org.hl7.fhir.r4.model.Location();
		loc.setId(id);
		loc.getMeta().setLastUpdatedElement(new org.hl7.fhir.r4.model.InstantType(lastUpdated));
		bundle.addEntry().setResource(loc);
		return loc;
	}

	private static java.io.ByteArrayInputStream stream(String body) {
		return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** The body's runtime type must match the BodyHandler the code under test uses (String or InputStream). */
	private static <T> HttpResponse<T> response(int status, T body, String... header) {
		return new HttpResponse<>() {
			@Override
			public int statusCode() {
				return status;
			}

			@Override
			public HttpRequest request() {
				return null;
			}

			@Override
			public Optional<HttpResponse<T>> previousResponse() {
				return Optional.empty();
			}

			@Override
			public HttpHeaders headers() {
				if (header.length == 2) {
					return HttpHeaders.of(java.util.Map.of(header[0], List.of(header[1])), (name, value) -> true);
				}
				return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
			}

			@Override
			public T body() {
				return body;
			}

			@Override
			public Optional<javax.net.ssl.SSLSession> sslSession() {
				return Optional.empty();
			}

			@Override
			public URI uri() {
				return URI.create("http://example.test");
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_1_1;
			}
		};
	}

	private static class FakeHttpClient extends HttpClient {
		/** Canned outcomes per send, consumed in order: an HttpResponse to return, or an IOException to throw. */
		private final List<?> outcomes;

		/**
		 * Outcomes keyed by request URL, consulted once {@code outcomes} is exhausted. Lets concurrent
		 * file-download requests (arriving in any order, from multiple threads) resolve independently
		 * of the sequential kickoff/poll calls that precede them.
		 */
		private final Map<String, Object> byUrl;

		/** Every request URL seen, in order, so tests can assert on kick-off parameters. */
		final List<String> requestedUrls = java.util.Collections.synchronizedList(new ArrayList<>());

		private int index;

		FakeHttpClient(List<?> outcomes) {
			this(outcomes, Map.of());
		}

		FakeHttpClient(List<?> outcomes, Map<String, Object> byUrl) {
			this.outcomes = outcomes;
			this.byUrl = byUrl;
		}

		@Override
		public Optional<CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.empty();
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public Optional<ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext() {
			try {
				SSLContext context = SSLContext.getInstance("TLS");
				context.init(null, null, new SecureRandom());
				return context;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public SSLParameters sslParameters() {
			return new SSLParameters();
		}

		@Override
		public Optional<Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_1_1;
		}

		@Override
		public Optional<Executor> executor() {
			return Optional.empty();
		}

		@SuppressWarnings("unchecked")
		@Override
		public synchronized <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
				throws IOException, InterruptedException {
			requestedUrls.add(request.uri().toString());
			Object outcome;
			if (index < outcomes.size()) {
				outcome = outcomes.get(index++);
			} else {
				outcome = byUrl.get(request.uri().toString());
				if (outcome == null) {
					throw new IllegalStateException("no fake outcome for " + request.uri());
				}
			}
			if (outcome instanceof IOException e) {
				throw e;
			}
			return (HttpResponse<T>) outcome;
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
				HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
			throw new UnsupportedOperationException("sendAsync");
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
				HttpRequest request,
				HttpResponse.BodyHandler<T> responseBodyHandler,
				HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			throw new UnsupportedOperationException("sendAsync");
		}
	}
}

package org.hl7.davinci.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.hl7.davinci.api.config.ApiProperties;
import org.junit.jupiter.api.Test;

class FhirCrawlClientTest {

	@Test
	void bulkHttpClientFollowsRedirects() {
		// A Content-Location poll URL can 301 http->https behind a TLS-terminating proxy.
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
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
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		IllegalStateException error = assertThrows(
				IllegalStateException.class,
				() -> client.bulkExport("http://example.test/fhir", "server", steps::add, batch -> {}));

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
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.SearchResult result = client.bulkExport("http://example.test/fhir", "server", steps::add, batch -> {});

		assertEquals(1, result.records(), "the export should succeed after the retried kick-off");
		assertTrue(
				steps.stream().anyMatch(step -> step.message().contains("retrying in")),
				"the retry should be narrated into the play-by-play");
	}

	@Test
	void retriesTransientFailuresAndFailsFastOnPermanentOnes() throws Exception {
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
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
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties());
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
		assertEquals(expected, FhirCrawlClient.maxLastUpdated(bundle).value(), "returns the newest entry's lastUpdated");
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
		assertNull(FhirCrawlClient.maxLastUpdated(new org.hl7.fhir.r4.model.Bundle()));
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

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				return searchPages.isEmpty() ? new org.hl7.fhir.r4.model.Bundle() : searchPages.poll();
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
	void searchByLastUpdatedTerminatesWhenOneInstantIsRenderedWithDifferentStrings() {
		// a, b, c all share one instant but the server renders it two ways (Z vs +00:00). Comparing
		// verbatim strings would oscillate the watermark forever; comparing parsed instants must settle.
		org.hl7.fhir.r4.model.Bundle clusterHead = pageOf(true, "a", "2026-01-01T00:00:00.000Z");
		addOrg(clusterHead, "b", "2026-01-01T00:00:00.000Z");
		int[] searchCalls = {0};

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
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

		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			ca.uhn.fhir.rest.client.api.IGenericClient newClient(String serverUrl) {
				return null;
			}

			@Override
			org.hl7.fhir.r4.model.Bundle searchByUrl(ca.uhn.fhir.rest.client.api.IGenericClient c, String url) {
				return searchPages.isEmpty() ? new org.hl7.fhir.r4.model.Bundle() : searchPages.poll();
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

	private static org.hl7.fhir.r4.model.Organization addOrg(
			org.hl7.fhir.r4.model.Bundle bundle, String id, String lastUpdated) {
		org.hl7.fhir.r4.model.Organization org = new org.hl7.fhir.r4.model.Organization();
		org.setId(id);
		org.getMeta().setLastUpdatedElement(new org.hl7.fhir.r4.model.InstantType(lastUpdated));
		bundle.addEntry().setResource(org);
		return org;
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
		/** Canned outcomes per send: an HttpResponse to return, or an IOException to throw. */
		private final List<?> outcomes;

		private int index;

		FakeHttpClient(List<?> outcomes) {
			this.outcomes = outcomes;
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
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
				throws IOException, InterruptedException {
			Object outcome = outcomes.get(index++);
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

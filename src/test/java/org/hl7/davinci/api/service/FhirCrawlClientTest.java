package org.hl7.davinci.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
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
	void bulkExportFailsWhenOutputFileDownloadIsNotSuccessful() {
		FakeHttpClient http = new FakeHttpClient(List.of(
				response(202, "", "Content-Location", "http://example.test/export-status"),
				response(
						200,
						"{\"output\":[{\"type\":\"Organization\",\"url\":\"http://example.test/file/Organization.ndjson\"}]}"),
				response(410, "expired")));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		IllegalStateException error = assertThrows(
				IllegalStateException.class,
				() -> client.bulkExport("http://example.test/fhir", "server", steps::add));

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
				response(200, "{\"resourceType\":\"Organization\",\"id\":\"a\"}")));
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper(), new ApiProperties()) {
			@Override
			HttpClient newBulkHttpClient() {
				return http;
			}
		};
		List<StepEvent> steps = new ArrayList<>();

		FhirCrawlClient.SearchResult result = client.bulkExport("http://example.test/fhir", "server", steps::add);

		assertEquals(1, result.fetched().size(), "the export should succeed after the retried kick-off");
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
		assertThrows(InternalErrorException.class, () -> client.withRetry("SEARCH", "test call", steps::add, () -> {
			permanentCalls[0]++;
			throw new InternalErrorException("HTTP 500");
		}));
		assertEquals(1, permanentCalls[0], "a plain 500 must fail fast without retries");
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

	private static HttpResponse<String> response(int status, String body, String... header) {
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
			public Optional<HttpResponse<String>> previousResponse() {
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
			public String body() {
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

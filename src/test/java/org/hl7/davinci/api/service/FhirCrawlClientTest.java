package org.hl7.davinci.api.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
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
		FhirCrawlClient client = new FhirCrawlClient(FhirContext.forR4(), new ObjectMapper()) {
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
		private final List<HttpResponse<String>> responses;
		private int index;

		FakeHttpClient(List<HttpResponse<String>> responses) {
			this.responses = responses;
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
			return (HttpResponse<T>) responses.get(index++);
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

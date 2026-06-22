package org.hl7.davinci.api.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.client.interceptor.CapturingInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Outbound FHIR client for crawling external servers. */
@Component
public class FhirCrawlClient {

	private static final Logger ourLog = LoggerFactory.getLogger(FhirCrawlClient.class);

	/** The 8 Plan-Net resource types crawled by every strategy. No Bundle. */
	public static final List<String> PLAN_NET_TYPES = List.of(
			"Endpoint",
			"HealthcareService",
			"InsurancePlan",
			"Location",
			"Organization",
			"OrganizationAffiliation",
			"Practitioner",
			"PractitionerRole");

	/** Hard cap on pages per type to guard against pagination loops. */
	static final int MAX_PAGES_PER_TYPE = 1000;

	/** Resources buffered before a batch is pushed to the persistence sink. */
	private static final int EMIT_BATCH = 1000;

	/** Emit an INFO fetch heartbeat every this many pages within a single type/stream. */
	private static final int PAGE_LOG_EVERY = 20;

	private static final int POLL_MAX_ATTEMPTS = 120;
	private static final int POLL_DEFAULT_WAIT_SECONDS = 2;

	/** Bounded attempts per request for transient failures (timeouts, dropped connections, gateway 5xx). */
	static final int MAX_RETRY_ATTEMPTS = 3;

	private static final long RETRY_BACKOFF_MS = 2_000;

	/** Cap on server-requested Retry-After waits so a hostile header cannot stall a worker. */
	private static final long MAX_RETRY_AFTER_MS = 60_000;

	private final FhirContext fhirContext;
	private final ObjectMapper objectMapper;

	/** Connect and per-request timeout for all outbound crawl calls (api.request-timeout-ms). */
	private final Duration httpTimeout;

	/** Politeness pause between page fetches (api.page-delay-ms); 0 = none. */
	private final long pageDelayMs;

	public FhirCrawlClient(FhirContext fhirContext, ObjectMapper objectMapper, ApiProperties props) {
		this.fhirContext = fhirContext;
		this.objectMapper = objectMapper;
		this.httpTimeout = Duration.ofMillis(props.getRequestTimeoutMs());
		this.pageDelayMs = props.getPageDelayMs();
		// HAPI client timeouts are factory-wide, so clients from newClient() pick these up.
		fhirContext.getRestfulClientFactory().setConnectTimeout(props.getRequestTimeoutMs());
		fhirContext.getRestfulClientFactory().setSocketTimeout(props.getRequestTimeoutMs());
	}

	public record ServerTime(String iso, String source) {}

	public record SearchResult(int records, long bytes, int requests, int pages) {}

	IGenericClient newClient(String serverUrl) {
		return fhirContext.newRestfulGenericClient(serverUrl);
	}

	/** A failure step for a HAPI client error, retaining its status and raw response body. */
	private static StepEvent serverError(
			String phase, String message, String url, long startNanos, BaseServerResponseException e) {
		return StepEvent.failure(
				phase,
				message + ": HTTP " + e.getStatusCode(),
				"GET",
				url,
				e.getStatusCode(),
				(System.nanoTime() - startNanos) / 1_000_000,
				e.getResponseBody());
	}

	HttpClient newBulkHttpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(httpTimeout)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@FunctionalInterface
	interface RetryableCall<T> {
		T run() throws IOException, InterruptedException;
	}

	/**
	 * Run an idempotent GET with bounded retries on transient failures, backing off linearly and
	 * narrating each retry into the play-by-play. Permanent errors (4xx, most 5xx) fail fast.
	 */
	<T> T withRetry(String phase, String what, Consumer<StepEvent> steps, RetryableCall<T> call)
			throws IOException, InterruptedException {
		for (int attempt = 1; ; attempt++) {
			try {
				return call.run();
			} catch (Exception e) {
				if (attempt >= MAX_RETRY_ATTEMPTS || !isTransient(e)) {
					throw e;
				}
				long backoffMs = retryDelayMs(e, RETRY_BACKOFF_MS * attempt);
				steps.accept(StepEvent.info(
						phase,
						"Transient failure on " + what + " (" + reason(e) + "); retrying in " + (backoffMs / 1000)
								+ "s (attempt " + (attempt + 1) + " of " + MAX_RETRY_ATTEMPTS + ")"));
				Thread.sleep(backoffMs);
			}
		}
	}

	/** Adapter for the HAPI call sites, whose failures are all unchecked. */
	private <T> T withRetryUnchecked(String phase, String what, Consumer<StepEvent> steps, Supplier<T> call) {
		try {
			return withRetry(phase, what, steps, call::get);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Crawl interrupted during retry backoff", e);
		}
	}

	/**
	 * Timeouts, dropped connections, rate limiting (429), and gateway-style 5xx are worth
	 * retrying; the rest fail fast.
	 */
	static boolean isTransient(Exception e) {
		if (e instanceof FhirClientConnectionException) {
			return true;
		}
		if (e instanceof BaseServerResponseException serverError) {
			int status = serverError.getStatusCode();
			return status == 429 || status == 502 || status == 503 || status == 504;
		}
		return e instanceof HttpTimeoutException || e instanceof ConnectException || e instanceof SocketException;
	}

	/** The server's Retry-After (seconds form, clamped) for a 429; the fixed backoff otherwise. */
	static long retryDelayMs(Exception e, long fallbackMs) {
		if (e instanceof BaseServerResponseException serverError
				&& serverError.getStatusCode() == 429
				&& serverError.hasResponseHeaders()) {
			for (Map.Entry<String, List<String>> header :
					serverError.getResponseHeaders().entrySet()) {
				if ("retry-after".equalsIgnoreCase(header.getKey())
						&& !header.getValue().isEmpty()) {
					try {
						long ms = Long.parseLong(header.getValue().get(0).trim()) * 1000L;
						return Math.min(Math.max(ms, 1_000L), MAX_RETRY_AFTER_MS);
					} catch (NumberFormatException ignored) {
						// HTTP-date form or junk; the fixed backoff applies
					}
				}
			}
		}
		return fallbackMs;
	}

	/** Optional politeness pause before fetching the next page (api.page-delay-ms). */
	private void pauseBetweenPages() {
		if (pageDelayMs <= 0) {
			return;
		}
		try {
			Thread.sleep(pageDelayMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Crawl interrupted during page delay", e);
		}
	}

	private static String reason(Exception e) {
		return e.getMessage() != null && !e.getMessage().isBlank()
				? e.getMessage()
				: e.getClass().getSimpleName();
	}

	/** Server-time anchor: HTTP Date header, then meta.lastUpdated, then local time. */
	public ServerTime getServerTime(String serverUrl) {
		IGenericClient client = newClient(serverUrl);
		CapturingInterceptor capture = new CapturingInterceptor();
		client.registerInterceptor(capture);
		try {
			CapabilityStatement cs =
					client.capabilities().ofType(CapabilityStatement.class).execute();

			IHttpResponse response = capture.getLastResponse();
			if (response != null) {
				for (Map.Entry<String, List<String>> header :
						response.getAllHeaders().entrySet()) {
					if ("date".equalsIgnoreCase(header.getKey())
							&& !header.getValue().isEmpty()) {
						Instant parsed = parseHttpDate(header.getValue().get(0));
						if (parsed != null) {
							return new ServerTime(parsed.toString(), "date-header");
						}
					}
				}
			}
			if (cs != null && cs.getMeta() != null && cs.getMeta().hasLastUpdated()) {
				return new ServerTime(cs.getMeta().getLastUpdatedElement().getValueAsString(), "bundle-meta");
			}
		} catch (Exception e) {
			ourLog.warn("Could not read server time from {}: {}", serverUrl, e.getMessage());
		} finally {
			client.unregisterInterceptor(capture);
		}
		return new ServerTime(Instant.now().toString(), "client-fallback");
	}

	private static Instant parseHttpDate(String value) {
		try {
			return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Search every Plan-Net type. When {@code since} is set, only newer resources are pulled
	 * ({@code _lastUpdated=gt{since}}); otherwise a full snapshot.
	 */
	public SearchResult searchTypes(
			String serverUrl,
			String serverKey,
			int pageSize,
			String since,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		IGenericClient client = newClient(serverUrl);
		BatchEmitter emitter = new BatchEmitter(resourceSink);
		long[] bytes = {0};
		int requests = 0;
		int pages = 0;

		IParser parser = fhirContext.newJsonParser();

		for (String type : PLAN_NET_TYPES) {
			Set<String> seen = new HashSet<>();
			StringBuilder query =
					new StringBuilder(type).append("?_count=").append(pageSize).append("&_sort=_lastUpdated");
			if (since != null) {
				// Inclusive (ge) so a resource stamped exactly at the anchor instant is never skipped;
				// DiffUtil marks an unchanged re-fetch as no-op, so the boundary overlap is harmless.
				query.append("&_lastUpdated=ge").append(since);
			}

			long typeStartNanos = System.nanoTime();
			int beforeCount = emitter.count();
			long beforeBytes = bytes[0];

			steps.accept(StepEvent.progress(
					"SEARCH",
					(since != null ? "Searching " + type + " changes since the anchor" : "Searching all " + type)
							+ "..."));
			int typePages = 0;
			try {
				Bundle bundle = withRetryUnchecked("SEARCH", type + " search", steps, () -> client.search()
						.byUrl(query.toString())
						.returnBundle(Bundle.class)
						.execute());
				while (bundle != null) {
					requests++;
					pages++;
					typePages++;
					collectEntries(bundle, serverKey, parser, emitter, bytes);
					if (typePages % PAGE_LOG_EVERY == 0) {
						ourLog.info(
								"SEARCH {}: {} pages fetched, {} resources so far", type, typePages, emitter.count());
					}

					Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
					if (next == null || next.getUrl() == null || seen.contains(next.getUrl())) {
						break;
					}
					if (typePages >= MAX_PAGES_PER_TYPE) {
						ourLog.warn("Hit page cap for {} on {}", type, serverKey);
						break;
					}
					seen.add(next.getUrl());
					pauseBetweenPages();
					steps.accept(StepEvent.progress("SEARCH", "Fetching " + type + " page " + (typePages + 1) + "..."));
					Bundle current = bundle;
					bundle = withRetryUnchecked("SEARCH", type + " page fetch", steps, () -> client.loadPage()
							.next(current)
							.execute());
				}
			} catch (BaseServerResponseException e) {
				steps.accept(
						serverError("SEARCH", "Search failed for " + type, serverUrl + "/" + query, typeStartNanos, e));
				throw e;
			}

			int typeCount = emitter.count() - beforeCount;
			steps.accept(StepEvent.request(
					"SEARCH",
					(since != null ? "Searched " + type + " changed since the anchor" : "Searched all " + type) + " ("
							+ typePages + " page" + (typePages == 1 ? "" : "s") + ")",
					"GET",
					serverUrl + "/" + query,
					200,
					(System.nanoTime() - typeStartNanos) / 1_000_000,
					bytes[0] - beforeBytes,
					typeCount));
		}

		emitter.flush();
		return new SearchResult(emitter.count(), bytes[0], requests, pages);
	}

	private void collectEntries(Bundle bundle, String serverKey, IParser parser, BatchEmitter emitter, long[] bytes) {
		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();
			if (resource == null
					|| resource.getIdElement() == null
					|| resource.getIdElement().getIdPart() == null) {
				continue;
			}
			String type = resource.fhirType();
			String id = resource.getIdElement().getIdPart();
			String versionId =
					resource.getMeta() != null ? emptyToNull(resource.getMeta().getVersionId()) : null;
			String lastUpdated =
					resource.getMeta() != null && resource.getMeta().hasLastUpdated()
							? resource.getMeta().getLastUpdatedElement().getValueAsString()
							: null;
			String json = parser.encodeResourceToString(resource);
			bytes[0] += json.length();
			emitter.add(new FetchedResource(
					serverKey + "|" + type + "/" + id, type, id, versionId, lastUpdated, json, json.length()));
		}
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	/** Buffers fetched resources and pushes them to the persistence sink in batches. */
	private static final class BatchEmitter {
		private final Consumer<List<FetchedResource>> sink;
		private final List<FetchedResource> buffer = new ArrayList<>();
		private int count;

		BatchEmitter(Consumer<List<FetchedResource>> sink) {
			this.sink = sink;
		}

		void add(FetchedResource fr) {
			buffer.add(fr);
			count++;
			if (buffer.size() >= EMIT_BATCH) {
				flush();
			}
		}

		void flush() {
			if (!buffer.isEmpty()) {
				sink.accept(new ArrayList<>(buffer));
				buffer.clear();
			}
		}

		int count() {
			return count;
		}
	}

	public record DeletionScanResult(List<DeletionEntry> deletions, int requests, int pages, long bytes) {}

	/**
	 * Walk system _history since the anchor and collect deletions. An entry with no
	 * resource body is treated as a deletion. Throws {@link HistoryUnsupportedException}
	 * if the server rejects _history.
	 */
	public DeletionScanResult scanDeletions(String serverUrl, String since, int pageSize, Consumer<StepEvent> steps) {
		IGenericClient client = newClient(serverUrl);
		List<DeletionEntry> deletions = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int requests = 0;
		int pages = 0;
		long startNanos = System.nanoTime();

		String scanUrl = serverUrl + "/_history?_since=" + since;
		steps.accept(StepEvent.progress("HISTORY", "Scanning system _history for deletions..."));
		Bundle bundle;
		try {
			bundle = withRetryUnchecked("HISTORY", "deletion scan", steps, () -> client.history()
					.onServer()
					.andReturnBundle(Bundle.class)
					.since(Date.from(parseInstant(since)))
					.count(pageSize)
					.execute());
		} catch (BaseServerResponseException e) {
			if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
				throw new HistoryUnsupportedException(serverUrl);
			}
			steps.accept(serverError("HISTORY", "Deletion scan via system _history failed", scanUrl, startNanos, e));
			throw e;
		}

		try {
			while (bundle != null) {
				requests++;
				pages++;
				extractDeletions(bundle, deletions);

				Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
				if (next == null || next.getUrl() == null || seen.contains(next.getUrl())) {
					break;
				}
				seen.add(next.getUrl());
				pauseBetweenPages();
				steps.accept(StepEvent.progress("HISTORY", "Fetching deletion scan page " + (pages + 1) + "..."));
				Bundle current = bundle;
				bundle = withRetryUnchecked("HISTORY", "deletion scan page fetch", steps, () -> client.loadPage()
						.next(current)
						.execute());
			}
		} catch (BaseServerResponseException e) {
			steps.accept(serverError("HISTORY", "Deletion scan via system _history failed", scanUrl, startNanos, e));
			throw e;
		}

		steps.accept(StepEvent.request(
				"HISTORY",
				"Scanned system _history for deletions since the anchor",
				"GET",
				serverUrl + "/_history?_since=" + since,
				200,
				(System.nanoTime() - startNanos) / 1_000_000,
				null,
				deletions.size()));
		return new DeletionScanResult(deletions, requests, pages, 0);
	}

	static boolean isDeletion(Bundle.BundleEntryComponent entry) {
		Bundle.HTTPVerb method = entry.hasRequest() ? entry.getRequest().getMethod() : null;
		if (method != null) {
			return method == Bundle.HTTPVerb.DELETE;
		}
		return entry.getResource() == null;
	}

	static void extractDeletions(Bundle bundle, List<DeletionEntry> out) {
		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			if (!isDeletion(entry)) {
				continue;
			}
			String ref = entry.getRequest() != null && entry.getRequest().getUrl() != null
					? entry.getRequest().getUrl()
					: entry.getFullUrl();
			DeletionEntry parsed = parseReference(ref);
			if (parsed != null) {
				out.add(parsed);
			}
		}
	}

	private static DeletionEntry parseReference(String ref) {
		if (ref == null) {
			return null;
		}
		String clean = ref.split("\\?")[0].replaceAll("/_history/.*$", "");
		List<String> segments = new ArrayList<>();
		for (String part : clean.split("/")) {
			if (!part.isEmpty()) {
				segments.add(part);
			}
		}
		if (segments.size() < 2) {
			return null;
		}
		String id = segments.get(segments.size() - 1);
		String type = segments.get(segments.size() - 2);
		return new DeletionEntry(type, id);
	}

	private static Instant parseInstant(String value) {
		try {
			return Instant.parse(value);
		} catch (Exception primary) {
			try {
				return OffsetDateTime.parse(value).toInstant();
			} catch (Exception fallback) {
				return Instant.now();
			}
		}
	}

	/**
	 * Acquire a full snapshot via async Bulk Data $export: kick off, poll Content-Location
	 * honoring Retry-After, then download and parse each NDJSON output file.
	 */
	public SearchResult bulkExport(
			String serverUrl,
			String serverKey,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		HttpClient http = newBulkHttpClient();
		IParser parser = fhirContext.newJsonParser();
		int requests = 0;
		long bytes = 0;
		try {
			String typeParam = URLEncoder.encode(String.join(",", PLAN_NET_TYPES), StandardCharsets.UTF_8);
			String kickoffUrl = serverUrl + "/$export?_type=" + typeParam;
			steps.accept(StepEvent.progress("EXPORT", "Kicking off system $export..."));
			long kickStart = System.nanoTime();
			HttpResponse<String> kickoff = withRetry(
					"EXPORT",
					"$export kick-off",
					steps,
					() -> http.send(
							HttpRequest.newBuilder(URI.create(kickoffUrl))
									.timeout(httpTimeout)
									.header("Accept", "application/fhir+json")
									.header("Prefer", "respond-async")
									.GET()
									.build(),
							HttpResponse.BodyHandlers.ofString()));
			requests++;
			long kickMs = (System.nanoTime() - kickStart) / 1_000_000;
			if (kickoff.statusCode() != 202) {
				steps.accept(StepEvent.failure(
						"EXPORT",
						"Bulk $export kick-off failed: HTTP " + kickoff.statusCode(),
						"GET",
						kickoffUrl,
						kickoff.statusCode(),
						kickMs,
						kickoff.body()));
				throw new IllegalStateException("Expected 202 from $export, got " + kickoff.statusCode());
			}
			steps.accept(StepEvent.request(
					"EXPORT",
					"Kicked off system $export with Prefer: respond-async",
					"GET",
					kickoffUrl,
					kickoff.statusCode(),
					kickMs,
					null,
					null));
			String pollUrl = kickoff.headers()
					.firstValue("Content-Location")
					.orElseThrow(() -> new IllegalStateException("$export 202 missing Content-Location"));

			JsonNode manifest = null;
			for (int attempt = 1; attempt <= POLL_MAX_ATTEMPTS && manifest == null; attempt++) {
				long pollStart = System.nanoTime();
				HttpResponse<String> poll = withRetry(
						"EXPORT",
						"$export status poll",
						steps,
						() -> http.send(
								HttpRequest.newBuilder(URI.create(pollUrl))
										.timeout(httpTimeout)
										.header("Accept", "application/json")
										.GET()
										.build(),
								HttpResponse.BodyHandlers.ofString()));
				requests++;
				int status = poll.statusCode();
				String retryAfter = poll.headers().firstValue("Retry-After").orElse(null);
				steps.accept(StepEvent.request(
						"EXPORT",
						"Polled export status (attempt " + attempt + ")"
								+ (status == 202 && retryAfter != null ? ", Retry-After " + retryAfter + "s" : ""),
						"GET",
						pollUrl,
						status,
						(System.nanoTime() - pollStart) / 1_000_000,
						null,
						null));
				if (status == 200) {
					manifest = objectMapper.readTree(poll.body());
				} else if (status >= 500) {
					steps.accept(StepEvent.failure(
							"EXPORT", "$export failed: HTTP " + status, "GET", pollUrl, status, null, poll.body()));
					throw new IllegalStateException("$export failed: HTTP " + status);
				} else if (status == 202 || status == 429) {
					// 429 during polling is the server asking us to slow down, not a failure.
					Thread.sleep(parseRetryAfter(retryAfter) * 1000L);
				} else {
					steps.accept(StepEvent.failure(
							"EXPORT",
							"Unexpected $export poll status " + status,
							"GET",
							pollUrl,
							status,
							null,
							poll.body()));
					throw new IllegalStateException("Unexpected $export poll status " + status);
				}
			}
			if (manifest == null) {
				throw new IllegalStateException("$export did not complete within the poll window");
			}

			int fileCount = manifest.path("output").size();
			steps.accept(StepEvent.info(
					"EXPORT", "Server export complete: " + fileCount + " output file" + (fileCount == 1 ? "" : "s")));

			BatchEmitter emitter = new BatchEmitter(resourceSink);
			for (JsonNode output : manifest.path("output")) {
				String fileUrl = output.path("url").asText(null);
				if (fileUrl == null) {
					continue;
				}
				String fileType = output.path("type").asText(null);
				steps.accept(StepEvent.progress(
						"EXPORT", "Downloading export file" + (fileType != null ? " (" + fileType + ")" : "") + "..."));
				long fileStart = System.nanoTime();
				HttpResponse<String> file = withRetry(
						"EXPORT",
						"export file download",
						steps,
						() -> http.send(
								HttpRequest.newBuilder(URI.create(fileUrl))
										.timeout(httpTimeout)
										.header("Accept", "application/fhir+ndjson")
										.GET()
										.build(),
								HttpResponse.BodyHandlers.ofString()));
				requests++;
				int fileStatus = file.statusCode();
				long fileBytes = file.body().length();
				if (fileStatus < 200 || fileStatus >= 300) {
					steps.accept(StepEvent.failure(
							"EXPORT",
							"Export file download failed" + (fileType != null ? " (" + fileType + ")" : "") + ": HTTP "
									+ fileStatus,
							"GET",
							fileUrl,
							fileStatus,
							(System.nanoTime() - fileStart) / 1_000_000,
							file.body()));
					throw new IllegalStateException(
							"Failed to download export file " + fileUrl + ": HTTP " + fileStatus);
				}
				bytes += fileBytes;
				int before = emitter.count();
				for (String line : file.body().split("\n")) {
					if (line.isBlank()) {
						continue;
					}
					FetchedResource resource = toFetched(line.trim(), serverKey, parser);
					if (resource != null) {
						emitter.add(resource);
					}
				}
				steps.accept(StepEvent.request(
						"EXPORT",
						"Downloaded export file" + (fileType != null ? " (" + fileType + ")" : ""),
						"GET",
						fileUrl,
						fileStatus,
						(System.nanoTime() - fileStart) / 1_000_000,
						fileBytes,
						emitter.count() - before));
			}
			emitter.flush();
			return new SearchResult(emitter.count(), bytes, requests, 0);
		} catch (IOException e) {
			throw new UncheckedIOException("Bulk export failed for " + serverUrl, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Bulk export interrupted", e);
		}
	}

	/** Current-state resources plus deletions discovered while paging _history. */
	public record HistoryResult(int records, List<DeletionEntry> deletions, long bytes, int requests, int pages) {}

	/**
	 * Page system _history and collapse each resource to its latest state. When {@code since}
	 * is null this is a full snapshot; otherwise only changes after the anchor are returned,
	 * yielding an incremental delta (upserts + deletions). History is returned newest-first,
	 * so the first entry seen per key wins; an entry with no resource body is a deletion.
	 */
	public HistoryResult historyExport(
			String serverUrl,
			String serverKey,
			String since,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		IGenericClient client = newClient(serverUrl);
		IParser parser = fhirContext.newJsonParser();
		BatchEmitter emitter = new BatchEmitter(resourceSink);
		List<DeletionEntry> deletions = new ArrayList<>();
		Set<String> seenKeys = new HashSet<>();
		Set<String> seenUrls = new HashSet<>();
		int requests = 0;
		int pages = 0;
		long bytes = 0;
		long startNanos = System.nanoTime();

		var history = client.history().onServer().andReturnBundle(Bundle.class).count(500);
		if (since != null) {
			history = history.since(Date.from(parseInstant(since)));
		}
		String historyUrl = serverUrl + "/_history?_count=500" + (since != null ? "&_since=" + since : "");
		steps.accept(StepEvent.progress(
				"HISTORY",
				since != null ? "Paging system _history since the anchor..." : "Paging full system _history..."));
		var pagedHistory = history;
		Bundle bundle;
		try {
			bundle = withRetryUnchecked("HISTORY", "history export", steps, pagedHistory::execute);
		} catch (BaseServerResponseException e) {
			steps.accept(serverError("HISTORY", "System _history export failed", historyUrl, startNanos, e));
			throw e;
		}
		while (bundle != null) {
			requests++;
			pages++;
			if (pages % PAGE_LOG_EVERY == 0) {
				ourLog.info("HISTORY: {} pages fetched, {} resources so far", pages, emitter.count());
			}
			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				if (isDeletion(entry)) {
					DeletionEntry deletion = parseReference(
							entry.getRequest() != null ? entry.getRequest().getUrl() : entry.getFullUrl());
					if (deletion != null
							&& PLAN_NET_TYPES.contains(deletion.resourceType())
							&& seenKeys.add(serverKey + "|" + deletion.resourceType() + "/" + deletion.id())) {
						deletions.add(deletion);
					}
					continue;
				}
				Resource resource = entry.getResource();
				if (resource == null
						|| resource.getIdElement() == null
						|| resource.getIdElement().getIdPart() == null) {
					continue;
				}
				String type = resource.fhirType();
				if (!PLAN_NET_TYPES.contains(type)) {
					continue;
				}
				String key =
						serverKey + "|" + type + "/" + resource.getIdElement().getIdPart();
				if (!seenKeys.add(key)) {
					continue;
				}
				String json = parser.encodeResourceToString(resource);
				bytes += json.length();
				String versionId = resource.getMeta() != null
						? emptyToNull(resource.getMeta().getVersionId())
						: null;
				String lastUpdated =
						resource.getMeta() != null && resource.getMeta().hasLastUpdated()
								? resource.getMeta().getLastUpdatedElement().getValueAsString()
								: null;
				emitter.add(new FetchedResource(
						key, type, resource.getIdElement().getIdPart(), versionId, lastUpdated, json, json.length()));
			}
			Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
			if (next == null || next.getUrl() == null || seenUrls.contains(next.getUrl())) {
				break;
			}
			seenUrls.add(next.getUrl());
			pauseBetweenPages();
			steps.accept(StepEvent.progress("HISTORY", "Fetching history page " + (pages + 1) + "..."));
			Bundle page = bundle;
			try {
				bundle = withRetryUnchecked("HISTORY", "history page fetch", steps, () -> client.loadPage()
						.next(page)
						.execute());
			} catch (BaseServerResponseException e) {
				steps.accept(serverError("HISTORY", "System _history page fetch failed", next.getUrl(), startNanos, e));
				throw e;
			}
		}

		emitter.flush();
		steps.accept(StepEvent.request(
				"HISTORY",
				(since != null ? "Paged system _history since the anchor" : "Paged full system _history")
						+ " (" + pages + " page" + (pages == 1 ? "" : "s") + "): "
						+ emitter.count() + " current, " + deletions.size() + " deleted",
				"GET",
				serverUrl + "/_history?_count=500" + (since != null ? "&_since=" + since : ""),
				200,
				(System.nanoTime() - startNanos) / 1_000_000,
				bytes,
				emitter.count()));
		return new HistoryResult(emitter.count(), deletions, bytes, requests, pages);
	}

	private FetchedResource toFetched(String json, String serverKey, IParser parser) {
		try {
			Resource resource = (Resource) parser.parseResource(json);
			if (resource.getIdElement() == null || resource.getIdElement().getIdPart() == null) {
				return null;
			}
			String type = resource.fhirType();
			String id = resource.getIdElement().getIdPart();
			String versionId =
					resource.getMeta() != null ? emptyToNull(resource.getMeta().getVersionId()) : null;
			String lastUpdated =
					resource.getMeta() != null && resource.getMeta().hasLastUpdated()
							? resource.getMeta().getLastUpdatedElement().getValueAsString()
							: null;
			return new FetchedResource(
					serverKey + "|" + type + "/" + id, type, id, versionId, lastUpdated, json, json.length());
		} catch (Exception e) {
			return null;
		}
	}

	private static int parseRetryAfter(String value) {
		if (value == null || value.isBlank()) {
			return POLL_DEFAULT_WAIT_SECONDS;
		}
		String trimmed = value.trim();
		if (trimmed.chars().allMatch(Character::isDigit)) {
			return Math.max(1, Integer.parseInt(trimmed));
		}
		try {
			Instant when = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed));
			return (int)
					Math.max(1, Math.min(Duration.between(Instant.now(), when).getSeconds(), 60));
		} catch (Exception e) {
			return POLL_DEFAULT_WAIT_SECONDS;
		}
	}
}

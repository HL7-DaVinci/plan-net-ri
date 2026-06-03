package org.hl7.davinci.api.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.client.interceptor.CapturingInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
	private static final int POLL_MAX_ATTEMPTS = 120;
	private static final int POLL_DEFAULT_WAIT_SECONDS = 2;

	private final FhirContext fhirContext;
	private final ObjectMapper objectMapper;

	public FhirCrawlClient(FhirContext fhirContext, ObjectMapper objectMapper) {
		this.fhirContext = fhirContext;
		this.objectMapper = objectMapper;
	}

	public record ServerTime(String iso, String source) {}

	public record SearchResult(List<FetchedResource> fetched, long bytes, int requests, int pages) {}

	IGenericClient newClient(String serverUrl) {
		return fhirContext.newRestfulGenericClient(serverUrl);
	}

	HttpClient newBulkHttpClient() {
		return HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
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
				for (Map.Entry<String, List<String>> header : response.getAllHeaders().entrySet()) {
					if ("date".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
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
			String serverUrl, String serverKey, int pageSize, String since, Consumer<StepEvent> steps) {
		IGenericClient client = newClient(serverUrl);
		List<FetchedResource> fetched = new ArrayList<>();
		long[] bytes = {0};
		int requests = 0;
		int pages = 0;

		IParser parser = fhirContext.newJsonParser();

		for (String type : PLAN_NET_TYPES) {
			Set<String> seen = new HashSet<>();
			StringBuilder query = new StringBuilder(type)
					.append("?_count=")
					.append(pageSize)
					.append("&_sort=_lastUpdated");
			if (since != null) {
				// Inclusive (ge) so a resource stamped exactly at the anchor instant is never skipped;
				// DiffUtil marks an unchanged re-fetch as no-op, so the boundary overlap is harmless.
				query.append("&_lastUpdated=ge").append(since);
			}

			long typeStartNanos = System.nanoTime();
			int beforeCount = fetched.size();
			long beforeBytes = bytes[0];

			Bundle bundle =
					client.search().byUrl(query.toString()).returnBundle(Bundle.class).execute();
			int typePages = 0;
			while (bundle != null) {
				requests++;
				pages++;
				typePages++;
				collectEntries(bundle, serverKey, parser, fetched, bytes);

				Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
				if (next == null || next.getUrl() == null || seen.contains(next.getUrl())) {
					break;
				}
				if (typePages >= MAX_PAGES_PER_TYPE) {
					ourLog.warn("Hit page cap for {} on {}", type, serverKey);
					break;
				}
				seen.add(next.getUrl());
				bundle = client.loadPage().next(bundle).execute();
			}

			int typeCount = fetched.size() - beforeCount;
			steps.accept(StepEvent.request(
					"SEARCH",
					(since != null ? "Searched " + type + " changed since the anchor" : "Searched all " + type)
							+ " (" + typePages + " page" + (typePages == 1 ? "" : "s") + ")",
					"GET",
					serverUrl + "/" + query,
					200,
					(System.nanoTime() - typeStartNanos) / 1_000_000,
					bytes[0] - beforeBytes,
					typeCount));
		}

		return new SearchResult(fetched, bytes[0], requests, pages);
	}

	private void collectEntries(
			Bundle bundle, String serverKey, IParser parser, List<FetchedResource> out, long[] bytes) {
		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();
			if (resource == null || resource.getIdElement() == null || resource.getIdElement().getIdPart() == null) {
				continue;
			}
			String type = resource.fhirType();
			String id = resource.getIdElement().getIdPart();
			String versionId = resource.getMeta() != null ? emptyToNull(resource.getMeta().getVersionId()) : null;
			String lastUpdated = resource.getMeta() != null && resource.getMeta().hasLastUpdated()
					? resource.getMeta().getLastUpdatedElement().getValueAsString()
					: null;
			String json = parser.encodeResourceToString(resource);
			bytes[0] += json.length();
			out.add(new FetchedResource(
					serverKey + "|" + type + "/" + id, type, id, versionId, lastUpdated, json, json.length()));
		}
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	public record DeletionScanResult(List<DeletionEntry> deletions, int requests, int pages, long bytes) {}

	/**
	 * Walk system _history since the anchor and collect deletions. An entry with no
	 * resource body is treated as a deletion. Throws {@link HistoryUnsupportedException}
	 * if the server rejects _history.
	 */
	public DeletionScanResult scanDeletions(
			String serverUrl, String since, int pageSize, Consumer<StepEvent> steps) {
		IGenericClient client = newClient(serverUrl);
		List<DeletionEntry> deletions = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int requests = 0;
		int pages = 0;
		long startNanos = System.nanoTime();

		Bundle bundle;
		try {
			bundle = client.history()
					.onServer()
					.andReturnBundle(Bundle.class)
					.since(Date.from(parseInstant(since)))
					.count(pageSize)
					.execute();
		} catch (BaseServerResponseException e) {
			if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
				throw new HistoryUnsupportedException(serverUrl);
			}
			throw e;
		}

		while (bundle != null) {
			requests++;
			pages++;
			extractDeletions(bundle, deletions);

			Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
			if (next == null || next.getUrl() == null || seen.contains(next.getUrl())) {
				break;
			}
			seen.add(next.getUrl());
			bundle = client.loadPage().next(bundle).execute();
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

	private static void extractDeletions(Bundle bundle, List<DeletionEntry> out) {
		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			if (entry.getResource() != null) {
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
	public SearchResult bulkExport(String serverUrl, String serverKey, Consumer<StepEvent> steps) {
		HttpClient http = newBulkHttpClient();
		IParser parser = fhirContext.newJsonParser();
		int requests = 0;
		long bytes = 0;
		try {
			String typeParam = URLEncoder.encode(String.join(",", PLAN_NET_TYPES), StandardCharsets.UTF_8);
			String kickoffUrl = serverUrl + "/$export?_type=" + typeParam;
			long kickStart = System.nanoTime();
			HttpResponse<String> kickoff = http.send(
					HttpRequest.newBuilder(URI.create(kickoffUrl))
							.timeout(HTTP_TIMEOUT)
							.header("Accept", "application/fhir+json")
							.header("Prefer", "respond-async")
							.GET()
							.build(),
					HttpResponse.BodyHandlers.ofString());
			requests++;
			steps.accept(StepEvent.request(
					"EXPORT",
					"Kicked off system $export with Prefer: respond-async",
					"GET",
					kickoffUrl,
					kickoff.statusCode(),
					(System.nanoTime() - kickStart) / 1_000_000,
					null,
					null));
			if (kickoff.statusCode() != 202) {
				throw new IllegalStateException("Expected 202 from $export, got " + kickoff.statusCode());
			}
			String pollUrl = kickoff.headers()
					.firstValue("Content-Location")
					.orElseThrow(() -> new IllegalStateException("$export 202 missing Content-Location"));

			JsonNode manifest = null;
			for (int attempt = 1; attempt <= POLL_MAX_ATTEMPTS && manifest == null; attempt++) {
				long pollStart = System.nanoTime();
				HttpResponse<String> poll = http.send(
						HttpRequest.newBuilder(URI.create(pollUrl))
								.timeout(HTTP_TIMEOUT)
								.header("Accept", "application/json")
								.GET()
								.build(),
						HttpResponse.BodyHandlers.ofString());
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
					throw new IllegalStateException("$export failed: HTTP " + status);
				} else if (status == 202) {
					Thread.sleep(parseRetryAfter(retryAfter) * 1000L);
				} else {
					throw new IllegalStateException("Unexpected $export poll status " + status);
				}
			}
			if (manifest == null) {
				throw new IllegalStateException("$export did not complete within the poll window");
			}

			int fileCount = manifest.path("output").size();
			steps.accept(StepEvent.info(
					"EXPORT", "Server export complete: " + fileCount + " output file" + (fileCount == 1 ? "" : "s")));

			List<FetchedResource> fetched = new ArrayList<>();
			for (JsonNode output : manifest.path("output")) {
				String fileUrl = output.path("url").asText(null);
				if (fileUrl == null) {
					continue;
				}
				String fileType = output.path("type").asText(null);
				long fileStart = System.nanoTime();
				HttpResponse<String> file = http.send(
						HttpRequest.newBuilder(URI.create(fileUrl))
								.timeout(HTTP_TIMEOUT)
								.header("Accept", "application/fhir+ndjson")
								.GET()
								.build(),
						HttpResponse.BodyHandlers.ofString());
				requests++;
				int fileStatus = file.statusCode();
				long fileBytes = file.body().length();
				if (fileStatus < 200 || fileStatus >= 300) {
					steps.accept(StepEvent.request(
							"EXPORT",
							"Downloaded export file" + (fileType != null ? " (" + fileType + ")" : ""),
							"GET",
							fileUrl,
							fileStatus,
							(System.nanoTime() - fileStart) / 1_000_000,
							fileBytes,
							null));
					throw new IllegalStateException(
							"Failed to download export file " + fileUrl + ": HTTP " + fileStatus);
				}
				bytes += fileBytes;
				int before = fetched.size();
				for (String line : file.body().split("\n")) {
					if (line.isBlank()) {
						continue;
					}
					FetchedResource resource = toFetched(line.trim(), serverKey, parser);
					if (resource != null) {
						fetched.add(resource);
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
						fetched.size() - before));
			}
			return new SearchResult(fetched, bytes, requests, 0);
		} catch (IOException e) {
			throw new UncheckedIOException("Bulk export failed for " + serverUrl, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Bulk export interrupted", e);
		}
	}

	/** Current-state resources plus deletions discovered while paging _history. */
	public record HistoryResult(
			List<FetchedResource> fetched, List<DeletionEntry> deletions, long bytes, int requests, int pages) {}

	/**
	 * Page system _history and collapse each resource to its latest state. When {@code since}
	 * is null this is a full snapshot; otherwise only changes after the anchor are returned,
	 * yielding an incremental delta (upserts + deletions). History is returned newest-first,
	 * so the first entry seen per key wins; an entry with no resource body is a deletion.
	 */
	public HistoryResult historyExport(
			String serverUrl, String serverKey, String since, Consumer<StepEvent> steps) {
		IGenericClient client = newClient(serverUrl);
		IParser parser = fhirContext.newJsonParser();
		Map<String, FetchedResource> current = new LinkedHashMap<>();
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
		Bundle bundle = history.execute();
		while (bundle != null) {
			requests++;
			pages++;
			for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
				Resource resource = entry.getResource();
				if (resource != null && resource.getIdElement() != null && resource.getIdElement().getIdPart() != null) {
					String type = resource.fhirType();
					if (!PLAN_NET_TYPES.contains(type)) {
						continue;
					}
					String key = serverKey + "|" + type + "/" + resource.getIdElement().getIdPart();
					if (!seenKeys.add(key)) {
						continue;
					}
					String json = parser.encodeResourceToString(resource);
					bytes += json.length();
					String versionId = resource.getMeta() != null ? emptyToNull(resource.getMeta().getVersionId()) : null;
					String lastUpdated = resource.getMeta() != null && resource.getMeta().hasLastUpdated()
							? resource.getMeta().getLastUpdatedElement().getValueAsString()
							: null;
					current.put(
							key,
							new FetchedResource(
									key,
									type,
									resource.getIdElement().getIdPart(),
									versionId,
									lastUpdated,
									json,
									json.length()));
				} else {
					DeletionEntry deletion = parseReference(
							entry.getRequest() != null ? entry.getRequest().getUrl() : entry.getFullUrl());
					if (deletion != null
							&& PLAN_NET_TYPES.contains(deletion.resourceType())
							&& seenKeys.add(serverKey + "|" + deletion.resourceType() + "/" + deletion.id())) {
						deletions.add(deletion);
					}
				}
			}
			Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
			if (next == null || next.getUrl() == null || seenUrls.contains(next.getUrl())) {
				break;
			}
			seenUrls.add(next.getUrl());
			bundle = client.loadPage().next(bundle).execute();
		}

		steps.accept(StepEvent.request(
				"HISTORY",
				(since != null ? "Paged system _history since the anchor" : "Paged full system _history")
						+ " (" + pages + " page" + (pages == 1 ? "" : "s") + "): "
						+ current.size() + " current, " + deletions.size() + " deleted",
				"GET",
				serverUrl + "/_history?_count=500" + (since != null ? "&_since=" + since : ""),
				200,
				(System.nanoTime() - startNanos) / 1_000_000,
				bytes,
				current.size()));
		return new HistoryResult(new ArrayList<>(current.values()), deletions, bytes, requests, pages);
	}

	private FetchedResource toFetched(String json, String serverKey, IParser parser) {
		try {
			Resource resource = (Resource) parser.parseResource(json);
			if (resource.getIdElement() == null || resource.getIdElement().getIdPart() == null) {
				return null;
			}
			String type = resource.fhirType();
			String id = resource.getIdElement().getIdPart();
			String versionId = resource.getMeta() != null ? emptyToNull(resource.getMeta().getVersionId()) : null;
			String lastUpdated = resource.getMeta() != null && resource.getMeta().hasLastUpdated()
					? resource.getMeta().getLastUpdatedElement().getValueAsString()
					: null;
			return new FetchedResource(serverKey + "|" + type + "/" + id, type, id, versionId, lastUpdated, json, json.length());
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
			return (int) Math.max(1, Math.min(Duration.between(Instant.now(), when).getSeconds(), 60));
		} catch (Exception e) {
			return POLL_DEFAULT_WAIT_SECONDS;
		}
	}
}

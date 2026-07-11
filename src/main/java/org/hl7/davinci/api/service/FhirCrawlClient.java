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
import org.hl7.davinci.common.PlanNetTypes;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Outbound FHIR client for crawling external servers. */
@Component
public class FhirCrawlClient {

	private static final Logger ourLog = LoggerFactory.getLogger(FhirCrawlClient.class);

	/** Hard cap on pages per type; a backstop against pagination loops, set high enough not to drop large types. */
	static final int MAX_PAGES_PER_TYPE = 100_000;

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

	/** Parallel per-type paging chains per crawl (api.crawl-concurrency); 1 disables parallelism. */
	private final int crawlConcurrency;

	/** One rate gate per normalized server, shared across every crawl (and every concurrent job) hitting it. */
	private final ConcurrentHashMap<String, RateGate> gates = new ConcurrentHashMap<>();

	public FhirCrawlClient(FhirContext fhirContext, ObjectMapper objectMapper, ApiProperties props) {
		this.fhirContext = fhirContext;
		this.objectMapper = objectMapper;
		this.httpTimeout = Duration.ofMillis(props.getRequestTimeoutMs());
		this.pageDelayMs = props.getPageDelayMs();
		this.crawlConcurrency = props.getCrawlConcurrency();
		// HAPI client timeouts are factory-wide, so clients from newClient() pick these up.
		fhirContext.getRestfulClientFactory().setConnectTimeout(props.getRequestTimeoutMs());
		fhirContext.getRestfulClientFactory().setSocketTimeout(props.getRequestTimeoutMs());
	}

	public record ServerTime(String iso, String source) {}

	public record SearchResult(int records, long bytes, int requests, int pages) {}

	/** One type's (or one file's) fetch outcome, merged into a {@link SearchResult} by the task driver. */
	private record TypeCrawl(int records, long bytes, int requests, int pages) {}

	/** Package-private test seam: verifies the registry reuses one gate per normalized server. */
	RateGate gateFor(String serverUrl) {
		return gates.computeIfAbsent(CrawlService.normalizeServerKey(serverUrl), key -> new RateGate(crawlConcurrency));
	}

	IGenericClient newClient(String serverUrl) {
		return fhirContext.newRestfulGenericClient(serverUrl);
	}

	/** Execute a search by raw URL. Overridable so tests can script paging. */
	Bundle searchByUrl(IGenericClient client, String url) {
		return client.search().byUrl(url).returnBundle(Bundle.class).execute();
	}

	/** Follow a bundle's next link. Overridable so tests can script paging. */
	Bundle loadNextPage(IGenericClient client, Bundle bundle) {
		return client.loadPage().next(bundle).execute();
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
		return withRetry(phase, what, steps, null, call);
	}

	/**
	 * Gate-aware retry: holds the gate's in-flight slot only for the duration of the request
	 * itself (honoring any active pause first), never through the backoff sleep, so a worker
	 * waiting out a long Retry-After does not sit on a permit other chains need. On a 429 the
	 * gate's pause is pushed out so every worker hitting the server backs off, not just this
	 * one; other transient failures (a 500, a timeout) are a single chain's problem and must not
	 * pause siblings. A null gate behaves exactly like the ungated overload.
	 */
	<T> T withRetry(String phase, String what, Consumer<StepEvent> steps, RateGate gate, RetryableCall<T> call)
			throws IOException, InterruptedException {
		for (int attempt = 1; ; attempt++) {
			try {
				return runGated(gate, call);
			} catch (Exception e) {
				if (attempt >= MAX_RETRY_ATTEMPTS || !isTransient(e)) {
					throw e;
				}
				long backoffMs = retryDelayMs(e, RETRY_BACKOFF_MS * attempt);
				if (gate != null && isRateLimited(e)) {
					gate.pause(backoffMs);
				}
				steps.accept(StepEvent.info(
						phase,
						"Transient failure on " + what + " (" + reason(e) + "); retrying in " + (backoffMs / 1000)
								+ "s (attempt " + (attempt + 1) + " of " + MAX_RETRY_ATTEMPTS + ")"));
				Thread.sleep(backoffMs);
			}
		}
	}

	/** Runs the call with the gate's in-flight slot held only around the call itself. */
	private static <T> T runGated(RateGate gate, RetryableCall<T> call) throws IOException, InterruptedException {
		if (gate == null) {
			return call.run();
		}
		gate.acquire();
		try {
			return call.run();
		} finally {
			gate.release();
		}
	}

	private static boolean isRateLimited(Exception e) {
		return e instanceof BaseServerResponseException serverError && serverError.getStatusCode() == 429;
	}

	/** Adapter for the HAPI call sites, whose failures are all unchecked. */
	private <T> T withRetryUnchecked(String phase, String what, Consumer<StepEvent> steps, Supplier<T> call) {
		return withRetryUnchecked(phase, what, steps, null, call);
	}

	/** Gate-aware adapter for the HAPI call sites, whose failures are all unchecked. */
	private <T> T withRetryUnchecked(
			String phase, String what, Consumer<StepEvent> steps, RateGate gate, Supplier<T> call) {
		try {
			return withRetry(phase, what, steps, gate, call::get);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Crawl interrupted during retry backoff", e);
		}
	}

	/**
	 * Timeouts, dropped connections, rate limiting (429), and any 5xx are worth retrying;
	 * the rest fail fast. All crawl requests are idempotent GETs, so retrying a 500 is safe;
	 * servers commonly return 500 for internal timeouts that resolve on a later attempt
	 * (e.g. HAPI's 60s search coordinator limit, where the search keeps building in the
	 * background and a re-request of the same page succeeds).
	 */
	static boolean isTransient(Exception e) {
		if (e instanceof FhirClientConnectionException || e instanceof TransientHttpStatusException) {
			return true;
		}
		if (e instanceof BaseServerResponseException serverError) {
			int status = serverError.getStatusCode();
			return status == 429 || status >= 500;
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

	/**
	 * A 429 or 5xx returned as a normal response by the raw bulk HttpClient, thrown inside a
	 * {@link #withRetry} callable so status-class failures retry like their HAPI-client
	 * counterparts (which surface as thrown {@code BaseServerResponseException}s).
	 */
	static final class TransientHttpStatusException extends IOException {
		private final int status;
		private final String body;

		TransientHttpStatusException(String message, int status, String body) {
			super(message);
			this.status = status;
			this.body = body;
		}

		int status() {
			return status;
		}

		String body() {
			return body;
		}
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
	public ServerTime getServerTime(String serverUrl, Consumer<StepEvent> steps) {
		IGenericClient client = newClient(serverUrl);
		CapturingInterceptor capture = new CapturingInterceptor();
		client.registerInterceptor(capture);
		try {
			CapabilityStatement cs =
					client.capabilities().ofType(CapabilityStatement.class).execute();
			reportUndeclaredTypes(cs, steps);

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

	/**
	 * Advisory check: reports Plan-Net types the CapabilityStatement does not declare. Every type
	 * is still crawled regardless, since a statement can be incomplete; the actual per-type query
	 * confirms or refutes this hint.
	 */
	static void reportUndeclaredTypes(CapabilityStatement cs, Consumer<StepEvent> steps) {
		Set<String> declared = new HashSet<>();
		for (CapabilityStatement.CapabilityStatementRestComponent rest : cs.getRest()) {
			if (rest.getMode() == CapabilityStatement.RestfulCapabilityMode.SERVER) {
				for (CapabilityStatement.CapabilityStatementRestResourceComponent resource : rest.getResource()) {
					if (resource.getType() != null) {
						declared.add(resource.getType());
					}
				}
			}
		}
		if (declared.isEmpty()) {
			return;
		}
		List<String> undeclared =
				PlanNetTypes.TYPES.stream().filter(t -> !declared.contains(t)).toList();
		if (!undeclared.isEmpty()) {
			steps.accept(StepEvent.info(
					"METADATA",
					"CapabilityStatement does not declare " + String.join(", ", undeclared)
							+ "; attempting anyway in case the statement is incomplete"));
		}
	}

	private static Instant parseHttpDate(String value) {
		try {
			return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Entry-phase probe for the partitioned last-updated strategy: exercises the parameters the
	 * watermark queries need. A server that returns 200 while silently ignoring _lastUpdated
	 * passes; the watermark loop's bound-violation guard is the backstop for that case.
	 */
	public boolean probePartitionedSearch(String serverUrl, Consumer<StepEvent> steps) {
		return probeSearch(serverUrl, "?_count=1&_total=none&_sort=_lastUpdated&_lastUpdated=ge1970-01-01", steps);
	}

	/** Entry-phase probe for plain paged search: one minimal query per type until one succeeds. */
	public boolean probePlainSearch(String serverUrl, Consumer<StepEvent> steps) {
		return probeSearch(serverUrl, "?_count=1", steps);
	}

	/**
	 * Walks the Plan-Net types issuing one cheap query per type until one returns successfully
	 * (the strategy is viable) or every type is rejected with an unsupported-class 4xx (it is
	 * not). A 429 or 5xx that survives retries propagates: transient failures fail the run
	 * instead of silently demoting the server to a weaker strategy.
	 */
	private boolean probeSearch(String serverUrl, String queryShape, Consumer<StepEvent> steps) {
		IGenericClient client = newClient(serverUrl);
		RateGate gate = gateFor(serverUrl);
		for (String type : PlanNetTypes.TYPES) {
			String query = type + queryShape;
			String url = serverUrl + "/" + query;
			long start = System.nanoTime();
			try {
				withRetryUnchecked("STRATEGY", "strategy probe", steps, gate, () -> searchByUrl(client, query));
				steps.accept(StepEvent.request(
						"STRATEGY",
						"Probe query succeeded",
						"GET",
						url,
						200,
						(System.nanoTime() - start) / 1_000_000,
						null,
						null));
				return true;
			} catch (BaseServerResponseException e) {
				if (e.getStatusCode() == 429 || e.getStatusCode() >= 500) {
					throw e;
				}
				steps.accept(serverError("STRATEGY", "Probe query rejected for " + type, url, start, e));
			}
		}
		return false;
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
		RateGate gate = gateFor(serverUrl);
		List<Callable<TypeCrawl>> tasks = new ArrayList<>();
		for (String type : PlanNetTypes.TYPES) {
			tasks.add(() -> runSearchType(type, serverUrl, serverKey, pageSize, since, gate, steps, resourceSink));
		}
		return runTypeTasks(tasks);
	}

	/** One type's link-paged search, from the initial query through its last page (or failure). */
	private TypeCrawl runSearchType(
			String type,
			String serverUrl,
			String serverKey,
			int pageSize,
			String since,
			RateGate gate,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		Consumer<StepEvent> trackSteps = ev -> steps.accept(ev.track() == null ? ev.withTrack(type) : ev);
		IGenericClient client = newClient(serverUrl);
		IParser parser = fhirContext.newJsonParser();
		BatchEmitter emitter = new BatchEmitter(resourceSink);
		long[] bytes = {0};
		int requests = 0;
		int pages = 0;
		int typePages = 0;

		Set<String> seen = new HashSet<>();
		StringBuilder query =
				new StringBuilder(type).append("?_count=").append(pageSize).append("&_sort=_lastUpdated");
		if (since != null) {
			// Inclusive (ge) so a resource stamped exactly at the anchor instant is never skipped;
			// DiffUtil marks an unchanged re-fetch as no-op, so the boundary overlap is harmless.
			query.append("&_lastUpdated=ge").append(since);
		}

		long typeStartNanos = System.nanoTime();
		try {
			trackSteps.accept(StepEvent.progress(
					"SEARCH",
					(since != null ? "Searching " + type + " changes since the anchor" : "Searching all " + type)
							+ "...",
					"GET",
					serverUrl + "/" + query));
			try {
				Bundle bundle = withRetryUnchecked("SEARCH", type + " search", trackSteps, gate, () -> client.search()
						.byUrl(query.toString())
						.returnBundle(Bundle.class)
						.execute());
				// Single-slot prefetch: once a page is in hand, the next page's fetch runs on this
				// background thread while the current page is parsed and buffered, so the network
				// wait overlaps processing instead of blocking it.
				ExecutorService prefetch = newPrefetchExecutor();
				Map<String, String> mdcContext = MDC.getCopyOfContextMap();
				try {
					while (bundle != null) {
						requests++;
						pages++;
						typePages++;

						Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
						boolean linkAvailable = next != null && next.getUrl() != null && !seen.contains(next.getUrl());
						if (linkAvailable && typePages >= MAX_PAGES_PER_TYPE) {
							ourLog.warn("Hit page cap for {} on {}", type, serverKey);
							linkAvailable = false;
						}

						Future<Bundle> nextFuture = null;
						if (linkAvailable) {
							seen.add(next.getUrl());
							String nextUrl = next.getUrl();
							trackSteps.accept(StepEvent.progress(
									"SEARCH", "Fetching " + type + " page " + (typePages + 1), "GET", nextUrl));
							Bundle current = bundle;
							nextFuture = prefetch.submit(withMdc(
									() -> {
										pauseBetweenPages();
										return withRetryUnchecked(
												"SEARCH",
												type + " page fetch",
												trackSteps,
												gate,
												() -> client.loadPage()
														.next(current)
														.execute());
									},
									mdcContext));
						}

						collectEntries(bundle, serverKey, parser, emitter, bytes);
						if (typePages % PAGE_LOG_EVERY == 0) {
							ourLog.info(
									"SEARCH {}: {} pages fetched, {} resources so far",
									type,
									typePages,
									emitter.count());
						}

						bundle = nextFuture == null ? null : awaitPrefetch(nextFuture);
					}
				} finally {
					prefetch.shutdownNow();
				}
			} catch (BaseServerResponseException e) {
				// Degrade gracefully: surface the failure as a step and keep crawling the remaining types.
				trackSteps.accept(serverError(
						"SEARCH",
						"Search failed for " + type + " after " + typePages
								+ " page(s); skipping the rest of this type",
						serverUrl + "/" + query,
						typeStartNanos,
						e));
				return new TypeCrawl(emitter.count(), bytes[0], requests, pages);
			}

			trackSteps.accept(StepEvent.request(
					"SEARCH",
					(since != null ? "Searched " + type + " changed since the anchor" : "Searched all " + type) + " ("
							+ typePages + " page" + (typePages == 1 ? "" : "s") + ")",
					"GET",
					serverUrl + "/" + query,
					200,
					(System.nanoTime() - typeStartNanos) / 1_000_000,
					bytes[0],
					emitter.count()));
			return new TypeCrawl(emitter.count(), bytes[0], requests, pages);
		} finally {
			// A per-type failure or an early return must not strand a partial buffered page.
			emitter.flush();
		}
	}

	/**
	 * Search every Plan-Net type, paging by an advancing {@code _lastUpdated} watermark instead of
	 * page links. {@code since} sets the starting watermark; null is a full snapshot.
	 */
	public SearchResult searchTypesByLastUpdated(
			String serverUrl,
			String serverKey,
			int pageSize,
			String since,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		RateGate gate = gateFor(serverUrl);
		List<Callable<TypeCrawl>> tasks = new ArrayList<>();
		for (String type : PlanNetTypes.TYPES) {
			tasks.add(() -> runSearchTypeByLastUpdated(
					type, serverUrl, serverKey, pageSize, since, null, type, true, gate, steps, resourceSink));
		}
		return runTypeTasks(tasks);
	}

	/**
	 * One type's (or, under partitioning, one time window's) watermark-paged search, from the
	 * initial query through its last page (or failure). A null {@code upperBound} searches to the
	 * present, matching {@link #searchTypesByLastUpdated}; {@code track} labels the step's play-by-play
	 * line, letting a partitioned type's windows show up as separate lines instead of colliding.
	 * When {@code persistSummary} is false the successful resolution is broadcast-only, so a
	 * many-window type reports one persisted rollup instead of one step per window; failures are
	 * always persisted.
	 */
	private TypeCrawl runSearchTypeByLastUpdated(
			String type,
			String serverUrl,
			String serverKey,
			int pageSize,
			String since,
			String upperBound,
			String track,
			boolean persistSummary,
			RateGate gate,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		Consumer<StepEvent> trackSteps = ev -> steps.accept(ev.track() == null ? ev.withTrack(track) : ev);
		IGenericClient client = newClient(serverUrl);
		IParser parser = fhirContext.newJsonParser();
		BatchEmitter emitter = new BatchEmitter(resourceSink);
		long[] bytes = {0};
		int requests = 0;
		int pages = 0;
		int typePages = 0;
		long typeStartNanos = System.nanoTime();
		String windowQuery = lastUpdatedQuery(type, pageSize, since, upperBound);
		String scope = upperBound != null
				? type + " window " + (since != null ? since : "start") + " to " + upperBound
				: (since != null ? type + " changed since the anchor" : "all " + type);
		try {
			trackSteps.accept(StepEvent.progress("SEARCH", "Searching " + scope + " by last updated..."));

			String watermark = since;
			Date watermarkInstant = since != null ? Date.from(parseInstant(since)) : null;
			Date upperBoundInstant = upperBound != null ? Date.from(parseInstant(upperBound)) : null;
			try {
				String url = windowQuery;
				trackSteps.accept(StepEvent.progress(
						"SEARCH", "Searching " + scope + " by last updated", "GET", serverUrl + "/" + url));
				Bundle bundle = withRetryUnchecked(
						"SEARCH", type + " search", trackSteps, gate, () -> searchByUrl(client, url));
				Set<String> seenNext = new HashSet<>();
				int maxPageRows = 0;
				// Single-slot prefetch: once the next watermark query or cluster-follow link is known,
				// its fetch runs on this background thread while the current page is parsed and buffered.
				ExecutorService prefetch = newPrefetchExecutor();
				Map<String, String> mdcContext = MDC.getCopyOfContextMap();
				try {
					while (bundle != null) {
						int pageRows = matchedResources(bundle, type).size();
						if (pageRows == 0) {
							break;
						}
						requests++;
						pages++;
						typePages++;
						maxPageRows = Math.max(maxPageRows, pageRows);

						Future<Bundle> nextFuture = null;
						if (typePages >= MAX_PAGES_PER_TYPE) {
							ourLog.warn("Hit page cap for {} on {}", type, serverKey);
						} else {
							// Terminate on the keyset logic, never on page size: a server may cap pages below
							// the requested _count, so a short page is not the end of the data.
							LastUpdated pageMax = maxLastUpdated(bundle, type);
							// Compare parsed instants, not the verbatim strings: one instant rendered with
							// different offsets/precision across rows would otherwise stall the watermark.
							if (pageMax != null
									&& upperBoundInstant != null
									&& !pageMax.instant().before(upperBoundInstant)) {
								// The server violated the lt bound; advancing the watermark from this page
								// would leap past the window's remaining data, so end the window loudly.
								trackSteps.accept(StepEvent.info(
										"SEARCH",
										type + " page contains entries at or past the window bound " + upperBound
												+ "; the server did not honor the _lastUpdated filter, ending"
												+ " this window"));
							} else if (pageMax != null
									&& (watermarkInstant == null
											|| pageMax.instant().after(watermarkInstant))) {
								watermark = pageMax.value();
								watermarkInstant = pageMax.instant();
								String reUrl = lastUpdatedQuery(type, pageSize, watermark, upperBound);
								trackSteps.accept(StepEvent.progress(
										"SEARCH",
										"Searching " + scope + " by last updated",
										"GET",
										serverUrl + "/" + reUrl));
								nextFuture = prefetch.submit(withMdc(
										() -> {
											pauseBetweenPages();
											return withRetryUnchecked(
													"SEARCH",
													type + " search",
													trackSteps,
													gate,
													() -> searchByUrl(client, reUrl));
										},
										mdcContext));
							} else {
								// The whole page sits at one _lastUpdated instant the keyset window cannot pass,
								// so follow link[next] through the cluster instead of dropping the rest of the type.
								Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
								if (next != null && next.getUrl() != null && seenNext.add(next.getUrl())) {
									trackSteps.accept(StepEvent.progress(
											"SEARCH",
											"Paging " + type + " _lastUpdated cluster",
											"GET",
											next.getUrl()));
									Bundle current = bundle;
									nextFuture = prefetch.submit(withMdc(
											() -> {
												pauseBetweenPages();
												return withRetryUnchecked(
														"SEARCH",
														type + " page fetch",
														trackSteps,
														gate,
														() -> loadNextPage(client, current));
											},
											mdcContext));
								} else if (typePages > 1 && pageRows >= maxPageRows) {
									// A full page entirely at one instant with no next link: the server has more
									// rows at this instant it will not page to. Surface it rather than dropping
									// silently.
									ourLog.warn(
											"Possible truncation: a full page of {} {} resources shares one _lastUpdated"
													+ " instant on {} with no next link",
											pageRows,
											type,
											serverKey);
									trackSteps.accept(StepEvent.info(
											"SEARCH",
											"Possible truncation in " + type
													+ ": more than a page of resources share one"
													+ " _lastUpdated instant and the server returned no paging link;"
													+ " some resources at that instant may be missing"));
								}
							}
						}

						collectEntries(bundle, serverKey, parser, emitter, bytes);
						if (typePages % PAGE_LOG_EVERY == 0) {
							ourLog.info(
									"SEARCH {}: {} pages by last updated, {} resources so far",
									type,
									typePages,
									emitter.count());
						}
						bundle = nextFuture == null ? null : awaitPrefetch(nextFuture);
					}
				} finally {
					prefetch.shutdownNow();
				}
			} catch (BaseServerResponseException e) {
				// Degrade gracefully: surface the failure as a step and keep crawling the remaining types.
				trackSteps.accept(serverError(
						"SEARCH",
						"Search failed for " + scope + " after " + typePages + " page(s); skipping the rest of this "
								+ (upperBound != null ? "window" : "type"),
						serverUrl + "/" + windowQuery,
						typeStartNanos,
						e));
				return new TypeCrawl(emitter.count(), bytes[0], requests, pages);
			}

			StepEvent resolution = StepEvent.request(
					"SEARCH",
					"Searched " + scope + " by last updated (" + typePages + " page" + (typePages == 1 ? "" : "s")
							+ ")",
					"GET",
					serverUrl + "/" + windowQuery,
					200,
					(System.nanoTime() - typeStartNanos) / 1_000_000,
					bytes[0],
					emitter.count());
			trackSteps.accept(persistSummary ? resolution : resolution.asProgress());
			return new TypeCrawl(emitter.count(), bytes[0], requests, pages);
		} finally {
			// A per-type failure or an early return must not strand a partial buffered page.
			emitter.flush();
		}
	}

	/**
	 * Search every Plan-Net type, splitting each into census-sized {@code _lastUpdated} windows
	 * (via {@link #planWindows}) and paging every window's watermark chain in parallel through the
	 * same task driver as the other strategies. {@code since} sets the starting lower bound; null
	 * plans from each type's earliest resource.
	 *
	 * <p>The upper bound trusts {@code anchor} only when it came from the server's own Date header:
	 * a stale or client-side clock is not safe as a snapshot boundary, so in that case the plan is
	 * still bisected against the client clock (to size the windows) but the final window per type is
	 * left open-ended, matching the unbounded coverage of the other search strategies. Anything a
	 * bounded run misses is caught by the next incremental crawl.
	 */
	public SearchResult searchTypesPartitioned(
			String serverUrl,
			String serverKey,
			int pageSize,
			String since,
			ServerTime anchor,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		RateGate gate = gateFor(serverUrl);
		IGenericClient planningClient = newClient(serverUrl);
		boolean trustedUpperBound = "date-header".equals(anchor.source());
		String hi = trustedUpperBound ? anchor.iso() : Instant.now().toString();
		boolean openTail = !trustedUpperBound;

		AtomicInteger planningRequests = new AtomicInteger();
		List<Callable<TypeCrawl>> tasks = new ArrayList<>();
		for (String type : PlanNetTypes.TYPES) {
			List<Window> windows = planTypeWindows(
					planningClient, serverUrl, type, since, hi, openTail, gate, steps, planningRequests);
			int windowCount = windows.size();
			if (windowCount == 0) {
				continue;
			}
			// Window resolutions are broadcast-only; the type persists one rollup step when its last
			// window completes, keeping the stored timeline at one summary per type.
			AtomicInteger remainingWindows = new AtomicInteger(windowCount);
			AtomicLong typeRecords = new AtomicLong();
			AtomicLong typeBytes = new AtomicLong();
			AtomicLong typePages = new AtomicLong();
			AtomicLong typeStartNanos = new AtomicLong();
			for (int i = 0; i < windowCount; i++) {
				Window window = windows.get(i);
				String track = windowCount == 1 ? type : type + " [" + (i + 1) + "/" + windowCount + "]";
				tasks.add(() -> {
					typeStartNanos.compareAndSet(0, System.nanoTime());
					TypeCrawl crawl = runSearchTypeByLastUpdated(
							type,
							serverUrl,
							serverKey,
							pageSize,
							window.lo(),
							window.hi(),
							track,
							false,
							gate,
							steps,
							resourceSink);
					typeRecords.addAndGet(crawl.records());
					typeBytes.addAndGet(crawl.bytes());
					typePages.addAndGet(crawl.pages());
					if (remainingWindows.decrementAndGet() == 0) {
						long pages = typePages.get();
						steps.accept(StepEvent.request(
										"SEARCH",
										"Searched " + type + " by last updated across " + windowCount + " window"
												+ (windowCount == 1 ? "" : "s") + " (" + pages + " page"
												+ (pages == 1 ? "" : "s") + ")",
										"GET",
										serverUrl + "/" + type,
										200,
										(System.nanoTime() - typeStartNanos.get()) / 1_000_000,
										typeBytes.get(),
										(int) typeRecords.get())
								.withTrack(type));
					}
					return crawl;
				});
			}
		}
		SearchResult tasksResult = runTypeTasks(tasks);
		return new SearchResult(
				tasksResult.records(),
				tasksResult.bytes(),
				tasksResult.requests() + planningRequests.get(),
				tasksResult.pages());
	}

	/**
	 * Plans one type's crawl windows: counts the type once via {@link #countType} and slices the
	 * crawl range into uniform time windows targeting {@link #MAX_WINDOW_SIZE} resources each.
	 * Density is uneven in practice, but watermark paging absorbs oversized windows, so balance is
	 * best-effort. The earliest-resource lookup runs only when slicing is actually needed (more
	 * than one window and no incremental {@code since} to anchor the cuts); a single-window type
	 * searches unbounded below. When no count form works the type is still sliced across the
	 * worker pool; an earliest-lookup failure degrades to a single window since without a lower
	 * bound there is no range to slice.
	 */
	private List<Window> planTypeWindows(
			IGenericClient client,
			String serverUrl,
			String type,
			String since,
			String hi,
			boolean openTail,
			RateGate gate,
			Consumer<StepEvent> steps,
			AtomicInteger planningRequests) {
		Consumer<StepEvent> trackSteps = ev -> steps.accept(ev.track() == null ? ev.withTrack(type) : ev);
		long total = countType(client, serverUrl, type, since, hi, gate, trackSteps, planningRequests);
		if (total == 0) {
			trackSteps.accept(StepEvent.info("SEARCH", "No " + type + " resources to crawl"));
			return List.of();
		}
		int target = total < 0
				? Math.max(1, crawlConcurrency)
				: (int) Math.min(MAX_WINDOWS_PER_TYPE, (total + MAX_WINDOW_SIZE - 1) / MAX_WINDOW_SIZE);
		String lo = since;
		if (target > 1 && lo == null) {
			String minUrl = type + "?_count=1&_sort=_lastUpdated&_total=none";
			long startNanos = System.nanoTime();
			trackSteps.accept(StepEvent.progress(
					"SEARCH", "Looking up the earliest " + type + " by last updated", "GET", serverUrl + "/" + minUrl));
			planningRequests.incrementAndGet();
			try {
				Bundle minBundle = withRetryUnchecked(
						"SEARCH", type + " earliest lookup", trackSteps, gate, () -> searchByUrl(client, minUrl));
				LastUpdated earliest = maxLastUpdated(minBundle, type);
				if (earliest == null) {
					trackSteps.accept(StepEvent.info("SEARCH", "No " + type + " resources found; nothing to crawl"));
					return List.of();
				}
				lo = earliest.value();
			} catch (BaseServerResponseException e) {
				trackSteps.accept(serverError(
						"SEARCH",
						"Earliest-resource lookup failed for " + type + "; using a single window",
						serverUrl + "/" + minUrl,
						startNanos,
						e));
			}
		}
		List<Window> windows = lo == null || target <= 1
				? List.of(new Window(lo, openTail ? null : hi))
				: uniformWindows(lo, hi, target, openTail);
		trackSteps.accept(StepEvent.info(
				"SEARCH",
				total < 0
						? "No count form worked for " + type + "; slicing into " + windows.size() + " uniform window"
								+ (windows.size() == 1 ? "" : "s")
						: "Partitioned " + type + " (" + total + " resources) into " + windows.size() + " window"
								+ (windows.size() == 1 ? "" : "s") + " of ~" + MAX_WINDOW_SIZE));
		return windows;
	}

	/**
	 * Counts a type trying each count form once, in order: {@code _summary=count}, {@code _count=0}
	 * (spec-equivalent), then {@code _total=accurate} on a minimal first page. A first crawl counts
	 * the whole type with no {@code _lastUpdated} bounds; an incremental run counts only the
	 * [since, hi) delta. Counts over millions of rows routinely outlive gateway timeouts while the
	 * abandoned query keeps running server-side, so re-issuing the same form stacks load on the
	 * origin; moving to the next form is the retry. Returns -1 when no form yields a total.
	 */
	private long countType(
			IGenericClient client,
			String serverUrl,
			String type,
			String since,
			String hi,
			RateGate gate,
			Consumer<StepEvent> steps,
			AtomicInteger planningRequests) {
		String range = since == null
				? ""
				: "&_lastUpdated=ge" + URLEncoder.encode(since, StandardCharsets.UTF_8) + "&_lastUpdated=lt"
						+ URLEncoder.encode(hi, StandardCharsets.UTF_8);
		String[] forms = {
			type + "?_summary=count" + range, type + "?_count=0" + range, type + "?_count=1&_total=accurate" + range,
		};
		String scope = since == null ? "Counting all " + type : "Counting " + type + " changed since the anchor";
		for (String url : forms) {
			planningRequests.incrementAndGet();
			steps.accept(StepEvent.progress("SEARCH", scope, "GET", serverUrl + "/" + url));
			long startNanos = System.nanoTime();
			try {
				Bundle bundle = runGatedUnchecked(gate, () -> searchByUrl(client, url));
				if (bundle.hasTotal()) {
					return bundle.getTotal();
				}
				steps.accept(StepEvent.info("SEARCH", "Count query for " + type + " returned no total"));
			} catch (BaseServerResponseException e) {
				steps.accept(
						serverError("SEARCH", "Count query failed for " + type, serverUrl + "/" + url, startNanos, e));
			}
		}
		return -1;
	}

	/** Runs a single gated attempt with no retries, for optional probes where a retry storm hurts. */
	private static <T> T runGatedUnchecked(RateGate gate, Supplier<T> call) {
		try {
			return runGated(gate, call::get);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Crawl interrupted while waiting for the rate gate", e);
		}
	}

	/**
	 * Run per-type (or per-file) tasks with bounded concurrency (api.crawl-concurrency). At
	 * concurrency 1 the tasks run inline, in order, on the calling thread: the exact serial
	 * behavior of a single worker. At higher concurrency they run on a bounded pool and are
	 * collected in completion order so a failing task is not stuck waiting behind a slow one.
	 */
	private SearchResult runTypeTasks(List<Callable<TypeCrawl>> tasks) {
		int n = Math.max(1, Math.min(crawlConcurrency, tasks.size()));
		if (n == 1) {
			TypeCrawl total = new TypeCrawl(0, 0, 0, 0);
			for (Callable<TypeCrawl> task : tasks) {
				total = merge(total, callUnchecked(task));
			}
			return toSearchResult(total);
		}

		Map<String, String> mdcContext = MDC.getCopyOfContextMap();
		ExecutorService pool = Executors.newFixedThreadPool(n, crawlTaskThreadFactory());
		try {
			ExecutorCompletionService<TypeCrawl> completion = new ExecutorCompletionService<>(pool);
			List<Future<TypeCrawl>> futures = new ArrayList<>(tasks.size());
			for (Callable<TypeCrawl> task : tasks) {
				futures.add(completion.submit(withMdc(task, mdcContext)));
			}
			TypeCrawl total = new TypeCrawl(0, 0, 0, 0);
			for (int i = 0; i < tasks.size(); i++) {
				try {
					total = merge(total, completion.take().get());
				} catch (ExecutionException e) {
					futures.forEach(f -> f.cancel(true));
					throw unwrap(e);
				} catch (CancellationException e) {
					futures.forEach(f -> f.cancel(true));
					throw e;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					futures.forEach(f -> f.cancel(true));
					throw new IllegalStateException("Crawl interrupted", e);
				}
			}
			return toSearchResult(total);
		} finally {
			pool.shutdownNow();
		}
	}

	private static SearchResult toSearchResult(TypeCrawl total) {
		return new SearchResult(total.records(), total.bytes(), total.requests(), total.pages());
	}

	private static TypeCrawl merge(TypeCrawl a, TypeCrawl b) {
		return new TypeCrawl(
				a.records() + b.records(), a.bytes() + b.bytes(), a.requests() + b.requests(), a.pages() + b.pages());
	}

	private static TypeCrawl callUnchecked(Callable<TypeCrawl> task) {
		try {
			return task.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Crawl task failed", e);
		}
	}

	/** Unwraps an ExecutionException from a task future, preserving cancellation and unchecked causes. */
	private static RuntimeException unwrap(ExecutionException e) {
		Throwable cause = e.getCause();
		if (cause instanceof CancellationException cancellation) {
			return cancellation;
		}
		if (cause instanceof RuntimeException runtime) {
			return runtime;
		}
		return new IllegalStateException("Crawl task failed", cause);
	}

	/** Propagates the submitting thread's MDC (batchId/jobId/serverKey) so step logging stays tagged. */
	private static <T> Callable<T> withMdc(Callable<T> task, Map<String, String> mdcContext) {
		return () -> {
			Map<String, String> previous = MDC.getCopyOfContextMap();
			if (mdcContext != null) {
				MDC.setContextMap(mdcContext);
			} else {
				MDC.clear();
			}
			try {
				return task.call();
			} finally {
				if (previous != null) {
					MDC.setContextMap(previous);
				} else {
					MDC.clear();
				}
			}
		};
	}

	private static ThreadFactory crawlTaskThreadFactory() {
		AtomicInteger counter = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, "crawl-task-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	private static final AtomicInteger PREFETCH_THREAD_COUNTER = new AtomicInteger();

	/**
	 * A dedicated single-thread executor for one chain's single-slot page prefetch: at most one
	 * page fetch is ever queued on it, so one thread is all a chain needs.
	 */
	private static ExecutorService newPrefetchExecutor() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "crawl-prefetch-" + PREFETCH_THREAD_COUNTER.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	/** Blocks for a prefetched page, converting failure/interruption the same way the retry helpers do. */
	private static <T> T awaitPrefetch(Future<T> future) {
		try {
			return future.get();
		} catch (ExecutionException e) {
			throw unwrap(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Crawl interrupted", e);
		}
	}

	/**
	 * A keyset page query. The {@code _lastUpdated} value is URL-encoded; a raw {@code +} offset is
	 * otherwise decoded by the server as a space and rejected as an invalid date.
	 */
	static String lastUpdatedQuery(String type, int pageSize, String watermark) {
		return lastUpdatedQuery(type, pageSize, watermark, null);
	}

	/**
	 * A keyset page query bounded above by an exclusive {@code upperBound}, for a partitioned window.
	 * A null {@code upperBound} is today's open-ended query.
	 */
	static String lastUpdatedQuery(String type, int pageSize, String watermark, String upperBound) {
		String query = type + "?_count=" + pageSize + "&_sort=_lastUpdated&_total=none";
		if (watermark != null) {
			query += "&_lastUpdated=ge" + URLEncoder.encode(watermark, StandardCharsets.UTF_8);
		}
		if (upperBound != null) {
			query += "&_lastUpdated=lt" + URLEncoder.encode(upperBound, StandardCharsets.UTF_8);
		}
		return query;
	}

	/** A page's newest {@code meta.lastUpdated}: the verbatim wire value plus its parsed instant. */
	record LastUpdated(String value, Date instant) {}

	/** The newest {@code meta.lastUpdated} on a page, by parsed instant; null if none. */
	static LastUpdated maxLastUpdated(Bundle bundle, String type) {
		return maxLastUpdated(matchedResources(bundle, type));
	}

	private static LastUpdated maxLastUpdated(List<Resource> resources) {
		LastUpdated max = null;
		for (Resource resource : resources) {
			if (resource.getMeta() == null || !resource.getMeta().hasLastUpdated()) {
				continue;
			}
			Date date = resource.getMeta().getLastUpdated();
			if (max == null || date.after(max.instant())) {
				max = new LastUpdated(resource.getMeta().getLastUpdatedElement().getValueAsString(), date);
			}
		}
		return max;
	}

	/**
	 * Match entries of the requested type. A searchset may carry other entries, e.g. an
	 * OperationOutcome warning with {@code search.mode=outcome} stamped at render time; those must
	 * never feed the watermark or count as page rows.
	 */
	static List<Resource> matchedResources(Bundle bundle, String type) {
		List<Resource> matches = new ArrayList<>();
		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();
			if (resource == null || !type.equals(resource.fhirType())) {
				continue;
			}
			if (entry.hasSearch() && entry.getSearch().getMode() == Bundle.SearchEntryMode.OUTCOME) {
				continue;
			}
			matches.add(resource);
		}
		return matches;
	}

	/** A time-bounded partition of a type's crawl range; a null hi is open-ended (the untrusted-anchor final window). */
	record Window(String lo, String hi) {}

	/** Target resources per window when the range's total is known. */
	static final long MAX_WINDOW_SIZE = 50_000;

	/** Window-count cap so a pathological total cannot explode the task list. */
	static final int MAX_WINDOWS_PER_TYPE = 4_096;

	/**
	 * Slices [lo, hi) into up to {@code n} equal-duration windows. Cuts that collapse onto a
	 * neighbor at millisecond resolution are skipped. When {@code openTail}, the last window's hi
	 * is cleared so its query stays open-ended.
	 */
	static List<Window> uniformWindows(String lo, String hi, int n, boolean openTail) {
		long loMs = parseInstant(lo).toEpochMilli();
		long hiMs = parseInstant(hi).toEpochMilli();
		List<Window> windows = new ArrayList<>();
		String cursor = lo;
		long cursorMs = loMs;
		for (int i = 1; i < n; i++) {
			long cutMs = loMs + (hiMs - loMs) * i / n;
			if (cutMs <= cursorMs || cutMs >= hiMs) {
				continue;
			}
			String cut = Instant.ofEpochMilli(cutMs).toString();
			windows.add(new Window(cursor, cut));
			cursor = cut;
			cursorMs = cutMs;
		}
		windows.add(new Window(cursor, openTail ? null : hi));
		return windows;
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
			if (!PlanNetTypes.TYPES.contains(type)) {
				continue;
			}
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
			ExecutorService prefetch = newPrefetchExecutor();
			Map<String, String> mdcContext = MDC.getCopyOfContextMap();
			try {
				while (bundle != null) {
					requests++;
					pages++;

					Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
					Future<Bundle> nextFuture = null;
					if (next != null && next.getUrl() != null && seen.add(next.getUrl())) {
						String nextUrl = next.getUrl();
						steps.accept(StepEvent.progress(
								"HISTORY", "Fetching deletion scan page " + (pages + 1), "GET", nextUrl));
						Bundle current = bundle;
						nextFuture = prefetch.submit(withMdc(
								() -> {
									pauseBetweenPages();
									return withRetryUnchecked(
											"HISTORY", "deletion scan page fetch", steps, () -> client.loadPage()
													.next(current)
													.execute());
								},
								mdcContext));
					}

					extractDeletions(bundle, deletions);

					bundle = nextFuture == null ? null : awaitPrefetch(nextFuture);
				}
			} finally {
				prefetch.shutdownNow();
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
	 * Acquire a snapshot via async Bulk Data $export: kick off, poll Content-Location
	 * honoring Retry-After, then download and parse each NDJSON output file. A non-null
	 * {@code since} makes it an incremental export ({@code _since} on the kick-off); a
	 * kick-off that rejects it throws {@link SinceUnsupportedException} so the caller can
	 * retry as a full export, while a rejected bare kick-off throws
	 * {@link StrategyUnsupportedException}. A kick-off 5xx is retried and, if it survives
	 * every attempt, also throws {@link StrategyUnsupportedException} (regardless of
	 * {@code since}: a 5xx is not a _since signal), because some servers answer an unknown
	 * operation with 500 rather than 404; a persistent 429 fails with
	 * {@link IllegalStateException} instead so a rate-limited server is never demoted.
	 */
	public SearchResult bulkExport(
			String serverUrl,
			String serverKey,
			String since,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		HttpClient http = newBulkHttpClient();
		int requests = 0;
		long bytes = 0;
		try {
			String typeParam = URLEncoder.encode(String.join(",", PlanNetTypes.TYPES), StandardCharsets.UTF_8);
			String kickoffUrl = serverUrl + "/$export?_type=" + typeParam
					+ (since != null ? "&_since=" + URLEncoder.encode(since, StandardCharsets.UTF_8) : "");
			steps.accept(StepEvent.progress("EXPORT", "Kicking off system $export..."));
			long kickStart = System.nanoTime();
			HttpRequest kickoffRequest = HttpRequest.newBuilder(URI.create(kickoffUrl))
					.timeout(httpTimeout)
					.header("Accept", "application/fhir+json")
					.header("Prefer", "respond-async")
					.GET()
					.build();
			HttpResponse<String> kickoff;
			try {
				kickoff = withRetry("EXPORT", "$export kick-off", steps, () -> {
					HttpResponse<String> response = http.send(kickoffRequest, HttpResponse.BodyHandlers.ofString());
					if (response.statusCode() == 429 || response.statusCode() >= 500) {
						throw new TransientHttpStatusException(
								"$export kick-off returned HTTP " + response.statusCode(),
								response.statusCode(),
								response.body());
					}
					return response;
				});
			} catch (TransientHttpStatusException e) {
				steps.accept(StepEvent.failure(
						"EXPORT",
						"Bulk $export kick-off failed: HTTP " + e.status() + " after " + MAX_RETRY_ATTEMPTS
								+ " attempts",
						"GET",
						kickoffUrl,
						e.status(),
						(System.nanoTime() - kickStart) / 1_000_000,
						e.body()));
				// A persistent 429 stays fatal: falling back to search would add load to a server
				// that is asking for less. A 5xx that survives every retry is classified as
				// unsupported instead, because some servers answer an unknown operation with 500
				// rather than 404; an AUTO run then falls back to search, while an explicit
				// BULK_EXPORT run still fails.
				if (e.status() == 429) {
					throw new IllegalStateException(
							"Expected 202 from $export, got 429 after " + MAX_RETRY_ATTEMPTS + " attempts");
				}
				throw new StrategyUnsupportedException("Bulk $export kick-off failed: HTTP " + e.status() + " after "
						+ MAX_RETRY_ATTEMPTS + " attempts");
			}
			requests++;
			long kickMs = (System.nanoTime() - kickStart) / 1_000_000;
			if (kickoff.statusCode() != 202) {
				int status = kickoff.statusCode();
				steps.accept(StepEvent.failure(
						"EXPORT",
						"Bulk $export kick-off failed: HTTP " + status,
						"GET",
						kickoffUrl,
						status,
						kickMs,
						kickoff.body()));
				if (since != null) {
					throw new SinceUnsupportedException("$export kick-off with _since rejected: HTTP " + status);
				}
				throw new StrategyUnsupportedException("Server does not support $export: HTTP " + status);
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

			// Type counts (by URL-bearing entries only) decide which tracks need an index suffix, so
			// two files of the same type are distinguishable in the play-by-play.
			Map<String, Integer> typeCounts = new HashMap<>();
			for (JsonNode output : manifest.path("output")) {
				if (output.path("url").asText(null) == null) {
					continue;
				}
				String type = output.path("type").asText(null);
				if (type != null) {
					typeCounts.merge(type, 1, Integer::sum);
				}
			}

			RateGate gate = gateFor(serverUrl);
			Map<String, Integer> typeSeen = new HashMap<>();
			List<Callable<TypeCrawl>> tasks = new ArrayList<>();
			for (JsonNode output : manifest.path("output")) {
				String fileUrl = output.path("url").asText(null);
				if (fileUrl == null) {
					continue;
				}
				String fileType = output.path("type").asText(null);
				String track;
				if (fileType == null) {
					track = null;
				} else if (typeCounts.get(fileType) > 1) {
					track = fileType + "-" + typeSeen.merge(fileType, 1, Integer::sum);
				} else {
					track = fileType;
				}
				tasks.add(
						() -> downloadExportFile(http, fileUrl, fileType, track, serverKey, gate, steps, resourceSink));
			}
			// Unlike a per-type search task, a file task does not absorb its own failure: a file that
			// still fails after retries must fail the whole export, since finishFullSnapshot would
			// otherwise treat that file's resources as deleted.
			SearchResult filesResult = runTypeTasks(tasks);
			return new SearchResult(
					filesResult.records(), bytes + filesResult.bytes(), requests + filesResult.requests(), 0);
		} catch (IOException e) {
			throw new UncheckedIOException("Bulk export failed for " + serverUrl, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Bulk export interrupted", e);
		}
	}

	/** One output file's download, run as a task through {@link #runTypeTasks}; its own parser and emitter. */
	private TypeCrawl downloadExportFile(
			HttpClient http,
			String fileUrl,
			String fileType,
			String track,
			String serverKey,
			RateGate gate,
			Consumer<StepEvent> steps,
			Consumer<List<FetchedResource>> resourceSink) {
		Consumer<StepEvent> trackSteps =
				track == null ? steps : ev -> steps.accept(ev.track() == null ? ev.withTrack(track) : ev);
		IParser parser = fhirContext.newJsonParser();
		BatchEmitter emitter = new BatchEmitter(resourceSink);
		long fileStart = System.nanoTime();
		try {
			trackSteps.accept(StepEvent.progress(
					"EXPORT",
					"Downloading export file" + (fileType != null ? " (" + fileType + ")" : "") + "...",
					"GET",
					fileUrl));
			long fileBytes = withRetry(
					"EXPORT",
					"export file download",
					trackSteps,
					gate,
					() -> streamExportFile(http, fileUrl, fileType, serverKey, parser, emitter, trackSteps, fileStart));
			trackSteps.accept(StepEvent.request(
					"EXPORT",
					"Downloaded export file" + (fileType != null ? " (" + fileType + ")" : ""),
					"GET",
					fileUrl,
					200,
					(System.nanoTime() - fileStart) / 1_000_000,
					fileBytes,
					emitter.count()));
			return new TypeCrawl(emitter.count(), fileBytes, 1, 0);
		} catch (IOException e) {
			throw new UncheckedIOException("Bulk export file download failed for " + fileUrl, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Bulk export interrupted", e);
		} finally {
			// A failed download must not strand a partial buffered page either.
			emitter.flush();
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
				since != null ? "Paging system _history since the anchor..." : "Paging full system _history...",
				"GET",
				historyUrl));
		var pagedHistory = history;
		Bundle bundle;
		try {
			bundle = withRetryUnchecked("HISTORY", "history export", steps, pagedHistory::execute);
		} catch (BaseServerResponseException e) {
			steps.accept(serverError("HISTORY", "System _history export failed", historyUrl, startNanos, e));
			throw e;
		}
		ExecutorService prefetch = newPrefetchExecutor();
		Map<String, String> mdcContext = MDC.getCopyOfContextMap();
		try {
			while (bundle != null) {
				requests++;
				pages++;
				if (pages % PAGE_LOG_EVERY == 0) {
					ourLog.info("HISTORY: {} pages fetched, {} resources so far", pages, emitter.count());
				}

				Bundle.BundleLinkComponent next = bundle.getLink(Bundle.LINK_NEXT);
				Future<Bundle> nextFuture = null;
				if (next != null && next.getUrl() != null && seenUrls.add(next.getUrl())) {
					String nextUrl = next.getUrl();
					steps.accept(StepEvent.progress("HISTORY", "Fetching history page " + (pages + 1), "GET", nextUrl));
					Bundle page = bundle;
					nextFuture = prefetch.submit(withMdc(
							() -> {
								pauseBetweenPages();
								try {
									return withRetryUnchecked(
											"HISTORY", "history page fetch", steps, () -> client.loadPage()
													.next(page)
													.execute());
								} catch (BaseServerResponseException e) {
									steps.accept(serverError(
											"HISTORY", "System _history page fetch failed", nextUrl, startNanos, e));
									throw e;
								}
							},
							mdcContext));
				}

				for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
					if (isDeletion(entry)) {
						DeletionEntry deletion = parseReference(
								entry.getRequest() != null ? entry.getRequest().getUrl() : entry.getFullUrl());
						if (deletion != null
								&& PlanNetTypes.TYPES.contains(deletion.resourceType())
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
					if (!PlanNetTypes.TYPES.contains(type)) {
						continue;
					}
					String key = serverKey + "|" + type + "/"
							+ resource.getIdElement().getIdPart();
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
							key,
							type,
							resource.getIdElement().getIdPart(),
							versionId,
							lastUpdated,
							json,
							json.length()));
				}

				bundle = nextFuture == null ? null : awaitPrefetch(nextFuture);
			}
		} finally {
			prefetch.shutdownNow();
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

	/**
	 * Stream one NDJSON export file line-by-line into the emitter, so a multi-million-row file
	 * never sits in heap. Returns the approximate byte count read.
	 */
	private long streamExportFile(
			HttpClient http,
			String fileUrl,
			String fileType,
			String serverKey,
			IParser parser,
			BatchEmitter emitter,
			Consumer<StepEvent> steps,
			long startNanos)
			throws IOException, InterruptedException {
		HttpResponse<InputStream> response = http.send(
				HttpRequest.newBuilder(URI.create(fileUrl))
						.timeout(httpTimeout)
						.header("Accept", "application/fhir+ndjson")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofInputStream());
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			String errorBody;
			try (InputStream in = response.body()) {
				errorBody = new String(in.readNBytes(StepEvent.MAX_ERROR_BODY_CHARS), StandardCharsets.UTF_8);
			}
			steps.accept(StepEvent.failure(
					"EXPORT",
					"Export file download failed" + (fileType != null ? " (" + fileType + ")" : "") + ": HTTP "
							+ status,
					"GET",
					fileUrl,
					status,
					(System.nanoTime() - startNanos) / 1_000_000,
					errorBody));
			throw new IllegalStateException("Failed to download export file " + fileUrl + ": HTTP " + status);
		}
		long fileBytes = 0;
		try (BufferedReader reader =
				new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				fileBytes += line.length() + 1;
				if (line.isBlank()) {
					continue;
				}
				FetchedResource resource = toFetched(line.trim(), serverKey, parser);
				if (resource != null) {
					emitter.add(resource);
				}
			}
		}
		return fileBytes;
	}

	private FetchedResource toFetched(String json, String serverKey, IParser parser) {
		try {
			Resource resource = (Resource) parser.parseResource(json);
			if (resource.getIdElement() == null || resource.getIdElement().getIdPart() == null) {
				return null;
			}
			String type = resource.fhirType();
			if (!PlanNetTypes.TYPES.contains(type)) {
				return null;
			}
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

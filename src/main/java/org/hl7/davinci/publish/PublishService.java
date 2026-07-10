package org.hl7.davinci.publish;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.DateRangeParam;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.common.PathUtils;
import org.hl7.davinci.common.PlanNetTypes;
import org.hl7.davinci.publish.feed.ChangeEntry;
import org.hl7.davinci.publish.feed.ChangeFeedReader;
import org.hl7.davinci.publish.feed.StorageSettingsGuard;
import org.hl7.davinci.publish.feed.WriteFrontier;
import org.hl7.davinci.publish.store.SnapshotFileMerger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Periodically publishes a snapshot of this server's own Plan-Net data via the Bulk Data
 * $bulk-publish operation. Filesystem-only: no database entity, isolated from the crawler's
 * manifests (which publish data crawled from OTHER servers).
 */
@Service
public class PublishService {

	private static final Logger ourLog = LoggerFactory.getLogger(PublishService.class);

	public static final String MANIFEST_TYPE =
			"http://hl7.org/fhir/uv/bulkdata/StructureDefinition/BulkPublishManifest";

	private static final String CURRENT_FILE = "current";
	private static final String META_FILE = "meta.json";
	private static final long FRONTIER_WAIT_WARN_MS = 60_000;
	private static final long FRONTIER_WAIT_POLL_MS = 100;

	private final FhirContext fhirContext;
	private final DaoRegistry daoRegistry;
	private final ObjectMapper objectMapper;
	private final PublishProperties publishProps;
	private final WriteFrontier writeFrontier;
	private final ChangeFeedReader changeFeedReader;
	private final StorageSettingsGuard storageSettingsGuard;

	private final AtomicReference<String> lastLoggedGuardViolation = new AtomicReference<>();

	public PublishService(
			FhirContext fhirContext,
			DaoRegistry daoRegistry,
			ObjectMapper objectMapper,
			PublishProperties publishProps,
			WriteFrontier writeFrontier,
			ChangeFeedReader changeFeedReader,
			StorageSettingsGuard storageSettingsGuard) {
		this.fhirContext = fhirContext;
		this.daoRegistry = daoRegistry;
		this.objectMapper = objectMapper;
		this.publishProps = publishProps;
		this.writeFrontier = writeFrontier;
		this.changeFeedReader = changeFeedReader;
		this.storageSettingsGuard = storageSettingsGuard;
	}

	/** One published file's stats, captured at export time; {@code snapshotId} is the dir that physically holds it. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FileMeta(String type, long count, long fileSize, String snapshotId) {}

	/**
	 * The contents of a snapshot's {@code meta.json}. Unknown fields are ignored on read so a future
	 * field added to this record does not break deserialization of metas written by an older build.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SnapshotMeta(String transactionTime, List<FileMeta> files) {}

	/** The currently active snapshot: its id (also the ETag) and metadata. */
	public record CurrentSnapshot(String snapshotId, SnapshotMeta meta) {}

	/** One retained snapshot directory on disk: its metadata plus whether it is the active one. */
	public record SnapshotListing(String id, String transactionTime, boolean current, List<FileMeta> files) {}

	/** A merge outcome: whether the content changed, and the FileMeta to keep, if any. */
	private record TypeMergeOutcome(boolean changed, Optional<FileMeta> fileMeta) {}

	/**
	 * The hosted DB is ephemeral, so by default a snapshot left over from a prior boot would
	 * misrepresent the store; wipe the publish root before the first publish. When reset-on-startup
	 * is disabled, a surviving {@code current} pointer keeps serving and the startup publish is
	 * change-gated instead of unconditional.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		boolean resetOnStartup = publishProps.isResetOnStartup();
		if (resetOnStartup) {
			clearPublishRoot();
		}
		if (!publishProps.isEnabled()) {
			return;
		}
		if (resetOnStartup) {
			if (checkGuardOrSkip()) {
				bootstrap();
			}
		} else {
			publishIfChanged();
		}
	}

	@Scheduled(
			initialDelayString = "#{@publishProperties.intervalMs}",
			fixedDelayString = "#{@publishProperties.intervalMs}")
	public void scheduledPublish() {
		if (!publishProps.isEnabled()) {
			return;
		}
		publishIfChanged();
	}

	/** Bootstraps if no snapshot exists yet; otherwise runs a steady-state tick. */
	void publishIfChanged() {
		if (!checkGuardOrSkip()) {
			return;
		}
		Optional<SnapshotMeta> current = currentSnapshotId().flatMap(this::readMeta);
		if (current.isEmpty()) {
			bootstrap();
		} else {
			steadyTick(current.get());
		}
	}

	/** Returns true if the tick may proceed. Logs a guard violation at ERROR only when its message changes. */
	private boolean checkGuardOrSkip() {
		Optional<String> violation = storageSettingsGuard.firstViolation();
		if (violation.isEmpty()) {
			lastLoggedGuardViolation.set(null);
			return true;
		}
		String message = violation.get();
		if (!message.equals(lastLoggedGuardViolation.getAndSet(message))) {
			ourLog.error("Skipping bulk-publish tick: {}", message);
		}
		return false;
	}

	/**
	 * Publishes a new snapshot if the write frontier has advanced and any type changed within the
	 * window. Synchronized with {@link #bootstrap}.
	 */
	private synchronized void steadyTick(SnapshotMeta previousMeta) {
		long previousMillis = Instant.parse(previousMeta.transactionTime()).toEpochMilli();
		long frontierMillis = writeFrontier.frontierMillis();
		if (frontierMillis <= previousMillis) {
			return;
		}

		Map<String, Map<String, ChangeEntry>> winnersByType =
				readWindowForEveryType(previousMillis - publishProps.getOverlapMs(), frontierMillis);
		if (winnersByType.values().stream().allMatch(Map::isEmpty)) {
			return;
		}

		long startNanos = System.nanoTime();
		String snapshotId = UUID.randomUUID().toString();
		Path dir = publishRoot().resolve(snapshotId);
		boolean published = false;
		try {
			Files.createDirectories(dir);
			List<FileMeta> files = new ArrayList<>();
			boolean anyChanged = false;
			for (String type : PlanNetTypes.TYPES) {
				Map<String, ChangeEntry> winners = winnersByType.get(type);
				Optional<FileMeta> previousFile = reusedFileMeta(previousMeta, type);
				if (winners.isEmpty()) {
					previousFile.ifPresent(files::add);
					continue;
				}
				Path oldFile = previousFile.map(this::fileFor).orElse(null);
				TypeMergeOutcome outcome = mergeType(dir, snapshotId, type, oldFile, winners);
				anyChanged |= outcome.changed();
				if (outcome.fileMeta().isPresent()) {
					files.add(outcome.fileMeta().get());
				} else if (!outcome.changed()) {
					previousFile.ifPresent(files::add);
				}
			}
			if (!anyChanged) {
				return;
			}
			writeMeta(dir, new SnapshotMeta(Instant.ofEpochMilli(frontierMillis).toString(), files));
			swapCurrent(snapshotId);
			writeFrontier.noteLastPublished(frontierMillis);
			published = true;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to publish bulk-publish snapshot " + snapshotId, e);
		} finally {
			if (!published) {
				PathUtils.deleteRecursively(dir);
			}
		}
		prune(snapshotId);
		ourLog.info(
				"Bulk publish snapshot {} created via steady tick in {} ms",
				snapshotId,
				(System.nanoTime() - startNanos) / 1_000_000);
	}

	/**
	 * Publishes the first snapshot by scanning every type, then repairs it against any writes that
	 * landed during the scan. Synchronized with {@link #steadyTick}.
	 */
	private synchronized void bootstrap() {
		long startNanos = System.nanoTime();
		long f0Millis = writeFrontier.frontierMillis();
		Instant scanUpperBound = Instant.now();
		String snapshotId = UUID.randomUUID().toString();
		Path dir = publishRoot().resolve(snapshotId);
		SystemRequestDetails details = new SystemRequestDetails();
		boolean published = false;
		try {
			Files.createDirectories(dir);
			Map<String, FileMeta> scannedByType = new HashMap<>();
			for (String type : PlanNetTypes.TYPES) {
				exportType(dir, snapshotId, type, scanUpperBound, details).ifPresent(fm -> scannedByType.put(type, fm));
			}

			long scanEndMillis = System.currentTimeMillis();
			awaitFrontierPast(scanEndMillis);
			long f1Millis = writeFrontier.frontierMillis();

			Map<String, Map<String, ChangeEntry>> winnersByType = readWindowForEveryType(f0Millis, f1Millis);
			List<FileMeta> files = new ArrayList<>();
			for (String type : PlanNetTypes.TYPES) {
				Map<String, ChangeEntry> winners = winnersByType.get(type);
				FileMeta scanned = scannedByType.get(type);
				if (winners.isEmpty()) {
					if (scanned != null) {
						files.add(scanned);
					}
					continue;
				}
				Path scannedFile = scanned != null ? dir.resolve(type + ".ndjson.gz") : null;
				TypeMergeOutcome outcome = mergeType(dir, snapshotId, type, scannedFile, winners);
				if (outcome.fileMeta().isPresent()) {
					files.add(outcome.fileMeta().get());
				} else if (!outcome.changed() && scanned != null) {
					files.add(scanned);
				}
			}

			writeMeta(dir, new SnapshotMeta(Instant.ofEpochMilli(f1Millis).toString(), files));
			swapCurrent(snapshotId);
			writeFrontier.noteLastPublished(f1Millis);
			published = true;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to bootstrap bulk-publish snapshot " + snapshotId, e);
		} finally {
			if (!published) {
				PathUtils.deleteRecursively(dir);
			}
		}
		prune(snapshotId);
		ourLog.info(
				"Bulk publish snapshot {} created via bootstrap in {} ms",
				snapshotId,
				(System.nanoTime() - startNanos) / 1_000_000);
	}

	/** Blocks until the write frontier reaches {@code scanEndMillis}, warning once past 60 seconds. */
	private void awaitFrontierPast(long scanEndMillis) {
		long waitStart = System.currentTimeMillis();
		boolean warned = false;
		while (writeFrontier.frontierMillis() < scanEndMillis) {
			if (!warned && System.currentTimeMillis() - waitStart > FRONTIER_WAIT_WARN_MS) {
				ourLog.warn(
						"Bootstrap publish has waited over {} ms for the write frontier to reach the scan end; "
								+ "a long-running write may be pinning it",
						FRONTIER_WAIT_WARN_MS);
				warned = true;
			}
			try {
				Thread.sleep(FRONTIER_WAIT_POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for the write frontier during bootstrap", e);
			}
		}
	}

	private Map<String, Map<String, ChangeEntry>> readWindowForEveryType(
			long sinceExclusiveMillis, long untilInclusiveMillis) {
		Map<String, Map<String, ChangeEntry>> result = new HashMap<>();
		for (String type : PlanNetTypes.TYPES) {
			result.put(type, changeFeedReader.readWindow(type, sinceExclusiveMillis, untilInclusiveMillis));
		}
		return result;
	}

	/** Merges {@code winners} onto {@code oldFileGz} (nullable) into {@code dir}/{@code type}.ndjson.gz via a temp file. */
	private TypeMergeOutcome mergeType(
			Path dir, String snapshotId, String type, Path oldFileGz, Map<String, ChangeEntry> winners) {
		Path finalFile = dir.resolve(type + ".ndjson.gz");
		Path tempFile = dir.resolve(type + ".ndjson.gz.merge-tmp");
		SnapshotFileMerger.MergeResult result =
				SnapshotFileMerger.merge(oldFileGz, winners, this::loadPinnedBody, tempFile);
		if (!result.changed()) {
			deleteQuietly(tempFile);
			return new TypeMergeOutcome(false, Optional.empty());
		}
		if (result.count() == 0) {
			deleteQuietly(tempFile);
			return new TypeMergeOutcome(true, Optional.empty());
		}
		try {
			Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to finalize merged snapshot file " + finalFile, e);
		}
		return new TypeMergeOutcome(
				true, Optional.of(new FileMeta(type, result.count(), result.uncompressedBytes(), snapshotId)));
	}

	/**
	 * Reads one winner's exact version. Throws if the read fails; never falls back to the current version.
	 * Passes deletedOk=true: the pinned version can be non-deleted even if the resource is now deleted.
	 */
	@SuppressWarnings("rawtypes")
	private String loadPinnedBody(ChangeEntry entry) {
		IFhirResourceDao dao = daoRegistry.getResourceDao(entry.type());
		IdType id = new IdType(entry.type(), entry.id(), Long.toString(entry.versionId()));
		IBaseResource resource = dao.read(id, new SystemRequestDetails(), true);
		return fhirContext.newJsonParser().setPrettyPrint(false).encodeResourceToString(resource);
	}

	private Path fileFor(FileMeta fileMeta) {
		return publishRoot().resolve(fileMeta.snapshotId()).resolve(fileMeta.type() + ".ndjson.gz");
	}

	/** The prior snapshot's {@link FileMeta} for {@code type}, if it had one; absent stays absent. */
	private static Optional<FileMeta> reusedFileMeta(SnapshotMeta previousMeta, String type) {
		if (previousMeta == null) {
			return Optional.empty();
		}
		return previousMeta.files().stream()
				.filter(file -> file.type().equals(type))
				.findFirst();
	}

	/**
	 * A resource updated again mid-export past transactionTime drops out of the remaining pages;
	 * the next publish catches it.
	 */
	@SuppressWarnings({"rawtypes"})
	private Optional<FileMeta> exportType(
			Path dir, String snapshotId, String type, Instant transactionTime, SystemRequestDetails details) {
		IFhirResourceDao dao = daoRegistry.getResourceDao(type);
		IParser parser = fhirContext.newJsonParser().setPrettyPrint(false);
		Path gzFile = dir.resolve(type + ".ndjson.gz");
		int pageSize = publishProps.getExportPageSize();
		long count = 0;
		long byteSize = 0;
		try {
			try (Writer writer = NdjsonFiles.gzipWriter(gzFile)) {
				Instant watermark = null;
				while (true) {
					SearchParameterMap page = pageQuery(watermark, transactionTime, pageSize);
					List<IBaseResource> resources = dao.search(page, details).getResources(0, pageSize);
					if (resources.isEmpty()) {
						break;
					}
					PageBoundary boundary = pageBoundary(lastUpdatedInstants(resources), pageSize);
					for (IBaseResource resource : resources.subList(0, boundary.writeThroughIndex())) {
						byteSize += writeResource(writer, parser, resource);
						count++;
					}
					WriteCount drained = drainInstant(dao, boundary.maxInstant(), details, parser, writer, pageSize);
					count += drained.count();
					byteSize += drained.byteSize();
					watermark = boundary.maxInstant();
					if (boundary.isFinalPage()) {
						break;
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to export " + type + " for publish", e);
		}
		if (count == 0) {
			deleteQuietly(gzFile);
			return Optional.empty();
		}
		return Optional.of(new FileMeta(type, count, byteSize, snapshotId));
	}

	private static SearchParameterMap pageQuery(Instant watermark, Instant transactionTime, int pageSize) {
		DateRangeParam range = new DateRangeParam().setUpperBoundInclusive(Date.from(transactionTime));
		if (watermark != null) {
			range.setLowerBoundExclusive(Date.from(watermark));
		}
		SearchParameterMap map = SearchParameterMap.newSynchronous()
				.setSort(new SortSpec(Constants.PARAM_LASTUPDATED).setOrder(SortOrderEnum.ASC))
				.setCount(pageSize)
				.setLoadSynchronousUpTo(pageSize);
		map.setLastUpdated(range);
		return map;
	}

	private static List<Instant> lastUpdatedInstants(List<IBaseResource> resources) {
		return resources.stream()
				.map(resource -> resource.getMeta().getLastUpdated().toInstant())
				.toList();
	}

	/**
	 * One page's write/drain split: rows before {@code writeThroughIndex} are strictly below
	 * {@code maxInstant} and safe to write directly (ascending sort guarantees no later page
	 * revisits them); rows from there on share {@code maxInstant} and are drained separately so an
	 * instant cluster larger than the page is never partially written. {@code isFinalPage} is true
	 * once the page came back shorter than requested, meaning nothing follows past this instant.
	 */
	public record PageBoundary(int writeThroughIndex, Instant maxInstant, boolean isFinalPage) {}

	/**
	 * Pure page-boundary decision over one page's ascending lastUpdated instants, kept separate
	 * from the DAO calls so it is directly testable over fake pages.
	 */
	public static PageBoundary pageBoundary(List<Instant> ascendingLastUpdated, int pageSize) {
		Instant max = ascendingLastUpdated.get(ascendingLastUpdated.size() - 1);
		int writeThroughIndex = 0;
		while (writeThroughIndex < ascendingLastUpdated.size()
				&& ascendingLastUpdated.get(writeThroughIndex).isBefore(max)) {
			writeThroughIndex++;
		}
		return new PageBoundary(writeThroughIndex, max, ascendingLastUpdated.size() < pageSize);
	}

	private record WriteCount(long count, long byteSize) {}

	/**
	 * Drains every row at one lastUpdated instant via a pinned, non-synchronous search paged by
	 * offset: a synchronous search ignores the offset, and an instant's cluster can exceed one page.
	 */
	@SuppressWarnings({"rawtypes"})
	private WriteCount drainInstant(
			IFhirResourceDao dao,
			Instant instant,
			SystemRequestDetails details,
			IParser parser,
			Writer writer,
			int pageSize)
			throws IOException {
		SearchParameterMap map =
				new SearchParameterMap().setSort(new SortSpec(Constants.PARAM_LASTUPDATED).setOrder(SortOrderEnum.ASC));
		map.setLastUpdated(new DateRangeParam()
				.setLowerBoundInclusive(Date.from(instant))
				.setUpperBoundInclusive(Date.from(instant)));
		IBundleProvider provider = dao.search(map, details);
		long count = 0;
		long byteSize = 0;
		int offset = 0;
		while (true) {
			List<IBaseResource> batch = provider.getResources(offset, offset + pageSize);
			if (batch.isEmpty()) {
				break;
			}
			for (IBaseResource resource : batch) {
				byteSize += writeResource(writer, parser, resource);
				count++;
			}
			offset += batch.size();
			if (batch.size() < pageSize) {
				break;
			}
		}
		return new WriteCount(count, byteSize);
	}

	private static long writeResource(Writer writer, IParser parser, IBaseResource resource) throws IOException {
		String json = parser.encodeResourceToString(resource);
		writer.write(json);
		writer.write("\n");
		return json.getBytes(StandardCharsets.UTF_8).length + 1;
	}

	private void writeMeta(Path dir, SnapshotMeta meta) throws IOException {
		objectMapper.writeValue(dir.resolve(META_FILE).toFile(), meta);
	}

	private void swapCurrent(String snapshotId) throws IOException {
		Path root = publishRoot();
		Path tmp = Files.createTempFile(root, "current", ".tmp");
		Files.writeString(tmp, snapshotId, StandardCharsets.UTF_8);
		Files.move(
				tmp, root.resolve(CURRENT_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private void prune(String currentId) {
		int retention = publishProps.getRetention();
		Path root = publishRoot();
		Map<String, SnapshotMeta> idToMeta = readAllMetas();
		Map<String, Instant> idToTransactionTime = new HashMap<>();
		idToMeta.forEach((id, meta) -> idToTransactionTime.put(id, Instant.parse(meta.transactionTime())));

		Set<String> byCount = idsToDelete(
				idToTransactionTime, currentId, retention, System.currentTimeMillis(), publishProps.getGracePeriodMs());
		Map<String, SnapshotMeta> retainedMetas = new HashMap<>(idToMeta);
		byCount.forEach(retainedMetas::remove);

		for (String id : subtractReferencedIds(byCount, retainedMetas)) {
			PathUtils.deleteRecursively(root.resolve(id));
		}
	}

	/**
	 * Pure retention decision: keep the newest {@code retention} snapshots by transaction time, and
	 * always keep {@code currentId} regardless of its position. {@code retention <= 0} means unlimited.
	 * A snapshot younger than {@code nowMillis - gracePeriodMs} is never nominated for deletion even
	 * if it falls outside the count window, giving in-flight file URLs time to age out of use.
	 */
	public static Set<String> idsToDelete(
			Map<String, Instant> idToTransactionTime,
			String currentId,
			int retention,
			long nowMillis,
			long gracePeriodMs) {
		if (retention <= 0) {
			return Set.of();
		}
		Set<String> keep = idToTransactionTime.entrySet().stream()
				.sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
				.map(Map.Entry::getKey)
				.limit(retention)
				.collect(Collectors.toCollection(HashSet::new));
		if (currentId != null) {
			keep.add(currentId);
		}
		long floor = nowMillis - gracePeriodMs;
		Set<String> toDelete = new HashSet<>();
		idToTransactionTime.forEach((id, transactionTime) -> {
			if (!keep.contains(id) && transactionTime.toEpochMilli() < floor) {
				toDelete.add(id);
			}
		});
		return toDelete;
	}

	/**
	 * Pure composition over {@link #idsToDelete}: a snapshot dir otherwise due for deletion survives
	 * if any RETAINED snapshot's meta (one not itself in {@code candidates}) still references it via
	 * a {@link FileMeta#snapshotId()}.
	 */
	public static Set<String> subtractReferencedIds(Set<String> candidates, Map<String, SnapshotMeta> retainedMetas) {
		Set<String> referenced = retainedMetas.values().stream()
				.flatMap(meta -> meta.files().stream())
				.map(FileMeta::snapshotId)
				.collect(Collectors.toSet());
		Set<String> result = new HashSet<>(candidates);
		result.removeAll(referenced);
		return result;
	}

	/** The active snapshot's id and metadata, or empty when no snapshot has been published yet. */
	public Optional<CurrentSnapshot> currentSnapshot() {
		return currentSnapshotId().flatMap(id -> readMeta(id).map(meta -> new CurrentSnapshot(id, meta)));
	}

	/** Retained snapshots on disk, newest first; directories with unreadable meta are skipped. */
	public List<SnapshotListing> listSnapshots() {
		String currentId = currentSnapshotId().orElse(null);
		return readAllMetas().entrySet().stream()
				.sorted(Map.Entry.<String, SnapshotMeta>comparingByValue(
								Comparator.comparing(meta -> Instant.parse(meta.transactionTime())))
						.reversed())
				.map(e -> new SnapshotListing(
						e.getKey(),
						e.getValue().transactionTime(),
						e.getKey().equals(currentId),
						e.getValue().files()))
				.toList();
	}

	/** Metadata for every snapshot directory under the publish root; unreadable metas are skipped. */
	private Map<String, SnapshotMeta> readAllMetas() {
		Map<String, SnapshotMeta> idToMeta = new HashMap<>();
		Path root = publishRoot();
		if (!Files.isDirectory(root)) {
			return idToMeta;
		}
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.filter(Files::isDirectory).toList()) {
				String id = child.getFileName().toString();
				try {
					readMeta(id).ifPresent(meta -> idToMeta.put(id, meta));
				} catch (UncheckedIOException ignored) {
					// a corrupt meta.json must not block listing or pruning the others
				}
			}
		} catch (IOException ignored) {
			// best-effort; a listing failure yields an empty map
		}
		return idToMeta;
	}

	/**
	 * Render the manifest body for a snapshot; a pure function of its metadata, so it is directly
	 * testable. Each file's URL is built from its OWNING snapshotId, not the manifest's own, so a
	 * type reused unchanged from an earlier snapshot keeps a byte-identical URL.
	 */
	public BulkPublishManifestJson render(SnapshotMeta meta, String baseUrl) {
		String updateCadence = Duration.ofMillis(publishProps.getIntervalMs()).toString();
		List<BulkPublishManifestJson.OutputEntry> output = meta.files().stream()
				.map(file -> new BulkPublishManifestJson.OutputEntry(
						file.type(),
						baseUrl + "/api/publish/" + file.snapshotId() + "/" + file.type() + ".ndjson",
						file.count(),
						file.fileSize()))
				.toList();
		return new BulkPublishManifestJson(MANIFEST_TYPE, meta.transactionTime(), updateCadence, false, output);
	}

	private Optional<String> currentSnapshotId() {
		Path pointer = publishRoot().resolve(CURRENT_FILE);
		if (!Files.exists(pointer)) {
			return Optional.empty();
		}
		try {
			String id = Files.readString(pointer, StandardCharsets.UTF_8).trim();
			return id.isBlank() ? Optional.empty() : Optional.of(id);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read publish pointer", e);
		}
	}

	private Optional<SnapshotMeta> readMeta(String snapshotId) {
		Path metaFile = publishRoot().resolve(snapshotId).resolve(META_FILE);
		if (!Files.exists(metaFile)) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(metaFile.toFile(), SnapshotMeta.class));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read publish snapshot meta " + snapshotId, e);
		}
	}

	private Path publishRoot() {
		return Path.of(publishProps.getStoragePath());
	}

	private void clearPublishRoot() {
		PathUtils.deleteRecursively(publishRoot());
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}
}

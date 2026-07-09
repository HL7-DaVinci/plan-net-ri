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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.common.PathUtils;
import org.hl7.davinci.common.PlanNetTypes;
import org.hl7.fhir.instance.model.api.IBaseResource;
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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

	private final FhirContext fhirContext;
	private final DaoRegistry daoRegistry;
	private final ObjectMapper objectMapper;
	private final PublishProperties publishProps;

	public PublishService(
			FhirContext fhirContext,
			DaoRegistry daoRegistry,
			ObjectMapper objectMapper,
			PublishProperties publishProps) {
		this.fhirContext = fhirContext;
		this.daoRegistry = daoRegistry;
		this.objectMapper = objectMapper;
		this.publishProps = publishProps;
	}

	/** One published file's stats, captured at export time; {@code snapshotId} is the dir that physically holds it. */
	public record FileMeta(String type, long count, long fileSize, String snapshotId) {}

	/** The contents of a snapshot's {@code meta.json}. */
	public record SnapshotMeta(String transactionTime, List<FileMeta> files) {}

	/** The currently active snapshot: its id (also the ETag) and metadata. */
	public record CurrentSnapshot(String snapshotId, SnapshotMeta meta) {}

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
			publish(Set.copyOf(PlanNetTypes.TYPES), null);
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

	/** Publish only when some type changed since the current snapshot; always publish on the first run. */
	void publishIfChanged() {
		Optional<SnapshotMeta> current = currentSnapshotId().flatMap(this::readMeta);
		boolean firstRun = current.isEmpty();
		Map<String, Boolean> hasHistoryByType =
				firstRun ? Map.of() : hasHistoryPerType(current.get().transactionTime());
		Set<String> changed = changedTypes(firstRun, hasHistoryByType);
		if (changed.isEmpty()) {
			return;
		}
		publish(changed, current.orElse(null));
	}

	/** Pure change-detection decision, kept separate from the DAO calls so it is directly testable. */
	public static Set<String> changedTypes(boolean firstRun, Map<String, Boolean> hasHistoryByType) {
		if (firstRun) {
			return Set.copyOf(PlanNetTypes.TYPES);
		}
		Set<String> changed = new HashSet<>();
		for (String type : PlanNetTypes.TYPES) {
			// A history provider that cannot report a definite answer counts the type as changed.
			if (hasHistoryByType.getOrDefault(type, true)) {
				changed.add(type);
			}
		}
		return changed;
	}

	/**
	 * A type's history {@code size()} is null under HAPI's default cached history-count mode whenever
	 * a since/until bound is applied (the count cache only covers the unbounded case), so a boolean
	 * existence check is used instead of a count; it runs the same bounded query and is unaffected by
	 * that caching.
	 */
	@SuppressWarnings({"rawtypes"})
	private Map<String, Boolean> hasHistoryPerType(String transactionTimeIso) {
		// History's since bound is inclusive; add 1ms to exclude entries at the prior transactionTime.
		Date since = Date.from(Instant.parse(transactionTimeIso).plusMillis(1));
		SystemRequestDetails details = new SystemRequestDetails();
		Map<String, Boolean> hasHistoryByType = new HashMap<>();
		for (String type : PlanNetTypes.TYPES) {
			IFhirResourceDao dao = daoRegistry.getResourceDao(type);
			IBundleProvider history = dao.history(since, null, null, details);
			hasHistoryByType.put(type, !history.isEmpty());
		}
		return hasHistoryByType;
	}

	/**
	 * Synchronized: the startup publish and a scheduled tick must never overlap, or the
	 * current-pointer swap and retention prune can corrupt each other. Types in {@code typesToExport}
	 * are freshly exported into the new snapshot; every other type carries its {@link FileMeta}
	 * forward from {@code previousMeta} unchanged, including its owning snapshotId, so its file URL
	 * stays byte-identical.
	 */
	private synchronized void publish(Set<String> typesToExport, SnapshotMeta previousMeta) {
		long startNanos = System.nanoTime();
		Instant transactionTime = Instant.now();
		String snapshotId = UUID.randomUUID().toString();
		Path dir = publishRoot().resolve(snapshotId);
		SystemRequestDetails details = new SystemRequestDetails();
		List<FileMeta> files = new ArrayList<>();
		try {
			Files.createDirectories(dir);
			for (String type : PlanNetTypes.TYPES) {
				if (typesToExport.contains(type)) {
					exportType(dir, snapshotId, type, transactionTime, details).ifPresent(files::add);
				} else {
					reusedFileMeta(previousMeta, type).ifPresent(files::add);
				}
			}
			writeMeta(dir, new SnapshotMeta(transactionTime.toString(), files));
			swapCurrent(snapshotId);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to publish bulk-publish snapshot " + snapshotId, e);
		}
		prune(snapshotId);
		long totalResources = files.stream().mapToLong(FileMeta::count).sum();
		ourLog.info(
				"Bulk publish snapshot {} created: {} resources across {} types in {} ms",
				snapshotId,
				totalResources,
				files.size(),
				(System.nanoTime() - startNanos) / 1_000_000);
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
		Map<String, SnapshotMeta> idToMeta = new HashMap<>();
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.filter(Files::isDirectory).toList()) {
				String id = child.getFileName().toString();
				readMeta(id).ifPresent(meta -> idToMeta.put(id, meta));
			}
		} catch (IOException ignored) {
			// best-effort; a listing failure just skips this prune pass
		}
		Map<String, Instant> idToTransactionTime = new HashMap<>();
		idToMeta.forEach((id, meta) -> idToTransactionTime.put(id, Instant.parse(meta.transactionTime())));

		Set<String> byCount = idsToDelete(idToTransactionTime, currentId, retention);
		Map<String, SnapshotMeta> retainedMetas = new HashMap<>(idToMeta);
		byCount.forEach(retainedMetas::remove);

		for (String id : subtractReferencedIds(byCount, retainedMetas)) {
			PathUtils.deleteRecursively(root.resolve(id));
		}
	}

	/**
	 * Pure retention decision: keep the newest {@code retention} snapshots by transaction time, and
	 * always keep {@code currentId} regardless of its position. {@code retention <= 0} means unlimited.
	 */
	public static Set<String> idsToDelete(Map<String, Instant> idToTransactionTime, String currentId, int retention) {
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
		Set<String> toDelete = new HashSet<>(idToTransactionTime.keySet());
		toDelete.removeAll(keep);
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

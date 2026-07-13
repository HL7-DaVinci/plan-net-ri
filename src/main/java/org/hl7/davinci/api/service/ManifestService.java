package org.hl7.davinci.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.entity.RunStatus;
import org.hl7.davinci.api.model.ManifestJson;
import org.hl7.davinci.api.model.ManifestSummary;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.hl7.davinci.common.PathUtils;
import org.hl7.davinci.common.PlanNetTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Creates retained snapshots and renders the served manifest. */
@Service
public class ManifestService {

	private static final Logger ourLog = LoggerFactory.getLogger(ManifestService.class);

	private static final String NDJSON_SUFFIX = ".ndjson";
	private static final String GZ_SUFFIX = ".ndjson.gz";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Manifest ids whose snapshot files are being re-exported; single-flight per manifest. */
	private final Set<String> regenerating = ConcurrentHashMap.newKeySet();

	private final NdjsonExportService ndjson;
	private final ManifestRepository manifestRepo;
	private final CrawlRunRepository runRepo;
	private final ApiProperties props;

	public ManifestService(
			NdjsonExportService ndjson,
			ManifestRepository manifestRepo,
			CrawlRunRepository runRepo,
			ApiProperties props) {
		this.ndjson = ndjson;
		this.manifestRepo = manifestRepo;
		this.runRepo = runRepo;
		this.props = props;
	}

	/** Write the NDJSON snapshot and persist a manifest row for it. */
	public ManifestRecord createManifest(
			CrawlJob job, String batchId, String windowSince, List<String> serverKeys, long operationStartNanos) {
		return createManifest(job, batchId, windowSince, serverKeys, operationStartNanos, 0);
	}

	/**
	 * As above; {@code carryoverMs} is crawl time absorbed from paused or crash-interrupted
	 * segments of this crawl, so the recorded build time covers the whole effort, matching the
	 * completed run's duration.
	 */
	public ManifestRecord createManifest(
			CrawlJob job,
			String batchId,
			String windowSince,
			List<String> serverKeys,
			long operationStartNanos,
			long carryoverMs) {
		String manifestId = UUID.randomUUID().toString();
		NdjsonExportService.SnapshotResult snapshot = ndjson.writeSnapshot(manifestId, serverKeys);

		ManifestRecord manifest = new ManifestRecord();
		manifest.setId(manifestId);
		manifest.setJobId(job.getId());
		manifest.setJobName(job.getName());
		manifest.setBatchId(batchId);
		manifest.setTransactionTime(Instant.now());
		manifest.setGeneratedAt(Instant.now());
		manifest.setRequiresAccessToken(false);
		manifest.setStrategy(job.getStrategy());
		manifest.setRequest(buildRequestUrl(job.getStrategy(), serverKeys, windowSince));
		manifest.setTotalResources(snapshot.totalResources());
		manifest.setStorageDir(snapshot.storageDir());
		manifest.setWindowSince(windowSince);
		manifest.setBuildDurationMs((System.nanoTime() - operationStartNanos) / 1_000_000 + carryoverMs);

		ManifestRecord saved = manifestRepo.save(manifest);
		pruneOldManifests(job.getId());
		return saved;
	}

	/**
	 * Re-export a manifest's snapshot files from the current aggregate on a background worker;
	 * returns false when a regeneration for it is already in flight. Meant for recovering a
	 * manifest whose files were lost (e.g. non-persistent storage across a restart).
	 */
	public boolean startRegeneration(ManifestRecord manifest, List<String> serverKeys) {
		if (!regenerating.add(manifest.getId())) {
			return false;
		}
		Thread worker = new Thread(
				() -> {
					try {
						regenerate(manifest, serverKeys);
					} catch (Exception e) {
						ourLog.error("Regeneration of manifest {} failed: {}", manifest.getId(), e.getMessage(), e);
					} finally {
						regenerating.remove(manifest.getId());
					}
				},
				"manifest-regen");
		worker.setDaemon(true);
		worker.start();
		return true;
	}

	public boolean isRegenerating(String manifestId) {
		return regenerating.contains(manifestId);
	}

	/**
	 * The re-export reflects the aggregate as it is NOW, which may have advanced past what the
	 * manifest originally snapshotted, so totalResources is updated to match and
	 * transactionTime becomes the time that data was actually acquired (the latest completed
	 * run across the servers), never the regeneration time. Files build in a temp directory
	 * and move into place at the end: the manifest row
	 * exists throughout, and a render must never see half-written gzip files (a truncated
	 * gzip fails the line-count fallback). A crash strands only the temp directory, which the
	 * startup orphan sweep reclaims like any other row-less directory.
	 */
	public ManifestRecord regenerate(ManifestRecord manifest, List<String> serverKeys) throws IOException {
		ourLog.info("Regenerating snapshot files for manifest {} from the aggregate", manifest.getId());
		Path tempDir = Path.of(props.getStoragePath(), manifest.getId() + ".regen");
		PathUtils.deleteRecursively(tempDir);
		NdjsonExportService.SnapshotResult snapshot = ndjson.writeSnapshot(manifest.getId() + ".regen", serverKeys);
		Path finalDir = Path.of(props.getStoragePath(), manifest.getId());
		PathUtils.deleteRecursively(finalDir);
		Files.move(Path.of(snapshot.storageDir()), finalDir);
		manifest.setStorageDir(finalDir.toString());
		manifest.setTotalResources(snapshot.totalResources());
		latestAcquisitionTime(serverKeys).ifPresent(manifest::setTransactionTime);
		ManifestRecord saved = manifestRepo.save(manifest);
		ourLog.info(
				"Regenerated manifest {}: {} resources in {}", manifest.getId(), snapshot.totalResources(), finalDir);
		return saved;
	}

	/**
	 * When the aggregate's data for these servers was last acquired: the most recent completed
	 * run start across them, from any job since crawl_resource is server-scoped. Run start is
	 * the aggregate's completeness frontier, the same anchor the incremental _since uses.
	 * Empty when no completed run survives, in which case the caller keeps the existing time.
	 */
	private Optional<Instant> latestAcquisitionTime(List<String> serverKeys) {
		return serverKeys.stream()
				.map(key -> runRepo.findTop1ByServerKeyAndStatusOrderByStartedAtDesc(key, RunStatus.COMPLETED))
				.flatMap(Optional::stream)
				.map(CrawlRun::getStartedAt)
				.filter(Objects::nonNull)
				.max(Comparator.naturalOrder());
	}

	/**
	 * Snapshot files are written before the manifest row is saved, so a crash in between strands
	 * a directory no row points at; those orphans are reclaimed here on startup.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void sweepOrphanedSnapshots() {
		Path root = Path.of(props.getStoragePath());
		if (!Files.isDirectory(root)) {
			return;
		}
		Set<String> knownIds = new HashSet<>();
		for (ManifestRecord manifest : manifestRepo.findAll()) {
			knownIds.add(manifest.getId());
		}
		try (Stream<Path> children = Files.list(root)) {
			children.filter(Files::isDirectory)
					.filter(dir -> !knownIds.contains(dir.getFileName().toString()))
					.forEach(dir -> {
						ourLog.warn("Deleting orphaned snapshot directory {}", dir);
						deleteDirectory(dir.toString());
					});
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}

	/** Delete every retained snapshot for a job: its NDJSON files and rows. Returns the count removed. */
	public int deleteManifestsForJob(String jobId) {
		List<ManifestRecord> manifests = manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc(jobId);
		List<String> storageDirs = new ArrayList<>();
		for (ManifestRecord manifest : manifests) {
			storageDirs.add(manifest.getStorageDir());
			manifestRepo.delete(manifest);
		}
		deleteDirectoriesAfterCommit(storageDirs);
		return manifests.size();
	}

	/**
	 * Remove snapshot directories only once the surrounding transaction commits, so a rollback
	 * partway through a cascading delete cannot strand on-disk files whose rows were restored.
	 * Outside a transaction (a direct call) the cleanup runs immediately.
	 */
	private void deleteDirectoriesAfterCommit(List<String> storageDirs) {
		if (storageDirs.isEmpty()) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					storageDirs.forEach(ManifestService.this::deleteDirectory);
				}
			});
		} else {
			storageDirs.forEach(this::deleteDirectory);
		}
	}

	/** Delete a manifest on demand: remove its NDJSON files and row. Returns false if not found. */
	public boolean deleteManifest(String id) {
		return manifestRepo
				.findById(id)
				.map(manifest -> {
					deleteDirectory(manifest.getStorageDir());
					manifestRepo.delete(manifest);
					return true;
				})
				.orElse(false);
	}

	/**
	 * The deprecated Bulk Data kick-off URL, populated only where there is a genuine single
	 * request: $export for BULK_EXPORT and _history (with _since for an incremental run) for
	 * HISTORY. SEARCH, SEARCH_LAST_UPDATED, and SEARCH_LAST_UPDATED_PARTITIONED issue per-type
	 * searches with no single kick-off URL, so they return null and the field is omitted from the
	 * manifest. AUTO also returns null: each server resolves its own concrete strategy per run,
	 * so the batch has no single knowable request.
	 */
	private static String buildRequestUrl(CrawlStrategy strategy, List<String> serverKeys, String windowSince) {
		String base = serverKeys.isEmpty() ? "" : serverKeys.get(0);
		return switch (strategy) {
			case BULK_EXPORT -> base + "/$export?_type=" + String.join("%2C", PlanNetTypes.TYPES);
			case HISTORY -> windowSince != null
					? base + "/_history?_since=" + URLEncoder.encode(windowSince, StandardCharsets.UTF_8)
					: base + "/_history";
			case AUTO, SEARCH, SEARCH_LAST_UPDATED, SEARCH_LAST_UPDATED_PARTITIONED -> null;
		};
	}

	/** Keep the newest {@code retentionPerJob} snapshots for a job; delete older files and rows. */
	private void pruneOldManifests(String jobId) {
		int keep = props.getRetentionPerJob();
		if (keep <= 0) {
			return;
		}
		List<ManifestRecord> existing = manifestRepo.findByJobIdOrderByGeneratedAtDescIdDesc(jobId);
		for (int i = keep; i < existing.size(); i++) {
			ManifestRecord old = existing.get(i);
			deleteDirectory(old.getStorageDir());
			manifestRepo.delete(old);
		}
	}

	private void deleteDirectory(String dir) {
		if (dir == null) {
			return;
		}
		PathUtils.deleteRecursively(Path.of(dir));
	}

	public List<ManifestSummary> listManifests() {
		List<ManifestSummary> summaries = new ArrayList<>();
		for (ManifestRecord m : manifestRepo.findAllByOrderByGeneratedAtDescIdDesc()) {
			summaries.add(new ManifestSummary(
					m.getId(),
					m.getJobId(),
					m.getJobName(),
					m.getBatchId(),
					String.valueOf(m.getTransactionTime()),
					String.valueOf(m.getGeneratedAt()),
					m.getTotalResources(),
					m.getWindowSince(),
					m.getBuildDurationMs(),
					isRegenerating(m.getId())));
		}
		return summaries;
	}

	/** Render the manifest body, deriving output[] from the immutable files on disk. */
	public ManifestJson render(ManifestRecord manifest, String baseUrl) {
		List<ManifestJson.OutputEntry> output = new ArrayList<>();
		Path dir = Path.of(manifest.getStorageDir());
		if (Files.isDirectory(dir)) {
			Map<String, Long> counts = readCounts(dir);
			boolean countedByHand = false;
			try (Stream<Path> files = Files.list(dir)) {
				List<Path> snapshotFiles = files.filter(p -> {
							String name = p.toString();
							return name.endsWith(GZ_SUFFIX) || name.endsWith(NDJSON_SUFFIX);
						})
						.sorted()
						.toList();
				for (Path file : snapshotFiles) {
					String fileName = file.getFileName().toString();
					String type = fileName.endsWith(GZ_SUFFIX)
							? fileName.substring(0, fileName.length() - GZ_SUFFIX.length())
							: fileName.substring(0, fileName.length() - NDJSON_SUFFIX.length());
					Long count = counts.get(type);
					if (count == null) {
						count = countLines(file);
						counts.put(type, count);
						countedByHand = true;
					}
					// The served name is always the logical .ndjson; the .gz is transparent to clients.
					String url = baseUrl + "/api/manifests/" + manifest.getId() + "/files/" + type + NDJSON_SUFFIX;
					output.add(new ManifestJson.OutputEntry(type, url, count));
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to read snapshot " + manifest.getId(), e);
			}
			if (countedByHand) {
				writeCounts(dir, counts);
			}
		}
		return new ManifestJson(
				String.valueOf(manifest.getTransactionTime()),
				manifest.getRequest(),
				manifest.isRequiresAccessToken(),
				output,
				List.of());
	}

	/** The per-type counts recorded at export time; empty when missing or unreadable. */
	private Map<String, Long> readCounts(Path dir) {
		Path file = dir.resolve(NdjsonExportService.COUNTS_FILE);
		if (Files.isRegularFile(file)) {
			try {
				return MAPPER.readValue(file.toFile(), new TypeReference<TreeMap<String, Long>>() {});
			} catch (IOException e) {
				ourLog.warn("Unreadable {} in snapshot {}: {}", NdjsonExportService.COUNTS_FILE, dir, e.getMessage());
			}
		}
		return new TreeMap<>();
	}

	/**
	 * Self-heal a snapshot written before counts were recorded, so counting its lines by
	 * decompressing every file happens at most once. Best-effort: failing to write only means
	 * the next render counts again.
	 */
	private void writeCounts(Path dir, Map<String, Long> counts) {
		try {
			Files.writeString(dir.resolve(NdjsonExportService.COUNTS_FILE), MAPPER.writeValueAsString(counts));
		} catch (IOException e) {
			ourLog.warn("Failed to write {} in snapshot {}: {}", NdjsonExportService.COUNTS_FILE, dir, e.getMessage());
		}
	}

	private long countLines(Path file) throws IOException {
		try (BufferedReader reader = openReader(file)) {
			return reader.lines().count();
		}
	}

	private static BufferedReader openReader(Path file) throws IOException {
		if (file.toString().endsWith(GZ_SUFFIX)) {
			return new BufferedReader(
					new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
		}
		return Files.newBufferedReader(file);
	}
}

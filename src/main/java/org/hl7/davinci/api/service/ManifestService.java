package org.hl7.davinci.api.service;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlJob;
import org.hl7.davinci.api.entity.CrawlStrategy;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.model.ManifestJson;
import org.hl7.davinci.api.model.ManifestSummary;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Creates retained snapshots and renders the served manifest. */
@Service
public class ManifestService {

	private static final String NDJSON_SUFFIX = ".ndjson";

	private final NdjsonExportService ndjson;
	private final ManifestRepository manifestRepo;
	private final ApiProperties props;

	public ManifestService(NdjsonExportService ndjson, ManifestRepository manifestRepo, ApiProperties props) {
		this.ndjson = ndjson;
		this.manifestRepo = manifestRepo;
		this.props = props;
	}

	/** Write the NDJSON snapshot and persist a manifest row for it. */
	public ManifestRecord createManifest(
			CrawlJob job, String batchId, String windowSince, List<String> serverKeys, long operationStartNanos) {
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
		manifest.setBuildDurationMs((System.nanoTime() - operationStartNanos) / 1_000_000);

		ManifestRecord saved = manifestRepo.save(manifest);
		pruneOldManifests(job.getId());
		return saved;
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
	 * HISTORY. SEARCH issues per-type searches with no single kick-off URL, so it returns null
	 * and the field is omitted from the manifest.
	 */
	private static String buildRequestUrl(CrawlStrategy strategy, List<String> serverKeys, String windowSince) {
		String base = serverKeys.isEmpty() ? "" : serverKeys.get(0);
		return switch (strategy) {
			case BULK_EXPORT -> base + "/$export?_type=" + String.join("%2C", FhirCrawlClient.PLAN_NET_TYPES);
			case HISTORY -> windowSince != null
					? base + "/_history?_since=" + URLEncoder.encode(windowSince, StandardCharsets.UTF_8)
					: base + "/_history";
			case SEARCH -> null;
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
		Path path = Path.of(dir);
		if (!Files.exists(path)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(path)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		} catch (IOException ignored) {
			// best-effort cleanup
		}
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
					m.getBuildDurationMs()));
		}
		return summaries;
	}

	/** Render the manifest body, deriving output[] from the immutable files on disk. */
	public ManifestJson render(ManifestRecord manifest, String baseUrl) {
		List<ManifestJson.OutputEntry> output = new ArrayList<>();
		Path dir = Path.of(manifest.getStorageDir());
		if (Files.isDirectory(dir)) {
			try (Stream<Path> files = Files.list(dir)) {
				List<Path> ndjsonFiles = files.filter(p -> p.toString().endsWith(NDJSON_SUFFIX))
						.sorted()
						.toList();
				for (Path file : ndjsonFiles) {
					String fileName = file.getFileName().toString();
					String type = fileName.substring(0, fileName.length() - NDJSON_SUFFIX.length());
					String url = baseUrl + "/api/manifests/" + manifest.getId() + "/files/" + fileName;
					output.add(new ManifestJson.OutputEntry(type, url, countLines(file)));
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to read snapshot " + manifest.getId(), e);
			}
		}
		return new ManifestJson(
				String.valueOf(manifest.getTransactionTime()),
				manifest.getRequest(),
				manifest.isRequiresAccessToken(),
				output,
				List.of());
	}

	private long countLines(Path file) throws IOException {
		try (Stream<String> lines = Files.lines(file)) {
			return lines.count();
		}
	}
}

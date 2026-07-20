package org.hl7.davinci.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.common.PlanNetTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/** Writes one {Type}.ndjson per resource type from the aggregate store. */
@Service
public class NdjsonExportService {

	private static final Logger ourLog = LoggerFactory.getLogger(NdjsonExportService.class);

	/** Rows per keyset page; bounds heap for large snapshots. */
	private static final int EXPORT_PAGE_SIZE = 2000;

	/** Emit an export heartbeat at most every this many written records. */
	private static final int EXPORT_LOG_EVERY = 100_000;

	/** I/O and gzip buffer size; large buffers cut syscall and compression overhead on big snapshots. */
	private static final int BUFFER = 1 << 16;

	/**
	 * Per-type line counts recorded at write time, so rendering the manifest never has to
	 * decompress the snapshot files just to count them.
	 */
	public static final String COUNTS_FILE = "counts.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final CrawlResourceRepository resourceRepo;
	private final ServerRegistry serverRegistry;
	private final ApiProperties props;

	public NdjsonExportService(
			CrawlResourceRepository resourceRepo, ServerRegistry serverRegistry, ApiProperties props) {
		this.resourceRepo = resourceRepo;
		this.serverRegistry = serverRegistry;
		this.props = props;
	}

	public record SnapshotResult(String storageDir, long totalResources) {}

	/** Write the aggregate for the given servers into {storage}/{manifestId}/{Type}.ndjson. */
	public SnapshotResult writeSnapshot(String manifestId, List<String> serverKeys) {
		Path dir = Path.of(props.getStoragePath(), manifestId);
		Map<String, BufferedWriter> writers = new HashMap<>();
		Map<String, Long> typeCounts = new TreeMap<>();
		Pageable page = PageRequest.ofSize(EXPORT_PAGE_SIZE);
		long total = 0;
		long lastLoggedAt = 0;
		long startNanos = System.nanoTime();
		try {
			Files.createDirectories(dir);
			ourLog.info("NDJSON export starting for {} server(s) into {}", serverKeys.size(), dir);
			for (String serverKey : serverKeys) {
				OptionalInt serverId = serverRegistry.idIfExists(serverKey);
				if (serverId.isEmpty()) {
					ourLog.debug("Skipping NDJSON export for server {}: no crawl_server row", serverKey);
					continue;
				}
				for (String type : PlanNetTypes.TYPES) {
					int typeId = PlanNetTypes.idOf(type);
					String afterUid = "";
					while (true) {
						List<CrawlResource> batch =
								resourceRepo.findByIdServerIdAndIdTypeIdAndIdUidGreaterThanOrderByIdUidAsc(
										serverId.getAsInt(), typeId, afterUid, page);
						if (batch.isEmpty()) {
							break;
						}
						BufferedWriter writer = writerFor(writers, dir, type);
						for (CrawlResource resource : batch) {
							writer.write(ResourceJsonCodec.decode(resource.getResourceJson()));
							writer.write("\n");
							total++;
							afterUid = resource.getId().getUid();
						}
						typeCounts.merge(type, (long) batch.size(), Long::sum);
						if (total - lastLoggedAt >= EXPORT_LOG_EVERY) {
							lastLoggedAt = total;
							ourLog.info(
									"NDJSON export progress: {} resources, {} ms",
									total,
									(System.nanoTime() - startNanos) / 1_000_000);
						}
						if (batch.size() < EXPORT_PAGE_SIZE) {
							break;
						}
					}
				}
			}
			Files.writeString(dir.resolve(COUNTS_FILE), MAPPER.writeValueAsString(typeCounts));
			ourLog.info(
					"NDJSON export complete: {} resources in {} ms",
					total,
					(System.nanoTime() - startNanos) / 1_000_000);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to write NDJSON snapshot " + manifestId, e);
		} finally {
			closeQuietly(writers.values());
		}
		return new SnapshotResult(dir.toString(), total);
	}

	private BufferedWriter writerFor(Map<String, BufferedWriter> writers, Path dir, String type) throws IOException {
		BufferedWriter existing = writers.get(type);
		if (existing != null) {
			return existing;
		}
		BufferedWriter writer = new BufferedWriter(NdjsonFiles.gzipWriter(dir.resolve(type + ".ndjson.gz")), BUFFER);
		writers.put(type, writer);
		return writer;
	}

	private void closeQuietly(Iterable<BufferedWriter> writers) {
		for (BufferedWriter writer : writers) {
			try {
				writer.close();
			} catch (IOException ignored) {
				// best-effort close
			}
		}
	}
}

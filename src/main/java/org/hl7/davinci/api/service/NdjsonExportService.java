package org.hl7.davinci.api.service;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

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

	/** Export gzip level (0-9). */
	private static final int GZIP_LEVEL = 3;

	private final CrawlResourceRepository resourceRepo;
	private final ApiProperties props;

	public NdjsonExportService(CrawlResourceRepository resourceRepo, ApiProperties props) {
		this.resourceRepo = resourceRepo;
		this.props = props;
	}

	public record SnapshotResult(String storageDir, long totalResources) {}

	/** Write the aggregate for the given servers into {storage}/{manifestId}/{Type}.ndjson. */
	public SnapshotResult writeSnapshot(String manifestId, List<String> serverKeys) {
		Path dir = Path.of(props.getStoragePath(), manifestId);
		Map<String, BufferedWriter> writers = new HashMap<>();
		Pageable page = PageRequest.ofSize(EXPORT_PAGE_SIZE);
		long total = 0;
		long lastLoggedAt = 0;
		long startNanos = System.nanoTime();
		try {
			Files.createDirectories(dir);
			ourLog.info("NDJSON export starting for {} server(s) into {}", serverKeys.size(), dir);
			for (String serverKey : serverKeys) {
				// Keys are serverKey|Type/id, so this server's rows are exactly those keyset-scanned
				// from just past "serverKey|" until the prefix stops matching.
				String prefix = serverKey + "|";
				String afterKey = prefix;
				boolean reachedNextServer = false;
				while (!reachedNextServer) {
					List<CrawlResource> batch = resourceRepo.findByKeyGreaterThanOrderByKeyAsc(afterKey, page);
					if (batch.isEmpty()) {
						break;
					}
					for (CrawlResource resource : batch) {
						if (!resource.getKey().startsWith(prefix)) {
							reachedNextServer = true;
							break;
						}
						BufferedWriter writer = writerFor(writers, dir, resource.getResourceType());
						writer.write(ResourceJsonCodec.decode(resource.getResourceJson()));
						writer.write("\n");
						total++;
						afterKey = resource.getKey();
					}
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
		OutputStream file = new BufferedOutputStream(Files.newOutputStream(dir.resolve(type + ".ndjson.gz")), BUFFER);
		OutputStream gz = new GZIPOutputStream(file, BUFFER) {
			{
				def.setLevel(GZIP_LEVEL);
			}
		};
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8), BUFFER);
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

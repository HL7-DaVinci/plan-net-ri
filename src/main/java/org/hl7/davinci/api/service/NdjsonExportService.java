package org.hl7.davinci.api.service;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Writes one {Type}.ndjson per resource type from the aggregate store. */
@Service
public class NdjsonExportService {

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
		long total = 0;
		try {
			Files.createDirectories(dir);
			for (String serverKey : serverKeys) {
				for (CrawlResource resource : resourceRepo.findByServerKey(serverKey)) {
					BufferedWriter writer = writerFor(writers, dir, resource.getResourceType());
					writer.write(resource.getResourceJson());
					writer.write("\n");
					total++;
				}
			}
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
		BufferedWriter writer = Files.newBufferedWriter(dir.resolve(type + ".ndjson"));
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

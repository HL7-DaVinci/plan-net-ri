package org.hl7.davinci.publish.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.publish.feed.ChangeEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/**
 * Merges winners into a snapshot file using strictly-monotone version comparison, so replaying
 * any subset of versions in any order converges to the highest version and never regresses.
 */
public final class SnapshotFileMerger {

	private static final Logger ourLog = LoggerFactory.getLogger(SnapshotFileMerger.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SnapshotFileMerger() {}

	public record MergeResult(boolean changed, long count, long uncompressedBytes) {}

	private record ParsedLine(String id, Long versionId) {}

	/**
	 * Merges {@code winners} into {@code oldFileGz} (absent for a new type), writing the result to
	 * {@code outFileGz}. The output is always written in full; when {@link MergeResult#changed()} is
	 * false its content is byte-identical to the input and the caller should discard it.
	 */
	public static MergeResult merge(
			Path oldFileGz,
			Map<String, ChangeEntry> winners,
			Function<ChangeEntry, String> bodyLoader,
			Path outFileGz) {
		Set<String> survivingIds = new HashSet<>();
		boolean droppedAny = false;
		boolean appendedAny = false;
		long count = 0;
		long uncompressedBytes = 0;

		try (Writer writer = NdjsonFiles.gzipWriter(outFileGz)) {
			if (oldFileGz != null && Files.exists(oldFileGz)) {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(
						new GZIPInputStream(Files.newInputStream(oldFileGz)), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						if (line.isBlank()) {
							continue;
						}
						ParsedLine parsed = parseLine(line);
						if (parsed.id() == null || parsed.versionId() == null) {
							ourLog.warn(
									"Could not parse id/versionId from a snapshot line; copying it through unchanged");
							// Track only winner ids; must stay O(winners), not O(file).
							if (parsed.id() != null && winners.containsKey(parsed.id())) {
								survivingIds.add(parsed.id());
							}
							count++;
							uncompressedBytes += writeLine(writer, line);
							continue;
						}
						ChangeEntry winner = winners.get(parsed.id());
						if (winner != null && winner.versionId() > parsed.versionId()) {
							droppedAny = true;
							continue;
						}
						if (winner != null) {
							survivingIds.add(parsed.id());
						}
						count++;
						uncompressedBytes += writeLine(writer, line);
					}
				}
			}

			for (Map.Entry<String, ChangeEntry> entry : winners.entrySet()) {
				String id = entry.getKey();
				ChangeEntry winner = entry.getValue();
				if (survivingIds.contains(id) || winner.deleted()) {
					continue;
				}
				String body = bodyLoader.apply(winner);
				if (body == null) {
					ourLog.warn(
							"Body loader returned no content for {}/{} version {}; skipping append",
							winner.type(),
							winner.id(),
							winner.versionId());
					continue;
				}
				appendedAny = true;
				count++;
				uncompressedBytes += writeLine(writer, body);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to merge snapshot file " + outFileGz, e);
		}

		return new MergeResult(droppedAny || appendedAny, count, uncompressedBytes);
	}

	private static long writeLine(Writer writer, String content) throws IOException {
		writer.write(content);
		writer.write("\n");
		return content.getBytes(StandardCharsets.UTF_8).length + 1L;
	}

	private static ParsedLine parseLine(String line) {
		try {
			JsonNode node = MAPPER.readTree(line);
			JsonNode idNode = node.get("id");
			String id = idNode != null && idNode.isTextual() ? idNode.asText() : null;
			JsonNode versionNode = node.path("meta").get("versionId");
			Long versionId = null;
			if (versionNode != null && versionNode.isTextual()) {
				try {
					versionId = Long.parseLong(versionNode.asText());
				} catch (NumberFormatException ignored) {
					versionId = null;
				}
			}
			return new ParsedLine(id, versionId);
		} catch (IOException e) {
			return new ParsedLine(null, null);
		}
	}
}

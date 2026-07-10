package org.hl7.davinci.publish.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.publish.feed.ChangeEntry;
import org.hl7.davinci.publish.store.SnapshotFileMerger.MergeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotFileMergerTest {

	private static final String TYPE = "Organization";

	@Test
	void replayingAnySubsetOfVersionsConvergesToTheHighest(@TempDir Path tmp) throws IOException {
		Path base = writeGz(tmp, "base.ndjson.gz", line("A", 1, "v1"));
		Map<String, String> bodies = Map.of(
				"A@2", line("A", 2, "v2"),
				"A@3", line("A", 3, "v3"));

		// v2 then v3, in two merges
		Path stepA1 = tmp.resolve("stepA1.ndjson.gz");
		SnapshotFileMerger.merge(base, Map.of("A", winner("A", 2, false)), bodyLoader(bodies), stepA1);
		Path stepA2 = tmp.resolve("stepA2.ndjson.gz");
		SnapshotFileMerger.merge(stepA1, Map.of("A", winner("A", 3, false)), bodyLoader(bodies), stepA2);

		// v3 then v2, in two merges
		Path stepB1 = tmp.resolve("stepB1.ndjson.gz");
		SnapshotFileMerger.merge(base, Map.of("A", winner("A", 3, false)), bodyLoader(bodies), stepB1);
		Path stepB2 = tmp.resolve("stepB2.ndjson.gz");
		SnapshotFileMerger.merge(stepB1, Map.of("A", winner("A", 2, false)), bodyLoader(bodies), stepB2);

		// v2 and v3 resolved to their single winner (v3) applied in one merge
		Path stepC = tmp.resolve("stepC.ndjson.gz");
		SnapshotFileMerger.merge(base, Map.of("A", winner("A", 3, false)), bodyLoader(bodies), stepC);

		List<String> expected = List.of(line("A", 3, "v3"));
		assertEquals(expected, readLines(stepA2));
		assertEquals(expected, readLines(stepB2));
		assertEquals(expected, readLines(stepC));
	}

	@Test
	void deleteDropsTheLine(@TempDir Path tmp) throws IOException {
		Path base = writeGz(tmp, "base.ndjson.gz", line("A", 1, "v1"));
		Path out = tmp.resolve("out.ndjson.gz");

		MergeResult result =
				SnapshotFileMerger.merge(base, Map.of("A", winner("A", 2, true)), bodyLoader(Map.of()), out);

		assertTrue(result.changed());
		assertEquals(0, result.count());
		assertEquals(List.of(), readLines(out));
	}

	@Test
	void staleDeleteIsIgnored(@TempDir Path tmp) throws IOException {
		Path base = writeGz(tmp, "base.ndjson.gz", line("A", 2, "v2"));
		Path out = tmp.resolve("out.ndjson.gz");

		MergeResult result =
				SnapshotFileMerger.merge(base, Map.of("A", winner("A", 1, true)), bodyLoader(Map.of()), out);

		assertFalse(result.changed());
		assertEquals(List.of(line("A", 2, "v2")), readLines(out));
	}

	@Test
	void reviveAppendsAResourceAbsentFromTheOldFile(@TempDir Path tmp) throws IOException {
		Path base = writeGz(tmp, "base.ndjson.gz", line("B", 1, "b"));
		Path out = tmp.resolve("out.ndjson.gz");
		Map<String, String> bodies = Map.of("A@3", line("A", 3, "v3"));

		MergeResult result =
				SnapshotFileMerger.merge(base, Map.of("A", winner("A", 3, false)), bodyLoader(bodies), out);

		assertTrue(result.changed());
		assertEquals(List.of(line("B", 1, "b"), line("A", 3, "v3")), readLines(out));
	}

	@Test
	void newTypeWithNoOldFileAppendsFromScratch(@TempDir Path tmp) throws IOException {
		Path out = tmp.resolve("out.ndjson.gz");
		Map<String, String> bodies = Map.of("A@1", line("A", 1, "v1"));

		MergeResult result =
				SnapshotFileMerger.merge(null, Map.of("A", winner("A", 1, false)), bodyLoader(bodies), out);

		assertTrue(result.changed());
		assertEquals(1, result.count());
		assertEquals(List.of(line("A", 1, "v1")), readLines(out));
	}

	@Test
	void untouchedIdsAreCopiedThroughByteIdentically(@TempDir Path tmp) throws IOException {
		String untouched = line("B", 1, "b");
		Path base = writeGz(tmp, "base.ndjson.gz", untouched);
		Path out = tmp.resolve("out.ndjson.gz");

		SnapshotFileMerger.merge(base, Map.of(), bodyLoader(Map.of()), out);

		assertEquals(List.of(untouched), readLines(out));
	}

	@Test
	void applyingTheSameWinnersTwiceIsANoOpOnTheSecondMerge(@TempDir Path tmp) throws IOException {
		Path base = writeGz(tmp, "base.ndjson.gz", line("A", 1, "v1"));
		Map<String, String> bodies = Map.of("A@2", line("A", 2, "v2"));
		Map<String, ChangeEntry> winners = Map.of("A", winner("A", 2, false));

		Path first = tmp.resolve("first.ndjson.gz");
		MergeResult firstResult = SnapshotFileMerger.merge(base, winners, bodyLoader(bodies), first);
		Path second = tmp.resolve("second.ndjson.gz");
		MergeResult secondResult = SnapshotFileMerger.merge(first, winners, bodyLoader(bodies), second);

		assertTrue(firstResult.changed());
		assertFalse(secondResult.changed());
		assertEquals(readLines(first), readLines(second));
	}

	@Test
	void versionComparisonIsNumericNotLexical(@TempDir Path tmp) throws IOException {
		Path base = writeGz(tmp, "base.ndjson.gz", line("A", 9, "v9"));
		Path out = tmp.resolve("out.ndjson.gz");
		Map<String, String> bodies = Map.of("A@10", line("A", 10, "v10"));

		MergeResult result =
				SnapshotFileMerger.merge(base, Map.of("A", winner("A", 10, false)), bodyLoader(bodies), out);

		assertTrue(result.changed());
		assertEquals(List.of(line("A", 10, "v10")), readLines(out));
	}

	@Test
	void malformedLineIsCopiedThroughAndMergeStillSucceeds(@TempDir Path tmp) throws IOException {
		String malformed = "not valid json";
		String good = line("B", 1, "b");
		Path base = writeGz(tmp, "base.ndjson.gz", malformed, good);
		Path out = tmp.resolve("out.ndjson.gz");

		MergeResult result = SnapshotFileMerger.merge(base, Map.of(), bodyLoader(Map.of()), out);

		assertFalse(result.changed());
		assertEquals(List.of(malformed, good), readLines(out));
	}

	@Test
	void countAndUncompressedBytesMatchTheOutputExactly(@TempDir Path tmp) throws IOException {
		String kept = line("B", 1, "b");
		String appendedBody = line("A", 1, "a");
		Path base = writeGz(tmp, "base.ndjson.gz", kept);
		Path out = tmp.resolve("out.ndjson.gz");
		Map<String, String> bodies = Map.of("A@1", appendedBody);

		MergeResult result =
				SnapshotFileMerger.merge(base, Map.of("A", winner("A", 1, false)), bodyLoader(bodies), out);

		long expectedBytes = (kept.getBytes(StandardCharsets.UTF_8).length + 1L)
				+ (appendedBody.getBytes(StandardCharsets.UTF_8).length + 1L);
		assertEquals(2, result.count());
		assertEquals(expectedBytes, result.uncompressedBytes());
	}

	private static ChangeEntry winner(String id, long versionId, boolean deleted) {
		return new ChangeEntry(TYPE, id, versionId, deleted, 0L);
	}

	private static java.util.function.Function<ChangeEntry, String> bodyLoader(Map<String, String> bodies) {
		return entry -> bodies.get(entry.id() + "@" + entry.versionId());
	}

	private static String line(String id, long versionId, String name) {
		return String.format(
				"{\"resourceType\":\"%s\",\"id\":\"%s\",\"meta\":{\"versionId\":\"%d\"},\"name\":\"%s\"}",
				TYPE, id, versionId, name);
	}

	private static Path writeGz(Path dir, String fileName, String... lines) throws IOException {
		Path file = dir.resolve(fileName);
		try (Writer writer = NdjsonFiles.gzipWriter(file)) {
			for (String l : lines) {
				writer.write(l);
				writer.write("\n");
			}
		}
		return file;
	}

	private static List<String> readLines(Path gzFile) throws IOException {
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new GZIPInputStream(Files.newInputStream(gzFile)), StandardCharsets.UTF_8))) {
			String l;
			while ((l = reader.readLine()) != null) {
				lines.add(l);
			}
		}
		return lines;
	}
}

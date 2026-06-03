package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.davinci.api.service.DeletionEntry;
import org.hl7.davinci.api.service.DiffUtil;
import org.hl7.davinci.api.service.FetchedResource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiffUtilTest {

	private static FetchedResource res(String key, String version, String lastUpdated) {
		return new FetchedResource(key, "Organization", "x", version, lastUpdated, "{}", 2);
	}

	@Test
	void classifiesAddedUpdatedUnchanged() {
		Map<String, DiffUtil.VersionInfo> existing = Map.of(
				"s|Organization/a", new DiffUtil.VersionInfo("1", "2026-01-01"),
				"s|Organization/b", new DiffUtil.VersionInfo("1", "2026-01-01"),
				"s|Practitioner/p", new DiffUtil.VersionInfo("2", "2026-02-02"));

		List<FetchedResource> incoming = List.of(
				res("s|Organization/c", "1", "2026-03-03"), // new key -> added
				res("s|Organization/a", "1", "2026-09-09"), // same versionId -> unchanged
				res("s|Organization/b", "2", "2026-01-01"), // same lastUpdated -> unchanged
				res("s|Practitioner/p", "3", "2026-03-03")); // both differ -> updated

		DiffUtil.DiffResult diff = DiffUtil.computeDiff(incoming, existing);

		assertEquals(1, diff.added().size());
		assertEquals("s|Organization/c", diff.added().get(0).key());
		assertEquals(1, diff.updated().size());
		assertEquals("s|Practitioner/p", diff.updated().get(0).key());
		assertEquals(2, diff.unchanged().size());
	}

	@Test
	void computesFullSnapshotDeletions() {
		Set<String> existing = Set.of("s|Location/x", "s|Organization/a");
		Set<String> fetched = Set.of("s|Organization/a", "s|Organization/c");

		List<String> deleted = DiffUtil.computeDeletedKeys(existing, fetched);

		assertEquals(List.of("s|Location/x"), deleted);
	}

	@Test
	void mapsHistoryDeletionsToExistingKeysOnly() {
		Set<String> existing = Set.of("s|Location/x", "s|Organization/a");
		List<DeletionEntry> deletions =
				List.of(new DeletionEntry("Location", "x"), new DeletionEntry("Endpoint", "z"));

		List<String> keys = DiffUtil.applyDeletions(deletions, "s", existing);

		assertEquals(1, keys.size());
		assertTrue(keys.contains("s|Location/x"));
	}
}

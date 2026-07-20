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

	private static FetchedResource res(String id, String version, String lastUpdated) {
		return new FetchedResource("Organization", id, version, lastUpdated, "{}", 2);
	}

	@Test
	void classifiesAddedUpdatedUnchanged() {
		Map<String, DiffUtil.VersionInfo> existing = Map.of(
				"Organization/a", new DiffUtil.VersionInfo("1", "2026-01-01"),
				"Organization/b", new DiffUtil.VersionInfo("1", "2026-01-01"),
				"Practitioner/p", new DiffUtil.VersionInfo("2", "2026-02-02"));

		List<FetchedResource> incoming = List.of(
				res("c", "1", "2026-03-03"), // new key -> added
				res("a", "1", "2026-09-09"), // same versionId -> unchanged
				res("b", "2", "2026-01-01"), // same lastUpdated -> unchanged
				new FetchedResource("Practitioner", "p", "3", "2026-03-03", "{}", 2)); // both differ -> updated

		DiffUtil.DiffResult diff = DiffUtil.computeDiff(incoming, existing);

		assertEquals(1, diff.added().size());
		assertEquals("c", diff.added().get(0).id());
		assertEquals(1, diff.updated().size());
		assertEquals("p", diff.updated().get(0).id());
		assertEquals(2, diff.unchanged().size());
	}

	@Test
	void computesFullSnapshotDeletions() {
		Set<String> existing = Set.of("Location/x", "Organization/a");
		Set<String> fetched = Set.of("Organization/a", "Organization/c");

		List<String> deleted = DiffUtil.computeDeletedKeys(existing, fetched);

		assertEquals(List.of("Location/x"), deleted);
	}

	@Test
	void mapsHistoryDeletionsToExistingKeysOnly() {
		Set<String> existing = Set.of("Location/x", "Organization/a");
		List<DeletionEntry> deletions =
				List.of(new DeletionEntry("Location", "x"), new DeletionEntry("Endpoint", "z"));

		List<String> keys = DiffUtil.applyDeletions(deletions, existing);

		assertEquals(1, keys.size());
		assertTrue(keys.contains("Location/x"));
	}
}

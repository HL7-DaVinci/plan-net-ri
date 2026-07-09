package org.hl7.davinci.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.davinci.common.PlanNetTypes;
import org.hl7.davinci.publish.BulkPublishManifestJson;
import org.hl7.davinci.publish.PublishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishServiceTest {

	@Test
	void rendersManifestFromSnapshotMeta() {
		PublishProperties publishProps = new PublishProperties();
		publishProps.setIntervalMs(5000);
		PublishService service = new PublishService(null, null, null, publishProps);

		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z", List.of(new PublishService.FileMeta("Organization", 3, 456, "snap-1")));

		BulkPublishManifestJson manifest = service.render(meta, "https://directory.example.org");

		assertEquals(PublishService.MANIFEST_TYPE, manifest.manifestType());
		assertEquals("2026-07-07T15:00:00Z", manifest.transactionTime());
		assertEquals("PT5S", manifest.updateCadence());
		assertFalse(manifest.requiresAccessToken());
		assertEquals(1, manifest.output().size());
		BulkPublishManifestJson.OutputEntry entry = manifest.output().get(0);
		assertEquals("Organization", entry.type());
		assertEquals("https://directory.example.org/api/publish/snap-1/Organization.ndjson", entry.url());
		assertEquals(3, entry.count());
		assertEquals(456, entry.fileSize());
	}

	@Test
	void omitsOutputEntriesForTypesWithNoResources() {
		PublishService service = new PublishService(null, null, null, new PublishProperties());

		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta("2026-07-07T15:00:00Z", List.of());

		BulkPublishManifestJson manifest = service.render(meta, "https://directory.example.org");

		assertTrue(manifest.output().isEmpty(), "a snapshot with no exported types renders an empty output array");
	}

	@Test
	void reusedTypeUrlUsesItsOwningSnapshotIdWhileAReExportedTypeUsesTheNewOne() {
		PublishService service = new PublishService(null, null, null, new PublishProperties());

		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z",
				List.of(
						new PublishService.FileMeta("Organization", 5, 900, "new-snapshot"),
						new PublishService.FileMeta("Location", 2, 300, "older-snapshot")));

		BulkPublishManifestJson manifest = service.render(meta, "https://directory.example.org");

		Map<String, String> urlByType = new HashMap<>();
		manifest.output().forEach(entry -> urlByType.put(entry.type(), entry.url()));
		assertEquals(
				"https://directory.example.org/api/publish/new-snapshot/Organization.ndjson",
				urlByType.get("Organization"),
				"a re-exported type's URL points at the new snapshot");
		assertEquals(
				"https://directory.example.org/api/publish/older-snapshot/Location.ndjson",
				urlByType.get("Location"),
				"a reused type's URL keeps pointing at the snapshot that physically holds its file");
	}

	@Test
	void retentionKeepsOnlyTheNewestWithinTheWindow() {
		Map<String, Instant> idToTransactionTime = Map.of(
				"oldest", Instant.parse("2026-07-01T00:00:00Z"),
				"middle", Instant.parse("2026-07-02T00:00:00Z"),
				"newest", Instant.parse("2026-07-03T00:00:00Z"));

		Set<String> toDelete = PublishService.idsToDelete(idToTransactionTime, "newest", 2);

		assertEquals(Set.of("oldest"), toDelete);
	}

	@Test
	void retentionNeverDeletesTheCurrentSnapshotEvenWhenOutsideTheWindow() {
		Map<String, Instant> idToTransactionTime = Map.of(
				"stale-current", Instant.parse("2026-01-01T00:00:00Z"),
				"a", Instant.parse("2026-07-04T00:00:00Z"),
				"b", Instant.parse("2026-07-03T00:00:00Z"),
				"c", Instant.parse("2026-07-02T00:00:00Z"));

		Set<String> toDelete = PublishService.idsToDelete(idToTransactionTime, "stale-current", 2);

		assertEquals(Set.of("c"), toDelete, "the two newest (a, b) are kept by the window; stale-current survives"
				+ " only because it is current; c falls outside both");
	}

	@Test
	void retentionOfZeroOrLessMeansUnlimited() {
		Map<String, Instant> idToTransactionTime =
				Map.of("a", Instant.parse("2026-07-03T00:00:00Z"), "b", Instant.parse("2026-07-02T00:00:00Z"));

		assertTrue(PublishService.idsToDelete(idToTransactionTime, "a", 0).isEmpty());
	}

	@Test
	void aPrunedByCountSnapshotSurvivesWhileARetainedMetaReferencesIt() {
		Set<String> byCount = Set.of("old");
		Map<String, PublishService.SnapshotMeta> retainedMetas = Map.of(
				"kept",
				new PublishService.SnapshotMeta(
						"2026-07-03T00:00:00Z", List.of(new PublishService.FileMeta("Organization", 5, 900, "old"))));

		assertTrue(
				PublishService.subtractReferencedIds(byCount, retainedMetas).isEmpty(),
				"old is spared because the kept snapshot's Organization file still lives in it");
	}

	@Test
	void aPrunedByCountSnapshotIsDeletedOnceNoRetainedMetaReferencesIt() {
		Set<String> byCount = Set.of("old");
		Map<String, PublishService.SnapshotMeta> retainedMetas = Map.of(
				"kept",
				new PublishService.SnapshotMeta(
						"2026-07-03T00:00:00Z", List.of(new PublishService.FileMeta("Organization", 5, 900, "kept"))));

		assertEquals(Set.of("old"), PublishService.subtractReferencedIds(byCount, retainedMetas));
	}

	@Test
	void firstRunTreatsAllTypesAsChanged() {
		assertEquals(Set.copyOf(PlanNetTypes.TYPES), PublishService.changedTypes(true, Map.of()));
	}

	@Test
	void noHistoryForAnyTypeYieldsNoChanges() {
		Map<String, Boolean> none = new HashMap<>();
		for (String type : PlanNetTypes.TYPES) {
			none.put(type, false);
		}

		assertTrue(PublishService.changedTypes(false, none).isEmpty());
	}

	@Test
	void mixedHistoryResultsYieldOnlyTheChangedTypes() {
		Map<String, Boolean> hasHistory = new HashMap<>();
		for (String type : PlanNetTypes.TYPES) {
			hasHistory.put(type, false);
		}
		hasHistory.put("Organization", true);
		hasHistory.remove("Endpoint");

		Set<String> changed = PublishService.changedTypes(false, hasHistory);

		assertEquals(
				Set.of("Organization", "Endpoint"),
				changed,
				"a type with history and a type missing an answer both mark as changed; no-history types do not");
	}

	@Test
	void pageBoundaryDefersRowsAtTheMaxInstantToTheDrain() {
		Instant t1 = Instant.parse("2026-07-01T00:00:00.000Z");
		Instant t2 = Instant.parse("2026-07-01T00:00:00.001Z");
		Instant t3 = Instant.parse("2026-07-01T00:00:00.002Z");

		PublishService.PageBoundary boundary = PublishService.pageBoundary(List.of(t1, t2, t3), 10);

		assertEquals(2, boundary.writeThroughIndex(), "rows strictly below the max instant are written directly");
		assertEquals(t3, boundary.maxInstant());
		assertTrue(boundary.isFinalPage(), "a page shorter than the requested size is the last page");
	}

	@Test
	void pageBoundaryDefersTheWholePageWhenEveryRowSharesTheMaxInstant() {
		Instant t = Instant.parse("2026-07-01T00:00:00.000Z");

		PublishService.PageBoundary boundary = PublishService.pageBoundary(List.of(t, t, t), 3);

		assertEquals(0, boundary.writeThroughIndex(), "a cluster spanning the entire page defers all rows to the drain");
		assertEquals(t, boundary.maxInstant());
		assertFalse(boundary.isFinalPage(), "a full page cannot be assumed to be the last page for its type");
	}

	@Test
	void pageBoundaryIsNotFinalWhenThePageIsFull() {
		Instant t1 = Instant.parse("2026-07-01T00:00:00.000Z");
		Instant t2 = Instant.parse("2026-07-01T00:00:00.001Z");

		PublishService.PageBoundary boundary = PublishService.pageBoundary(List.of(t1, t2), 2);

		assertFalse(boundary.isFinalPage(), "a page exactly at the page size must be followed by another query");
	}

	@Test
	void listSnapshotsReturnsNewestFirstWithCurrentFlag(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		ObjectMapper mapper = new ObjectMapper();
		PublishService service = new PublishService(null, null, mapper, props);

		writeSnapshotDir(
				tmp,
				mapper,
				"11111111-1111-1111-1111-111111111111",
				"2026-07-01T00:00:00Z",
				List.of(new PublishService.FileMeta(
						"Organization", 1, 100, "11111111-1111-1111-1111-111111111111")));
		writeSnapshotDir(
				tmp,
				mapper,
				"22222222-2222-2222-2222-222222222222",
				"2026-07-02T00:00:00Z",
				List.of(
						new PublishService.FileMeta(
								"Organization", 2, 200, "22222222-2222-2222-2222-222222222222"),
						new PublishService.FileMeta("Location", 1, 50, "11111111-1111-1111-1111-111111111111")));
		Files.writeString(tmp.resolve("current"), "22222222-2222-2222-2222-222222222222");

		List<PublishService.SnapshotListing> listings = service.listSnapshots();

		assertEquals(2, listings.size());
		assertEquals("22222222-2222-2222-2222-222222222222", listings.get(0).id());
		assertTrue(listings.get(0).current());
		assertEquals("2026-07-02T00:00:00Z", listings.get(0).transactionTime());
		assertEquals(2, listings.get(0).files().size());
		assertFalse(listings.get(1).current());
	}

	@Test
	void listSnapshotsSkipsDirectoriesWithUnreadableMeta(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		ObjectMapper mapper = new ObjectMapper();
		PublishService service = new PublishService(null, null, mapper, props);

		writeSnapshotDir(
				tmp,
				mapper,
				"11111111-1111-1111-1111-111111111111",
				"2026-07-01T00:00:00Z",
				List.of());
		Path corrupt = tmp.resolve("33333333-3333-3333-3333-333333333333");
		Files.createDirectories(corrupt);
		Files.writeString(corrupt.resolve("meta.json"), "not json");
		Path noMeta = tmp.resolve("44444444-4444-4444-4444-444444444444");
		Files.createDirectories(noMeta);

		List<PublishService.SnapshotListing> listings = service.listSnapshots();

		assertEquals(1, listings.size());
		assertEquals("11111111-1111-1111-1111-111111111111", listings.get(0).id());
	}

	@Test
	void listSnapshotsIsEmptyBeforeFirstPublish(@TempDir Path tmp) {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.resolve("never-created").toString());
		PublishService service = new PublishService(null, null, new ObjectMapper(), props);

		assertTrue(service.listSnapshots().isEmpty());
	}

	private static void writeSnapshotDir(
			Path root,
			ObjectMapper mapper,
			String id,
			String transactionTime,
			List<PublishService.FileMeta> files)
			throws Exception {
		Path dir = root.resolve(id);
		Files.createDirectories(dir);
		mapper.writeValue(
				dir.resolve("meta.json").toFile(), new PublishService.SnapshotMeta(transactionTime, files));
	}
}

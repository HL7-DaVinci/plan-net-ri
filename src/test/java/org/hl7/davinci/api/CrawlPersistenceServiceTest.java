package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.service.CrawlPersistenceService;
import org.hl7.davinci.api.service.DeletionEntry;
import org.hl7.davinci.api.service.FetchedResource;
import org.hl7.davinci.api.service.ResourceJsonCodec;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CrawlPersistenceServiceTest {

	@Test
	void incrementalDeleteDoesNotRemoveResourceFetchedInSameDelta() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(
				List.of(version("s|Organization/a", "1", "2026-01-01T00:00:00Z")), deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo);

		FetchedResource recreated = new FetchedResource(
				"s|Organization/a",
				"Organization",
				"a",
				"2",
				"2026-01-02T00:00:00Z",
				"{\"resourceType\":\"Organization\",\"id\":\"a\"}",
				40);

		CrawlPersistenceService.PersistCounts counts = service.persistIncremental(
				"s", "server", List.of(recreated), List.of(new DeletionEntry("Organization", "a")));

		assertEquals(0, counts.added());
		assertEquals(1, counts.updated());
		assertEquals(0, counts.deleted());
		assertEquals(1, counts.total(), "the aggregate still holds the recreated resource");
		assertEquals(List.of(), deletedIds.get());
	}

	@Test
	void fullSnapshotUpsertsOnlyChangesAndDeletesMissing() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(
				List.of(
						version("s|Organization/a", "1", "2026-01-01T00:00:00Z"),
						version("s|Organization/b", "1", "2026-01-01T00:00:00Z")),
				deletedIds,
				saved);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo);

		String json = "{\"resourceType\":\"Organization\",\"id\":\"c\"}";
		FetchedResource unchanged = new FetchedResource(
				"s|Organization/a", "Organization", "a", "1", "2026-01-01T00:00:00Z", "{}", 2);
		FetchedResource added = new FetchedResource("s|Organization/c", "Organization", "c", "1", null, json, 40);

		CrawlPersistenceService.PersistCounts counts =
				service.persistFullSnapshot("s", "server", List.of(unchanged, added));

		assertEquals(1, counts.added());
		assertEquals(0, counts.updated());
		assertEquals(1, counts.deleted());
		assertEquals(2, counts.total(), "two existing plus one added minus one deleted");
		assertEquals(
				List.of("s|Organization/c"),
				saved.get().stream().map(CrawlResource::getKey).toList(),
				"unchanged rows must not be rewritten");
		assertEquals(List.of("s|Organization/b"), deletedIds.get(), "keys absent from the fetch are deletions");
		assertTrue(saved.get().get(0).getResourceJson()[0] == (byte) 0x1f, "bodies are stored gzip-compressed");
		assertEquals(json, ResourceJsonCodec.decode(saved.get().get(0).getResourceJson()));
	}

	@Test
	void addedRowsAreFlaggedNewSoTheyInsertWithoutASelect() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo =
				repo(List.of(version("s|Organization/a", "1", "2026-01-01T00:00:00Z")), deletedIds, saved);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo);

		FetchedResource updated =
				new FetchedResource("s|Organization/a", "Organization", "a", "2", "2026-01-02T00:00:00Z", "{}", 2);
		FetchedResource added = new FetchedResource("s|Organization/b", "Organization", "b", "1", null, "{}", 2);

		service.persistFullSnapshot("s", "server", List.of(updated, added));

		CrawlResource addedRow = saved.get().stream()
				.filter(r -> r.getKey().equals("s|Organization/b"))
				.findFirst()
				.orElseThrow();
		CrawlResource updatedRow = saved.get().stream()
				.filter(r -> r.getKey().equals("s|Organization/a"))
				.findFirst()
				.orElseThrow();
		assertTrue(addedRow.isNew(), "an added row inserts (persist) without a pre-select");
		assertFalse(updatedRow.isNew(), "an updated row merges against the existing row");
	}

	@Test
	void repeatedStreamedAddedKeyCountsOnce() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		CrawlPersistenceService.SnapshotSession session =
				new CrawlPersistenceService(repo(List.of(), deletedIds, saved)).openSession("s", "server");
		FetchedResource first = new FetchedResource("s|Organization/a", "Organization", "a", "1", null, "{}", 2);
		FetchedResource duplicate = new FetchedResource("s|Organization/a", "Organization", "a", "2", null, "{}", 2);

		session.accept(List.of(first));
		session.accept(List.of(duplicate));
		CrawlPersistenceService.PersistCounts counts = session.finishFullSnapshot();

		assertEquals(1, counts.added());
		assertEquals(0, counts.updated());
		assertEquals(1, counts.total());
		assertEquals(List.of(true, false), saved.get().stream().map(CrawlResource::isNew).toList());
	}

	@Test
	void largeSnapshotPersistsEveryChangeAcrossMultipleChunks() {
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		int[] saveAllCalls = {0};
		CrawlResourceRepository resourceRepo = (CrawlResourceRepository) Proxy.newProxyInstance(
				CrawlResourceRepository.class.getClassLoader(),
				new Class<?>[] {CrawlResourceRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
						case "countByServerKey" -> 0L;
						case "findVersionViewByKeys" -> List.of();
					case "saveAll" -> {
						saveAllCalls[0]++;
						List<CrawlResource> acc = new ArrayList<>(saved.get());
						for (Object entity : (Iterable<?>) args[0]) {
							acc.add((CrawlResource) entity);
						}
						saved.set(acc);
						yield args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});

		List<FetchedResource> fetched = new ArrayList<>();
		for (int i = 0; i < 1500; i++) {
			fetched.add(new FetchedResource("s|Organization/" + i, "Organization", "" + i, "1", null, "{}", 2));
		}

		CrawlPersistenceService.PersistCounts counts =
				new CrawlPersistenceService(resourceRepo).persistFullSnapshot("s", "server", fetched);

		assertEquals(1500, counts.added());
		assertEquals(1500, counts.total());
		assertEquals(1500, saved.get().size(), "every changed resource must be persisted");
		assertEquals(2, saveAllCalls[0], "1500 rows should commit in two chunks");
	}

	@Test
	void incrementalDeletionOfAnAbsentKeyIsDropped() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo =
				repo(List.of(version("s|Organization/a", "1", "2026-01-01T00:00:00Z")), deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo);

		// A history-scan deletion for a key the DB never held must not be reported as a delete.
		CrawlPersistenceService.PersistCounts counts =
				service.persistIncremental("s", "server", List.of(), List.of(new DeletionEntry("Organization", "ghost")));

		assertEquals(0, counts.deleted());
		assertEquals(List.of(), deletedIds.get());
	}

	@Test
	void fullSnapshotDeletesOnlyTheTargetServersUnfetchedKeys() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		// Two servers share the table; crawling s1 must never touch s2's keys (PK-stream prefix bound).
		CrawlResourceRepository resourceRepo = repo(
				List.of(
						version("s1|Organization/a", "1", "2026-01-01T00:00:00Z"),
						version("s1|Organization/b", "1", "2026-01-01T00:00:00Z"),
						version("s2|Organization/c", "1", "2026-01-01T00:00:00Z")),
				deletedIds,
				saved);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo);

		FetchedResource a =
				new FetchedResource("s1|Organization/a", "Organization", "a", "1", "2026-01-01T00:00:00Z", "{}", 2);

		CrawlPersistenceService.PersistCounts counts = service.persistFullSnapshot("s1", "server", List.of(a));

		assertEquals(1, counts.deleted(), "only s1's un-fetched key b is deleted");
		assertEquals(List.of("s1|Organization/b"), deletedIds.get());
		assertEquals(1, counts.total(), "s1 had 2 rows; a kept, b deleted, 0 added");
	}

	@Test
	void resumedFullSessionDoesNotDeleteRowsBelowTheFloors() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(
				List.of(
						version("s|Organization/a", "1", "2026-01-01T00:00:00Z"),
						version("s|Organization/b", "1", "2026-01-01T00:00:00Z")),
				deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo);

		FetchedResource refetched = new FetchedResource(
				"s|Organization/a", "Organization", "a", "2", "2026-02-01T00:00:00Z", "{}", 2);
		CrawlPersistenceService.SnapshotSession session = service.openResumedFullSession("s", "server");
		session.accept(List.of(refetched));
		CrawlPersistenceService.PersistCounts counts = session.finishIncremental(List.of());

		assertEquals(0, counts.added());
		assertEquals(1, counts.updated());
		assertEquals(0, counts.deleted());
		assertEquals(2, counts.total(), "rows below the resume floors survive even though never re-fetched");
		assertEquals(List.of(), deletedIds.get());
	}

	@Test
	void resumedFullSessionRefusesTheFullSnapshotFinish() {
		AtomicReference<List<String>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo =
				repo(List.of(version("s|Organization/a", "1", "2026-01-01T00:00:00Z")), deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo);

		CrawlPersistenceService.SnapshotSession session = service.openResumedFullSession("s", "server");

		assertThrows(
				IllegalStateException.class,
				session::finishFullSnapshot,
				"the absent-key scan would misclassify everything below the floors as deleted");
	}

	private static CrawlResourceRepository repo(
			List<CrawlResourceRepository.ResourceVersionView> versions, AtomicReference<List<String>> deletedIds) {
		return repo(versions, deletedIds, new AtomicReference<>(List.of()));
	}

	private static CrawlResourceRepository repo(
			List<CrawlResourceRepository.ResourceVersionView> versions,
			AtomicReference<List<String>> deletedIds,
			AtomicReference<List<CrawlResource>> saved) {
		return (CrawlResourceRepository) Proxy.newProxyInstance(
				CrawlResourceRepository.class.getClassLoader(),
				new Class<?>[] {CrawlResourceRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "countByServerKey" -> versions.stream()
							.filter(v -> v.getKey().startsWith((String) args[0] + "|"))
							.count();
					case "findVersionViewByKeys" -> {
						@SuppressWarnings("unchecked")
						java.util.Collection<String> keys = (java.util.Collection<String>) args[0];
						yield versions.stream().filter(v -> keys.contains(v.getKey())).toList();
					}
					case "findKeysByKeyGreaterThanOrderByKeyAsc" -> {
						String afterKey = (String) args[0];
						int pageSize = ((org.springframework.data.domain.Pageable) args[1]).getPageSize();
						java.util.TreeSet<String> all = new java.util.TreeSet<>();
						versions.forEach(v -> all.add(v.getKey()));
						saved.get().forEach(e -> all.add(e.getKey()));
						yield all.stream().filter(k -> k.compareTo(afterKey) > 0).limit(pageSize).toList();
					}
					case "saveAll" -> {
						List<CrawlResource> entities = new ArrayList<>(saved.get());
						for (Object entity : (Iterable<?>) args[0]) {
							entities.add((CrawlResource) entity);
						}
						saved.set(entities);
						yield args[0];
					}
					case "deleteAllById" -> {
						List<String> ids = new ArrayList<>(deletedIds.get());
						for (Object id : (Iterable<?>) args[0]) {
							ids.add((String) id);
						}
						deletedIds.set(ids);
						yield null;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlResourceRepository.ResourceVersionView version(
			String key, String versionId, String lastUpdated) {
		return new CrawlResourceRepository.ResourceVersionView() {
			@Override
			public String getKey() {
				return key;
			}

			@Override
			public String getVersionId() {
				return versionId;
			}

			@Override
			public String getLastUpdated() {
				return lastUpdated;
			}
		};
	}
}

package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.entity.CrawlResourceId;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.service.CrawlPersistenceService;
import org.hl7.davinci.api.service.DeletionEntry;
import org.hl7.davinci.api.service.FetchedResource;
import org.hl7.davinci.api.service.ResourceJsonCodec;
import org.hl7.davinci.api.service.ServerRegistry;
import org.hl7.davinci.common.PlanNetTypes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class CrawlPersistenceServiceTest {

	private static final int ORG = PlanNetTypes.idOf("Organization");

	@Test
	void incrementalDeleteDoesNotRemoveResourceFetchedInSameDelta() {
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(List.of(row(1, "a", "1", "2026-01-01T00:00:00Z")), deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo, registry("s", 1));

		FetchedResource recreated = new FetchedResource(
				"Organization", "a", "2", "2026-01-02T00:00:00Z", "{\"resourceType\":\"Organization\",\"id\":\"a\"}", 40);

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
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(
				List.of(row(1, "a", "1", "2026-01-01T00:00:00Z"), row(1, "b", "1", "2026-01-01T00:00:00Z")),
				deletedIds,
				saved);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo, registry("s", 1));

		String json = "{\"resourceType\":\"Organization\",\"id\":\"c\"}";
		FetchedResource unchanged =
				new FetchedResource("Organization", "a", "1", "2026-01-01T00:00:00Z", "{}", 2);
		FetchedResource added = new FetchedResource("Organization", "c", "1", null, json, 40);

		CrawlPersistenceService.PersistCounts counts =
				service.persistFullSnapshot("s", "server", List.of(unchanged, added));

		assertEquals(1, counts.added());
		assertEquals(0, counts.updated());
		assertEquals(1, counts.deleted());
		assertEquals(2, counts.total(), "two existing plus one added minus one deleted");
		assertEquals(
				List.of("c"),
				saved.get().stream().map(r -> r.getId().getUid()).toList(),
				"unchanged rows must not be rewritten");
		assertEquals(List.of(id(1, "b")), deletedIds.get(), "keys absent from the fetch are deletions");
		assertTrue(saved.get().get(0).getResourceJson()[0] == (byte) 0x1f, "bodies are stored gzip-compressed");
		assertEquals(json, ResourceJsonCodec.decode(saved.get().get(0).getResourceJson()));
	}

	@Test
	void addedRowsAreFlaggedNewSoTheyInsertWithoutASelect() {
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(List.of(row(1, "a", "1", "2026-01-01T00:00:00Z")), deletedIds, saved);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo, registry("s", 1));

		FetchedResource updated = new FetchedResource("Organization", "a", "2", "2026-01-02T00:00:00Z", "{}", 2);
		FetchedResource added = new FetchedResource("Organization", "b", "1", null, "{}", 2);

		service.persistFullSnapshot("s", "server", List.of(updated, added));

		CrawlResource addedRow = saved.get().stream()
				.filter(r -> r.getId().getUid().equals("b"))
				.findFirst()
				.orElseThrow();
		CrawlResource updatedRow = saved.get().stream()
				.filter(r -> r.getId().getUid().equals("a"))
				.findFirst()
				.orElseThrow();
		assertTrue(addedRow.isNew(), "an added row inserts (persist) without a pre-select");
		assertFalse(updatedRow.isNew(), "an updated row merges against the existing row");
	}

	@Test
	void repeatedStreamedAddedKeyCountsOnce() {
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		CrawlPersistenceService.SnapshotSession session = new CrawlPersistenceService(
						repo(List.of(), deletedIds, saved), registry("s", 1))
				.openSession("s", "server");
		FetchedResource first = new FetchedResource("Organization", "a", "1", null, "{}", 2);
		FetchedResource duplicate = new FetchedResource("Organization", "a", "2", null, "{}", 2);

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
						case "countByIdServerId" -> 0L;
						case "findVersionViews" -> List.of();
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
			fetched.add(new FetchedResource("Organization", "" + i, "1", null, "{}", 2));
		}

		CrawlPersistenceService.PersistCounts counts = new CrawlPersistenceService(resourceRepo, registry("s", 1))
				.persistFullSnapshot("s", "server", fetched);

		assertEquals(1500, counts.added());
		assertEquals(1500, counts.total());
		assertEquals(1500, saved.get().size(), "every changed resource must be persisted");
		assertEquals(2, saveAllCalls[0], "1500 rows should commit in two chunks");
	}

	@Test
	void incrementalDeletionOfAnAbsentKeyIsDropped() {
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(List.of(row(1, "a", "1", "2026-01-01T00:00:00Z")), deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo, registry("s", 1));

		// A history-scan deletion for a key the DB never held must not be reported as a delete.
		CrawlPersistenceService.PersistCounts counts =
				service.persistIncremental("s", "server", List.of(), List.of(new DeletionEntry("Organization", "ghost")));

		assertEquals(0, counts.deleted());
		assertEquals(List.of(), deletedIds.get());
	}

	@Test
	void fullSnapshotDeletesOnlyTheTargetServersUnfetchedKeys() {
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		AtomicReference<List<CrawlResource>> saved = new AtomicReference<>(List.of());
		// Two servers share the table; crawling s1 must never touch s2's rows (PK-prefix bound).
		CrawlResourceRepository resourceRepo = repo(
				List.of(
						row(1, "a", "1", "2026-01-01T00:00:00Z"),
						row(1, "b", "1", "2026-01-01T00:00:00Z"),
						row(2, "c", "1", "2026-01-01T00:00:00Z")),
				deletedIds,
				saved);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo, registry(Map.of("s1", 1, "s2", 2)));

		FetchedResource a = new FetchedResource("Organization", "a", "1", "2026-01-01T00:00:00Z", "{}", 2);

		CrawlPersistenceService.PersistCounts counts = service.persistFullSnapshot("s1", "server", List.of(a));

		assertEquals(1, counts.deleted(), "only s1's un-fetched row b is deleted");
		assertEquals(List.of(id(1, "b")), deletedIds.get());
		assertEquals(1, counts.total(), "s1 had 2 rows; a kept, b deleted, 0 added");
	}

	@Test
	void resumedFullSessionDoesNotDeleteRowsBelowTheFloors() {
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(
				List.of(row(1, "a", "1", "2026-01-01T00:00:00Z"), row(1, "b", "1", "2026-01-01T00:00:00Z")), deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo, registry("s", 1));

		FetchedResource refetched = new FetchedResource("Organization", "a", "2", "2026-02-01T00:00:00Z", "{}", 2);
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
		AtomicReference<List<CrawlResourceId>> deletedIds = new AtomicReference<>(List.of());
		CrawlResourceRepository resourceRepo = repo(List.of(row(1, "a", "1", "2026-01-01T00:00:00Z")), deletedIds);
		CrawlPersistenceService service = new CrawlPersistenceService(resourceRepo, registry("s", 1));

		CrawlPersistenceService.SnapshotSession session = service.openResumedFullSession("s", "server");

		assertThrows(
				IllegalStateException.class,
				session::finishFullSnapshot,
				"the absent-key scan would misclassify everything below the floors as deleted");
	}

	private record ExistingRow(CrawlResourceId id, String versionId, String lastUpdated) {}

	private static ExistingRow row(int serverId, String uid, String versionId, String lastUpdated) {
		return new ExistingRow(id(serverId, uid), versionId, lastUpdated);
	}

	private static CrawlResourceId id(int serverId, String uid) {
		return new CrawlResourceId(serverId, ORG, uid);
	}

	/** A fake {@link ServerRegistry} resolving a single serverKey to a fixed serverId. */
	private static ServerRegistry registry(String serverKey, int serverId) {
		return registry(Map.of(serverKey, serverId));
	}

	private static ServerRegistry registry(Map<String, Integer> ids) {
		return new ServerRegistry(null) {
			@Override
			public int idFor(String serverKey) {
				return ids.get(serverKey);
			}
		};
	}

	private static CrawlResourceRepository repo(
			List<ExistingRow> existing, AtomicReference<List<CrawlResourceId>> deletedIds) {
		return repo(existing, deletedIds, new AtomicReference<>(List.of()));
	}

	private static CrawlResourceRepository repo(
			List<ExistingRow> existing,
			AtomicReference<List<CrawlResourceId>> deletedIds,
			AtomicReference<List<CrawlResource>> saved) {
		return (CrawlResourceRepository) Proxy.newProxyInstance(
				CrawlResourceRepository.class.getClassLoader(),
				new Class<?>[] {CrawlResourceRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "countByIdServerId" -> {
						int serverId = (int) args[0];
						yield existing.stream()
								.filter(r -> r.id().getServerId() == serverId)
								.count();
					}
					case "findVersionViews" -> {
						int serverId = (int) args[0];
						int typeId = (int) args[1];
						@SuppressWarnings("unchecked")
						Collection<String> uids = (Collection<String>) args[2];
						yield existing.stream()
								.filter(r -> r.id().getServerId() == serverId
										&& r.id().getTypeId() == typeId
										&& uids.contains(r.id().getUid()))
								.<CrawlResourceRepository.ResourceVersionView>map(
										r -> version(r.id().getUid(), r.versionId(), r.lastUpdated()))
								.toList();
					}
					case "findUids" -> {
						int serverId = (int) args[0];
						int typeId = (int) args[1];
						String afterUid = (String) args[2];
						int pageSize = ((Pageable) args[3]).getPageSize();
						TreeSet<String> all = new TreeSet<>();
						existing.stream()
								.filter(r -> r.id().getServerId() == serverId && r.id().getTypeId() == typeId)
								.forEach(r -> all.add(r.id().getUid()));
						saved.get().stream()
								.filter(e -> e.getId().getServerId() == serverId && e.getId().getTypeId() == typeId)
								.forEach(e -> all.add(e.getId().getUid()));
						yield all.stream()
								.filter(u -> u.compareTo(afterUid) > 0)
								.limit(pageSize)
								.toList();
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
						List<CrawlResourceId> ids = new ArrayList<>(deletedIds.get());
						for (Object id : (Iterable<?>) args[0]) {
							ids.add((CrawlResourceId) id);
						}
						deletedIds.set(ids);
						yield null;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static CrawlResourceRepository.ResourceVersionView version(
			String uid, String versionId, String lastUpdated) {
		return new CrawlResourceRepository.ResourceVersionView() {
			@Override
			public String getUid() {
				return uid;
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

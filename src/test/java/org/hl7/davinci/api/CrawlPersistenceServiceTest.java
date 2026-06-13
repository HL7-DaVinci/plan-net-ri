package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		assertTrue(saved.get().get(0).getResourceJson().startsWith("gz:"), "bodies are stored compressed");
		assertEquals(json, ResourceJsonCodec.decode(saved.get().get(0).getResourceJson()));
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
					case "findVersionViewByServerKey" -> versions;
					case "saveAll" -> {
						List<CrawlResource> entities = new ArrayList<>();
						for (Object entity : (Iterable<?>) args[0]) {
							entities.add((CrawlResource) entity);
						}
						saved.set(entities);
						yield args[0];
					}
					case "deleteAllById" -> {
						List<String> ids = new ArrayList<>();
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

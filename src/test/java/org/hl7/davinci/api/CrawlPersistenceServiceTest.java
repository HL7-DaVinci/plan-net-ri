package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.api.service.CrawlPersistenceService;
import org.hl7.davinci.api.service.DeletionEntry;
import org.hl7.davinci.api.service.FetchedResource;
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
		assertEquals(List.of(), deletedIds.get());
	}

	@SuppressWarnings("unchecked")
	private static CrawlResourceRepository repo(
			List<CrawlResourceRepository.ResourceVersionView> versions, AtomicReference<List<String>> deletedIds) {
		return (CrawlResourceRepository) Proxy.newProxyInstance(
				CrawlResourceRepository.class.getClassLoader(),
				new Class<?>[] {CrawlResourceRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findVersionViewByServerKey" -> versions;
					case "saveAll" -> args[0];
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

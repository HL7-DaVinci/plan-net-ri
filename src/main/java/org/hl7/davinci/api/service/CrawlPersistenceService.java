package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single writer of {@code crawl_resource}. Methods must name {@code crawlerTransactionManager};
 * a bare {@code @Transactional} would bind to HAPI's primary tx manager.
 */
@Service
public class CrawlPersistenceService {

	private final CrawlResourceRepository resourceRepo;

	public CrawlPersistenceService(CrawlResourceRepository resourceRepo) {
		this.resourceRepo = resourceRepo;
	}

	public record PersistCounts(int added, int updated, int deleted) {}

	/** Replace a server's aggregate with the fresh snapshot; returns the change counts. */
	@Transactional("crawlerTransactionManager")
	public PersistCounts persistFullSnapshot(String serverKey, String serverLabel, List<FetchedResource> fetched) {
		Map<String, DiffUtil.VersionInfo> existing = loadIndex(serverKey);
		DiffUtil.DiffResult diff = DiffUtil.computeDiff(fetched, existing);
		Set<String> fetchedKeys = fetched.stream().map(FetchedResource::key).collect(Collectors.toSet());
		List<String> deletedKeys = DiffUtil.computeDeletedKeys(existing.keySet(), fetchedKeys);

		resourceRepo.deleteByServerKey(serverKey);
		resourceRepo.saveAll(toEntities(serverKey, serverLabel, fetched));

		return new PersistCounts(diff.added().size(), diff.updated().size(), deletedKeys.size());
	}

	/** Upsert the changed resources and delete the resolved deletion keys. */
	@Transactional("crawlerTransactionManager")
	public PersistCounts persistIncremental(
			String serverKey, String serverLabel, List<FetchedResource> fetched, List<DeletionEntry> deletions) {
		Map<String, DiffUtil.VersionInfo> existing = loadIndex(serverKey);
		DiffUtil.DiffResult diff = DiffUtil.computeDiff(fetched, existing);
		Set<String> fetchedKeys = fetched.stream().map(FetchedResource::key).collect(Collectors.toSet());
		List<String> deletedKeys = DiffUtil.applyDeletions(deletions, serverKey, existing.keySet());
		deletedKeys.removeIf(fetchedKeys::contains);

		List<FetchedResource> changed = new ArrayList<>(diff.added());
		changed.addAll(diff.updated());
		resourceRepo.saveAll(toEntities(serverKey, serverLabel, changed));
		if (!deletedKeys.isEmpty()) {
			resourceRepo.deleteAllById(deletedKeys);
		}

		return new PersistCounts(diff.added().size(), diff.updated().size(), deletedKeys.size());
	}

	private Map<String, DiffUtil.VersionInfo> loadIndex(String serverKey) {
		Map<String, DiffUtil.VersionInfo> index = new HashMap<>();
		for (CrawlResourceRepository.ResourceVersionView view : resourceRepo.findVersionViewByServerKey(serverKey)) {
			index.put(view.getKey(), new DiffUtil.VersionInfo(view.getVersionId(), view.getLastUpdated()));
		}
		return index;
	}

	private List<CrawlResource> toEntities(String serverKey, String serverLabel, List<FetchedResource> fetched) {
		List<CrawlResource> entities = new ArrayList<>(fetched.size());
		for (FetchedResource fr : fetched) {
			CrawlResource e = new CrawlResource();
			e.setKey(fr.key());
			e.setServerKey(serverKey);
			e.setServerLabel(serverLabel);
			e.setResourceType(fr.resourceType());
			e.setResId(fr.id());
			e.setVersionId(fr.versionId());
			e.setLastUpdated(fr.lastUpdated());
			e.setResourceJson(fr.json());
			entities.add(e);
		}
		return entities;
	}
}

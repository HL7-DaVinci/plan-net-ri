package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single writer of {@code crawl_resource}. Crawls feed batches to a {@link SnapshotSession} as
 * they fetch, so the whole resource set is never held in memory; only the diff index and the seen
 * keys are retained. Upserts and deletes run in per-chunk transactions (the repository's own
 * {@code crawlerTransactionManager}-bound methods), so no server snapshot sits in one Hibernate
 * session or one H2 transaction.
 */
@Service
public class CrawlPersistenceService {

	private static final Logger ourLog = LoggerFactory.getLogger(CrawlPersistenceService.class);

	/** Rows per write transaction. */
	private static final int CHUNK = 1000;

	/** Emit a persist heartbeat at most every this many processed records. */
	private static final int LOG_EVERY = 50_000;

	/** Bounded recent-key window for deduping a first crawl's re-fetched keys. */
	private static final int DEDUP_WINDOW = 50_000;

	/** Keys per page when streaming the aggregate to find full-snapshot deletions. */
	private static final int DELETE_SCAN_PAGE = 1000;

	private final CrawlResourceRepository resourceRepo;

	public CrawlPersistenceService(CrawlResourceRepository resourceRepo) {
		this.resourceRepo = resourceRepo;
	}

	/** Change counts plus the server's aggregate size after the run was applied. */
	public record PersistCounts(int added, int updated, int deleted, int total) {}

	/** Accumulates a streamed crawl into the aggregate; finalize with the matching finish method. */
	public interface SnapshotSession {
		void accept(List<FetchedResource> batch);

		/** A full crawl: keys never seen in the stream are deletions. */
		PersistCounts finishFullSnapshot();

		/** An incremental crawl: deletions come from the explicit list (minus anything refetched). */
		PersistCounts finishIncremental(List<DeletionEntry> deletions);
	}

	public SnapshotSession openSession(String serverKey, String serverLabel) {
		return new DefaultSession(serverKey, serverLabel);
	}

	/** Convenience for callers with the whole set already in memory (tests, small jobs). */
	public PersistCounts persistFullSnapshot(String serverKey, String serverLabel, List<FetchedResource> fetched) {
		SnapshotSession session = openSession(serverKey, serverLabel);
		session.accept(fetched);
		return session.finishFullSnapshot();
	}

	/** Convenience for callers with the whole set already in memory (tests, small jobs). */
	public PersistCounts persistIncremental(
			String serverKey, String serverLabel, List<FetchedResource> fetched, List<DeletionEntry> deletions) {
		SnapshotSession session = openSession(serverKey, serverLabel);
		session.accept(fetched);
		return session.finishIncremental(deletions);
	}

	private final class DefaultSession implements SnapshotSession {
		private final String serverKey;
		private final String serverLabel;
		private final long startCount;
		private final boolean firstCrawl;
		private final Set<String> seenKeys = new HashSet<>();
		private final Set<String> updatedKeys = new HashSet<>();
		private final Set<String> recentKeys;
		private int added;
		private int updated;
		private int processed;
		private int lastLoggedAt;

		private DefaultSession(String serverKey, String serverLabel) {
			this.serverKey = serverKey;
			this.serverLabel = serverLabel;
			this.startCount = resourceRepo.countByServerKey(serverKey);
			this.firstCrawl = startCount == 0;
			this.recentKeys = firstCrawl ? boundedKeySet() : null;
			ourLog.info("Persist session for server {}: existing aggregate {} rows", serverLabel, startCount);
		}

		@Override
		public void accept(List<FetchedResource> batch) {
			// Skip the DB lookup for keys already handled this run: the stored row may hold this run's own
			// write, so re-reading it would misclassify the re-fetch.
			Set<String> handled = firstCrawl ? recentKeys : seenKeys;
			List<String> queryKeys = new ArrayList<>();
			for (FetchedResource fr : batch) {
				if (!handled.contains(fr.key())) {
					queryKeys.add(fr.key());
				}
			}
			Map<String, DiffUtil.VersionInfo> prior = queryKeys.isEmpty() ? Map.of() : loadVersions(queryKeys);

			DiffUtil.DiffResult diff = DiffUtil.computeDiff(batch, prior);
			List<FetchedResource> inserts = new ArrayList<>();
			List<FetchedResource> updates = new ArrayList<>(diff.updated());
			int newInserts = 0;
			int newUpdates = 0;
			for (FetchedResource fr : diff.added()) {
				if (handled.add(fr.key())) {
					inserts.add(fr);
					newInserts++;
				} else {
					updates.add(fr);
				}
			}
			for (FetchedResource fr : diff.updated()) {
				if (updatedKeys.add(fr.key())) {
					newUpdates++;
				}
			}
			upsertInChunks(inserts, true);
			upsertInChunks(updates, false);
			added += newInserts;
			updated += newUpdates;
			if (!firstCrawl) {
				// Full-snapshot deletion needs every fetched key; a first crawl has no prior rows to delete.
				for (FetchedResource fr : batch) {
					seenKeys.add(fr.key());
				}
			}
			processed += batch.size();
			if (processed - lastLoggedAt >= LOG_EVERY) {
				lastLoggedAt = processed;
				ourLog.info(
						"Persist progress for server {}: {} processed (+{} ~{})",
						serverLabel,
						processed,
						added,
						updated);
			}
		}

		@Override
		public PersistCounts finishFullSnapshot() {
			// A first crawl has no prior rows, so nothing can be deleted.
			List<String> deletedKeys = startCount > 0 ? scanDeletedKeys() : new ArrayList<>();
			return finish(deletedKeys);
		}

		@Override
		public PersistCounts finishIncremental(List<DeletionEntry> deletions) {
			List<String> deletedKeys = DiffUtil.applyDeletions(deletions, serverKey, existingDeletionKeys(deletions));
			deletedKeys.removeIf(seenKeys::contains);
			return finish(deletedKeys);
		}

		private PersistCounts finish(List<String> deletedKeys) {
			deleteInChunks(deletedKeys);
			return new PersistCounts(added, updated, deletedKeys.size(), (int) startCount + added - deletedKeys.size());
		}

		/** Keys of this server not re-seen this run. */
		private List<String> scanDeletedKeys() {
			List<String> deleted = new ArrayList<>();
			String prefix = serverKey + "|";
			String afterKey = prefix;
			while (true) {
				List<String> keys = resourceRepo.findKeysByKeyGreaterThanOrderByKeyAsc(
						afterKey, PageRequest.ofSize(DELETE_SCAN_PAGE));
				if (keys.isEmpty()) {
					break;
				}
				for (String key : keys) {
					if (!key.startsWith(prefix)) {
						return deleted; // reached the next server's keys
					}
					if (!seenKeys.contains(key)) {
						deleted.add(key);
					}
					afterKey = key;
				}
				if (keys.size() < DELETE_SCAN_PAGE) {
					break;
				}
			}
			return deleted;
		}

		/** The explicit deletion keys that currently exist. */
		private Set<String> existingDeletionKeys(List<DeletionEntry> deletions) {
			if (deletions.isEmpty()) {
				return Set.of();
			}
			List<String> candidates = new ArrayList<>(deletions.size());
			for (DeletionEntry d : deletions) {
				candidates.add(serverKey + "|" + d.resourceType() + "/" + d.id());
			}
			Set<String> present = new HashSet<>();
			for (CrawlResourceRepository.ResourceVersionView view : resourceRepo.findVersionViewByKeys(candidates)) {
				present.add(view.getKey());
			}
			return present;
		}
	}

	/** Rewriting unchanged rows bloats the append-oriented MVStore, so only the changed set is saved. */
	private void upsertInChunks(List<FetchedResource> changed, boolean isNew) {
		for (int i = 0; i < changed.size(); i += CHUNK) {
			List<FetchedResource> chunk = changed.subList(i, Math.min(i + CHUNK, changed.size()));
			resourceRepo.saveAll(toEntities(chunk, isNew));
		}
	}

	private void deleteInChunks(List<String> deletedKeys) {
		for (int i = 0; i < deletedKeys.size(); i += CHUNK) {
			resourceRepo.deleteAllById(deletedKeys.subList(i, Math.min(i + CHUNK, deletedKeys.size())));
		}
	}

	/** A set that retains only the most recent {@link #DEDUP_WINDOW} keys, evicting the oldest. */
	@SuppressWarnings("serial")
	private static Set<String> boundedKeySet() {
		return Collections.newSetFromMap(new LinkedHashMap<>(1024, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
				return size() > DEDUP_WINDOW;
			}
		});
	}

	/** Prior versions for the given keys. */
	private Map<String, DiffUtil.VersionInfo> loadVersions(List<String> keys) {
		Map<String, DiffUtil.VersionInfo> index = new HashMap<>();
		for (CrawlResourceRepository.ResourceVersionView view : resourceRepo.findVersionViewByKeys(keys)) {
			index.put(view.getKey(), new DiffUtil.VersionInfo(view.getVersionId(), view.getLastUpdated()));
		}
		return index;
	}

	private List<CrawlResource> toEntities(List<FetchedResource> fetched, boolean isNew) {
		List<CrawlResource> entities = new ArrayList<>(fetched.size());
		for (FetchedResource fr : fetched) {
			CrawlResource e = new CrawlResource();
			e.setKey(fr.key());
			e.setResourceType(fr.resourceType());
			e.setVersionId(fr.versionId());
			e.setLastUpdated(fr.lastUpdated());
			e.setResourceJson(ResourceJsonCodec.encode(fr.json()));
			e.setNew(isNew);
			entities.add(e);
		}
		return entities;
	}
}

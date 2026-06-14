package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
		private final Map<String, DiffUtil.VersionInfo> existing;
		private final Set<String> seenKeys = new HashSet<>();
		private final Set<String> updatedKeys = new HashSet<>();
		private int added;
		private int updated;
		private int lastLoggedAt;

		private DefaultSession(String serverKey, String serverLabel) {
			this.serverKey = serverKey;
			this.serverLabel = serverLabel;
			this.existing = loadIndex(serverKey);
			ourLog.info("Persist session for server {}: existing aggregate {} rows", serverLabel, existing.size());
		}

		@Override
		public void accept(List<FetchedResource> batch) {
			DiffUtil.DiffResult diff = DiffUtil.computeDiff(batch, existing);
			List<FetchedResource> inserts = new ArrayList<>();
			List<FetchedResource> updates = new ArrayList<>(diff.updated());
			int newInserts = 0;
			int newUpdates = 0;
			for (FetchedResource fr : diff.added()) {
				// A key already inserted earlier in this session must update, not insert again.
				if (seenKeys.add(fr.key())) {
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
			upsertInChunks(serverKey, serverLabel, inserts, true);
			upsertInChunks(serverKey, serverLabel, updates, false);
			added += newInserts;
			updated += newUpdates;
			for (FetchedResource fr : batch) {
				seenKeys.add(fr.key());
			}
			if (seenKeys.size() - lastLoggedAt >= LOG_EVERY) {
				lastLoggedAt = seenKeys.size();
				ourLog.info(
						"Persist progress for server {}: {} processed (+{} ~{})",
						serverLabel,
						seenKeys.size(),
						added,
						updated);
			}
		}

		@Override
		public PersistCounts finishFullSnapshot() {
			List<String> deletedKeys = new ArrayList<>();
			for (String key : existing.keySet()) {
				if (!seenKeys.contains(key)) {
					deletedKeys.add(key);
				}
			}
			return finish(deletedKeys);
		}

		@Override
		public PersistCounts finishIncremental(List<DeletionEntry> deletions) {
			List<String> deletedKeys = DiffUtil.applyDeletions(deletions, serverKey, existing.keySet());
			deletedKeys.removeIf(seenKeys::contains);
			return finish(deletedKeys);
		}

		private PersistCounts finish(List<String> deletedKeys) {
			deleteInChunks(deletedKeys);
			return new PersistCounts(added, updated, deletedKeys.size(), existing.size() + added - deletedKeys.size());
		}
	}

	/** Rewriting unchanged rows bloats the append-oriented MVStore, so only the changed set is saved. */
	private void upsertInChunks(String serverKey, String serverLabel, List<FetchedResource> changed, boolean isNew) {
		for (int i = 0; i < changed.size(); i += CHUNK) {
			List<FetchedResource> chunk = changed.subList(i, Math.min(i + CHUNK, changed.size()));
			resourceRepo.saveAll(toEntities(serverKey, serverLabel, chunk, isNew));
		}
	}

	private void deleteInChunks(List<String> deletedKeys) {
		for (int i = 0; i < deletedKeys.size(); i += CHUNK) {
			resourceRepo.deleteAllById(deletedKeys.subList(i, Math.min(i + CHUNK, deletedKeys.size())));
		}
	}

	private Map<String, DiffUtil.VersionInfo> loadIndex(String serverKey) {
		Map<String, DiffUtil.VersionInfo> index = new HashMap<>();
		for (CrawlResourceRepository.ResourceVersionView view : resourceRepo.findVersionViewByServerKey(serverKey)) {
			index.put(view.getKey(), new DiffUtil.VersionInfo(view.getVersionId(), view.getLastUpdated()));
		}
		return index;
	}

	private List<CrawlResource> toEntities(
			String serverKey, String serverLabel, List<FetchedResource> fetched, boolean isNew) {
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
			e.setResourceJson(ResourceJsonCodec.encode(fr.json()));
			e.setNew(isNew);
			entities.add(e);
		}
		return entities;
	}
}

package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.entity.CrawlResourceId;
import org.hl7.davinci.api.repository.CrawlResourceRepository;
import org.hl7.davinci.common.PlanNetTypes;
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

	/** Rows per page when streaming the aggregate to find full-snapshot deletions. */
	private static final int DELETE_SCAN_PAGE = 1000;

	private final CrawlResourceRepository resourceRepo;
	private final ServerRegistry serverRegistry;

	public CrawlPersistenceService(CrawlResourceRepository resourceRepo, ServerRegistry serverRegistry) {
		this.resourceRepo = resourceRepo;
		this.serverRegistry = serverRegistry;
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
		return new DefaultSession(serverKey, serverLabel, false);
	}

	/**
	 * A session for a full crawl resumed from checkpoints: rows below the resume floors are not
	 * re-fetched, so the stream never covers the whole server. It dedupes with the same bounded
	 * window as a first crawl (an unbounded seen-key set over millions of rows is a memory hazard)
	 * and refuses {@link SnapshotSession#finishFullSnapshot()}, whose absent-key scan would
	 * misclassify everything below the floors as deleted; finish with
	 * {@code finishIncremental(List.of())}, matching a first crawl's no-deletion semantics.
	 */
	public SnapshotSession openResumedFullSession(String serverKey, String serverLabel) {
		return new DefaultSession(serverKey, serverLabel, true);
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
		private final int serverId;
		private final String serverLabel;
		private final long startCount;
		private final boolean resumed;
		private final boolean dedupOnly;
		private final Set<String> seenKeys = new HashSet<>();
		private final Set<String> updatedKeys = new HashSet<>();
		private final Set<String> recentKeys;
		private int added;
		private int updated;
		private int processed;
		private int lastLoggedAt;

		private DefaultSession(String serverKey, String serverLabel, boolean resumed) {
			// The create point for new servers: the first session against a never-seen serverKey
			// inserts its crawl_server row here.
			this.serverId = serverRegistry.idFor(serverKey);
			this.serverLabel = serverLabel;
			this.startCount = resourceRepo.countByIdServerId(serverId);
			this.resumed = resumed;
			// A first crawl has no prior rows to delete and a resumed full crawl must not delete,
			// so neither tracks the full seen-key set; a bounded recent window dedupes re-fetches.
			this.dedupOnly = startCount == 0 || resumed;
			this.recentKeys = dedupOnly ? boundedKeySet() : null;
			ourLog.info("Persist session for server {}: existing aggregate {} rows", serverLabel, startCount);
		}

		// accept may be called concurrently from parallel fetch workers; the finish methods run
		// single-threaded afterwards, once every worker has returned.
		@Override
		public synchronized void accept(List<FetchedResource> batch) {
			// Skip the DB lookup for keys already handled this run: the stored row may hold this run's own
			// write, so re-reading it would misclassify the re-fetch.
			Set<String> handled = dedupOnly ? recentKeys : seenKeys;
			Map<String, List<String>> unseenUidsByType = new HashMap<>();
			for (FetchedResource fr : batch) {
				if (!handled.contains(key(fr))) {
					unseenUidsByType
							.computeIfAbsent(fr.resourceType(), t -> new ArrayList<>())
							.add(fr.id());
				}
			}
			Map<String, DiffUtil.VersionInfo> prior =
					unseenUidsByType.isEmpty() ? Map.of() : loadVersions(unseenUidsByType);

			DiffUtil.DiffResult diff = DiffUtil.computeDiff(batch, prior);
			List<FetchedResource> inserts = new ArrayList<>();
			List<FetchedResource> updates = new ArrayList<>(diff.updated());
			int newInserts = 0;
			int newUpdates = 0;
			for (FetchedResource fr : diff.added()) {
				if (handled.add(key(fr))) {
					inserts.add(fr);
					newInserts++;
				} else {
					updates.add(fr);
				}
			}
			for (FetchedResource fr : diff.updated()) {
				if (updatedKeys.add(key(fr))) {
					newUpdates++;
				}
			}
			upsertInChunks(inserts, true);
			upsertInChunks(updates, false);
			added += newInserts;
			updated += newUpdates;
			if (!dedupOnly) {
				// Full-snapshot deletion needs every fetched key; a first crawl has no prior rows to
				// delete and a resumed full crawl never runs the deletion scan.
				for (FetchedResource fr : batch) {
					seenKeys.add(key(fr));
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
			if (resumed) {
				throw new IllegalStateException(
						"A resumed session cannot detect full-snapshot deletions; finish with finishIncremental");
			}
			// A first crawl has no prior rows, so nothing can be deleted.
			List<CrawlResourceId> deletedIds = startCount > 0 ? scanDeletedIds() : new ArrayList<>();
			return finish(deletedIds);
		}

		@Override
		public PersistCounts finishIncremental(List<DeletionEntry> deletions) {
			Set<String> existingKeys = existingDeletionKeys(deletions);
			List<String> deletedKeys = DiffUtil.applyDeletions(deletions, existingKeys);
			deletedKeys.removeIf(seenKeys::contains);
			List<CrawlResourceId> deletedIds = new ArrayList<>(deletedKeys.size());
			for (String key : deletedKeys) {
				deletedIds.add(toId(key));
			}
			return finish(deletedIds);
		}

		private PersistCounts finish(List<CrawlResourceId> deletedIds) {
			deleteInChunks(deletedIds);
			return new PersistCounts(added, updated, deletedIds.size(), (int) startCount + added - deletedIds.size());
		}

		/** {@code type/id} keys of this server not re-seen this run, across every Plan-Net type. */
		private List<CrawlResourceId> scanDeletedIds() {
			List<CrawlResourceId> deleted = new ArrayList<>();
			for (String type : PlanNetTypes.TYPES) {
				int typeId = PlanNetTypes.idOf(type);
				String afterUid = "";
				while (true) {
					List<String> uids =
							resourceRepo.findUids(serverId, typeId, afterUid, PageRequest.ofSize(DELETE_SCAN_PAGE));
					if (uids.isEmpty()) {
						break;
					}
					for (String uid : uids) {
						if (!seenKeys.contains(type + "/" + uid)) {
							deleted.add(new CrawlResourceId(serverId, typeId, uid));
						}
						afterUid = uid;
					}
					if (uids.size() < DELETE_SCAN_PAGE) {
						break;
					}
				}
			}
			return deleted;
		}

		/** The explicit deletion keys that currently exist, grouped by type for the IN lookup. */
		private Set<String> existingDeletionKeys(List<DeletionEntry> deletions) {
			if (deletions.isEmpty()) {
				return Set.of();
			}
			Map<String, List<String>> uidsByType = new HashMap<>();
			for (DeletionEntry d : deletions) {
				uidsByType
						.computeIfAbsent(d.resourceType(), t -> new ArrayList<>())
						.add(d.id());
			}
			Set<String> present = new HashSet<>();
			for (Map.Entry<String, List<String>> e : uidsByType.entrySet()) {
				int typeId = PlanNetTypes.idOf(e.getKey());
				for (CrawlResourceRepository.ResourceVersionView view :
						resourceRepo.findVersionViews(serverId, typeId, e.getValue())) {
					present.add(e.getKey() + "/" + view.getUid());
				}
			}
			return present;
		}

		private Map<String, DiffUtil.VersionInfo> loadVersions(Map<String, List<String>> uidsByType) {
			Map<String, DiffUtil.VersionInfo> index = new HashMap<>();
			for (Map.Entry<String, List<String>> e : uidsByType.entrySet()) {
				int typeId = PlanNetTypes.idOf(e.getKey());
				for (CrawlResourceRepository.ResourceVersionView view :
						resourceRepo.findVersionViews(serverId, typeId, e.getValue())) {
					index.put(
							e.getKey() + "/" + view.getUid(),
							new DiffUtil.VersionInfo(view.getVersionId(), view.getLastUpdated()));
				}
			}
			return index;
		}

		private void upsertInChunks(List<FetchedResource> changed, boolean isNew) {
			for (int i = 0; i < changed.size(); i += CHUNK) {
				List<FetchedResource> chunk = changed.subList(i, Math.min(i + CHUNK, changed.size()));
				resourceRepo.saveAll(toEntities(chunk, isNew));
			}
		}

		private List<CrawlResource> toEntities(List<FetchedResource> fetched, boolean isNew) {
			List<CrawlResource> entities = new ArrayList<>(fetched.size());
			for (FetchedResource fr : fetched) {
				CrawlResource e = new CrawlResource();
				e.setId(new CrawlResourceId(serverId, PlanNetTypes.idOf(fr.resourceType()), fr.id()));
				e.setVersionId(fr.versionId());
				e.setLastUpdated(fr.lastUpdated());
				e.setResourceJson(ResourceJsonCodec.encode(fr.json()));
				e.setNew(isNew);
				entities.add(e);
			}
			return entities;
		}

		/** {@code type/uid}, parsed back into a full {@link CrawlResourceId} for this session's server. */
		private CrawlResourceId toId(String key) {
			int slash = key.indexOf('/');
			String type = key.substring(0, slash);
			String uid = key.substring(slash + 1);
			return new CrawlResourceId(serverId, PlanNetTypes.idOf(type), uid);
		}
	}

	private static String key(FetchedResource fr) {
		return fr.resourceType() + "/" + fr.id();
	}

	private void deleteInChunks(List<CrawlResourceId> deletedIds) {
		for (int i = 0; i < deletedIds.size(); i += CHUNK) {
			resourceRepo.deleteAllById(deletedIds.subList(i, Math.min(i + CHUNK, deletedIds.size())));
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
}

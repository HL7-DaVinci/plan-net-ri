package org.hl7.davinci.api.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure classification of fetched resources against the current aggregate state. */
public final class DiffUtil {

	private DiffUtil() {}

	/** Version markers of a currently stored resource. */
	public record VersionInfo(String versionId, String lastUpdated) {}

	/** Incoming resources split into added, updated, and unchanged. */
	public record DiffResult(
			List<FetchedResource> added, List<FetchedResource> updated, List<FetchedResource> unchanged) {}

	/**
	 * Classify incoming resources: added when the key is absent; unchanged when the
	 * versionId OR lastUpdated matches the stored marker; updated otherwise.
	 */
	public static DiffResult computeDiff(List<FetchedResource> incoming, Map<String, VersionInfo> existing) {
		List<FetchedResource> added = new ArrayList<>();
		List<FetchedResource> updated = new ArrayList<>();
		List<FetchedResource> unchanged = new ArrayList<>();

		for (FetchedResource resource : incoming) {
			VersionInfo prior = existing.get(resource.key());
			if (prior == null) {
				added.add(resource);
				continue;
			}
			boolean sameVersion = prior.versionId() != null
					&& resource.versionId() != null
					&& prior.versionId().equals(resource.versionId());
			boolean sameLastUpdated = prior.lastUpdated() != null
					&& resource.lastUpdated() != null
					&& prior.lastUpdated().equals(resource.lastUpdated());
			if (sameVersion || sameLastUpdated) {
				unchanged.add(resource);
			} else {
				updated.add(resource);
			}
		}
		return new DiffResult(added, updated, unchanged);
	}

	/** Existing keys absent from the fresh fetch (full-snapshot deletions). */
	public static List<String> computeDeletedKeys(Collection<String> existingKeys, Set<String> fetchedKeys) {
		List<String> deleted = new ArrayList<>();
		for (String key : existingKeys) {
			if (!fetchedKeys.contains(key)) {
				deleted.add(key);
			}
		}
		return deleted;
	}

	/** Keep only deletions that match a stored key, so the count is verifiable. */
	public static List<String> applyDeletions(
			List<DeletionEntry> deletions, String serverKey, Set<String> existingKeys) {
		List<String> keys = new ArrayList<>();
		for (DeletionEntry deletion : deletions) {
			String key = serverKey + "|" + deletion.resourceType() + "/" + deletion.id();
			if (existingKeys.contains(key)) {
				keys.add(key);
			}
		}
		return keys;
	}
}

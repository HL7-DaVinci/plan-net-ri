import { resourceKey } from "./db";
import type { DeletionEntry, StoredResource } from "./types";

export interface ExistingEntry {
  versionId?: string;
  lastUpdated?: string;
}

/** Index of currently stored resources, keyed by primary key. */
export type ExistingIndex = Map<string, ExistingEntry>;

export interface DiffResult {
  added: StoredResource[];
  updated: StoredResource[];
  unchanged: StoredResource[];
}

/**
 * Classify incoming resources against the existing store. A resource is:
 *  - added: key not present in the store
 *  - updated: key present but versionId (or lastUpdated) differs
 *  - unchanged: key present and version/lastUpdated match
 */
export function computeDiff(
  incoming: StoredResource[],
  existing: ExistingIndex,
): DiffResult {
  const added: StoredResource[] = [];
  const updated: StoredResource[] = [];
  const unchanged: StoredResource[] = [];

  for (const resource of incoming) {
    const prior = existing.get(resource.key);
    if (!prior) {
      added.push(resource);
      continue;
    }
    const sameVersion =
      prior.versionId !== undefined &&
      resource.versionId !== undefined &&
      prior.versionId === resource.versionId;
    const sameLastUpdated =
      prior.lastUpdated !== undefined &&
      resource.lastUpdated !== undefined &&
      prior.lastUpdated === resource.lastUpdated;

    if (sameVersion || sameLastUpdated) {
      unchanged.push(resource);
    } else {
      updated.push(resource);
    }
  }

  return { added, updated, unchanged };
}

/**
 * Map deletion entries to store keys that actually exist, so the deletion count
 * is verifiable (a delete for a resource we never had is a no-op).
 */
export function applyDeletions(
  deletions: DeletionEntry[],
  serverKey: string,
  existing: ExistingIndex,
): string[] {
  const keys: string[] = [];
  for (const deletion of deletions) {
    const key = resourceKey(serverKey, deletion.resourceType, deletion.id);
    if (existing.has(key)) {
      keys.push(key);
    }
  }
  return keys;
}

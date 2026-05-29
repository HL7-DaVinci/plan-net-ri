import type { CrawlMeta, CrawlRun, StoredResource } from "./types";

const DB_NAME = "plan-net-crawler";
const DB_VERSION = 1;
const STORE_RESOURCES = "resources";
const STORE_META = "meta";
const STORE_RUNS = "crawlRuns";

/** Normalize a server URL into a stable key (no trailing slash). */
export function normalizeServerKey(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

/** Build the primary key for a stored resource. */
export function resourceKey(
  serverKey: string,
  resourceType: string,
  id: string,
): string {
  return `${serverKey}|${resourceType}/${id}`;
}

/** Parse a stored resource key back into its parts. */
export function parseResourceKey(
  key: string,
): { serverKey: string; resourceType: string; id: string } | null {
  const sep = key.indexOf("|");
  if (sep === -1) return null;
  const serverKey = key.slice(0, sep);
  const rest = key.slice(sep + 1);
  const slash = rest.indexOf("/");
  if (slash === -1) return null;
  return {
    serverKey,
    resourceType: rest.slice(0, slash),
    id: rest.slice(slash + 1),
  };
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_RESOURCES)) {
        const store = db.createObjectStore(STORE_RESOURCES, { keyPath: "key" });
        store.createIndex("byServer", "serverKey", { unique: false });
        store.createIndex("byServerType", ["serverKey", "resourceType"], {
          unique: false,
        });
      }
      if (!db.objectStoreNames.contains(STORE_META)) {
        db.createObjectStore(STORE_META, { keyPath: "serverKey" });
      }
      if (!db.objectStoreNames.contains(STORE_RUNS)) {
        const runs = db.createObjectStore(STORE_RUNS, { keyPath: "id" });
        runs.createIndex("byServer", "serverKey", { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return dbPromise;
}

function promisifyRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export async function putResources(resources: StoredResource[]): Promise<void> {
  if (resources.length === 0) return;
  const db = await openDb();
  const tx = db.transaction(STORE_RESOURCES, "readwrite");
  const store = tx.objectStore(STORE_RESOURCES);
  for (const resource of resources) {
    store.put(resource);
  }
  await txDone(tx);
}

export async function deleteResources(keys: string[]): Promise<void> {
  if (keys.length === 0) return;
  const db = await openDb();
  const tx = db.transaction(STORE_RESOURCES, "readwrite");
  const store = tx.objectStore(STORE_RESOURCES);
  for (const key of keys) {
    store.delete(key);
  }
  await txDone(tx);
}

/** Apply upserts and deletions for one server in a single transaction. */
export async function applyChanges(
  upserts: StoredResource[],
  deleteKeys: string[],
): Promise<void> {
  if (upserts.length === 0 && deleteKeys.length === 0) return;
  const db = await openDb();
  const tx = db.transaction(STORE_RESOURCES, "readwrite");
  const store = tx.objectStore(STORE_RESOURCES);
  for (const resource of upserts) {
    store.put(resource);
  }
  for (const key of deleteKeys) {
    store.delete(key);
  }
  await txDone(tx);
}

export async function getResourcesByServers(
  serverKeys: string[],
  resourceType?: string,
): Promise<StoredResource[]> {
  if (serverKeys.length === 0) return [];
  const db = await openDb();
  const tx = db.transaction(STORE_RESOURCES, "readonly");
  const store = tx.objectStore(STORE_RESOURCES);
  const results: StoredResource[] = [];
  for (const serverKey of serverKeys) {
    if (resourceType) {
      const index = store.index("byServerType");
      const range = IDBKeyRange.only([serverKey, resourceType]);
      const items = await promisifyRequest(index.getAll(range));
      results.push(...items);
    } else {
      const index = store.index("byServer");
      const items = await promisifyRequest(
        index.getAll(IDBKeyRange.only(serverKey)),
      );
      results.push(...items);
    }
  }
  return results;
}

export interface AggregateCounts {
  total: number;
  perType: Record<string, number>;
  perServer: Record<string, number>;
}

/**
 * Count resources per type and per server without loading full objects, by
 * walking the primary keys of the byServer index.
 */
export async function countByServers(
  serverKeys: string[],
): Promise<AggregateCounts> {
  const counts: AggregateCounts = { total: 0, perType: {}, perServer: {} };
  if (serverKeys.length === 0) return counts;
  const db = await openDb();
  const tx = db.transaction(STORE_RESOURCES, "readonly");
  const index = tx.objectStore(STORE_RESOURCES).index("byServer");
  for (const serverKey of serverKeys) {
    await new Promise<void>((resolve, reject) => {
      const cursorReq = index.openKeyCursor(IDBKeyRange.only(serverKey));
      cursorReq.onsuccess = () => {
        const cursor = cursorReq.result;
        if (!cursor) {
          resolve();
          return;
        }
        const parsed = parseResourceKey(String(cursor.primaryKey));
        if (parsed) {
          counts.total += 1;
          counts.perType[parsed.resourceType] =
            (counts.perType[parsed.resourceType] ?? 0) + 1;
          counts.perServer[serverKey] = (counts.perServer[serverKey] ?? 0) + 1;
        }
        cursor.continue();
      };
      cursorReq.onerror = () => reject(cursorReq.error);
    });
  }
  return counts;
}

export async function getMeta(
  serverKey: string,
): Promise<CrawlMeta | undefined> {
  const db = await openDb();
  const tx = db.transaction(STORE_META, "readonly");
  return promisifyRequest(tx.objectStore(STORE_META).get(serverKey));
}

export async function setMeta(meta: CrawlMeta): Promise<void> {
  const db = await openDb();
  const tx = db.transaction(STORE_META, "readwrite");
  tx.objectStore(STORE_META).put(meta);
  await txDone(tx);
}

export async function appendCrawlRun(run: CrawlRun): Promise<void> {
  const db = await openDb();
  const tx = db.transaction(STORE_RUNS, "readwrite");
  tx.objectStore(STORE_RUNS).put(run);
  await txDone(tx);
}

export async function getCrawlRuns(serverKeys?: string[]): Promise<CrawlRun[]> {
  const db = await openDb();
  const tx = db.transaction(STORE_RUNS, "readonly");
  const store = tx.objectStore(STORE_RUNS);
  let runs: CrawlRun[];
  if (serverKeys && serverKeys.length > 0) {
    const index = store.index("byServer");
    runs = [];
    for (const serverKey of serverKeys) {
      const items = await promisifyRequest(
        index.getAll(IDBKeyRange.only(serverKey)),
      );
      runs.push(...items);
    }
  } else {
    runs = await promisifyRequest(store.getAll());
  }
  return runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
}

/** Remove only the stored resources for a server (keeps meta and run history). */
export async function clearServerResources(serverKey: string): Promise<void> {
  const db = await openDb();
  const tx = db.transaction(STORE_RESOURCES, "readwrite");
  const index = tx.objectStore(STORE_RESOURCES).index("byServer");
  const cursorReq = index.openKeyCursor(IDBKeyRange.only(serverKey));
  cursorReq.onsuccess = () => {
    const cursor = cursorReq.result;
    if (cursor) {
      tx.objectStore(STORE_RESOURCES).delete(cursor.primaryKey);
      cursor.continue();
    }
  };
  await txDone(tx);
}

/** Remove all resources, metadata, and runs for a single server. */
export async function clearServer(serverKey: string): Promise<void> {
  const db = await openDb();
  const tx = db.transaction(
    [STORE_RESOURCES, STORE_META, STORE_RUNS],
    "readwrite",
  );
  const resources = tx.objectStore(STORE_RESOURCES).index("byServer");
  const cursorReq = resources.openKeyCursor(IDBKeyRange.only(serverKey));
  cursorReq.onsuccess = () => {
    const cursor = cursorReq.result;
    if (cursor) {
      tx.objectStore(STORE_RESOURCES).delete(cursor.primaryKey);
      cursor.continue();
    }
  };
  tx.objectStore(STORE_META).delete(serverKey);
  const runs = tx.objectStore(STORE_RUNS).index("byServer");
  const runsCursorReq = runs.openKeyCursor(IDBKeyRange.only(serverKey));
  runsCursorReq.onsuccess = () => {
    const cursor = runsCursorReq.result;
    if (cursor) {
      tx.objectStore(STORE_RUNS).delete(cursor.primaryKey);
      cursor.continue();
    }
  };
  await txDone(tx);
}

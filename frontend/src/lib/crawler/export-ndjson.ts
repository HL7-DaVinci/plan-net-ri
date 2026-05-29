import { getResourcesByServers } from "./db";
import type { StoredResource } from "./types";

/**
 * Group stored resources into NDJSON text, one entry per resource type.
 * Each line is a single FHIR resource (Bulk Data NDJSON convention).
 */
export function buildNdjsonByType(
  resources: StoredResource[],
): Map<string, string> {
  const lines = new Map<string, string[]>();
  for (const stored of resources) {
    const arr = lines.get(stored.resourceType) ?? [];
    arr.push(JSON.stringify(stored.resource));
    lines.set(stored.resourceType, arr);
  }
  const files = new Map<string, string>();
  for (const [type, rows] of lines) {
    files.set(type, `${rows.join("\n")}\n`);
  }
  return files;
}

// --- Minimal store-only ZIP writer (no compression, no dependency) ---

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function u16(value: number): number[] {
  return [value & 0xff, (value >>> 8) & 0xff];
}

function u32(value: number): number[] {
  return [
    value & 0xff,
    (value >>> 8) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 24) & 0xff,
  ];
}

/** Build a valid ZIP archive (stored, uncompressed) from text files. */
export function createZip(files: { name: string; content: string }[]): Blob {
  const encoder = new TextEncoder();
  const chunks: Uint8Array[] = [];
  const central: number[] = [];
  let offset = 0;

  for (const file of files) {
    const nameBytes = encoder.encode(file.name);
    const data = encoder.encode(file.content);
    const crc = crc32(data);

    const localHeader = [
      ...u32(0x04034b50), // local file header signature
      ...u16(20), // version needed
      ...u16(0), // flags
      ...u16(0), // method: store
      ...u16(0), // mod time
      ...u16(0), // mod date
      ...u32(crc),
      ...u32(data.length), // compressed size
      ...u32(data.length), // uncompressed size
      ...u16(nameBytes.length),
      ...u16(0), // extra length
    ];
    const headerBytes = new Uint8Array([...localHeader, ...nameBytes]);
    chunks.push(headerBytes, data);

    central.push(
      ...u32(0x02014b50), // central directory header signature
      ...u16(20), // version made by
      ...u16(20), // version needed
      ...u16(0), // flags
      ...u16(0), // method: store
      ...u16(0), // mod time
      ...u16(0), // mod date
      ...u32(crc),
      ...u32(data.length),
      ...u32(data.length),
      ...u16(nameBytes.length),
      ...u16(0), // extra length
      ...u16(0), // comment length
      ...u16(0), // disk number start
      ...u16(0), // internal attributes
      ...u32(0), // external attributes
      ...u32(offset), // local header offset
      ...nameBytes,
    );

    offset += headerBytes.length + data.length;
  }

  const centralBytes = new Uint8Array(central);
  const end = new Uint8Array([
    ...u32(0x06054b50), // end of central directory signature
    ...u16(0), // disk number
    ...u16(0), // disk with central directory
    ...u16(files.length), // entries on this disk
    ...u16(files.length), // total entries
    ...u32(centralBytes.length),
    ...u32(offset),
    ...u16(0), // comment length
  ]);

  chunks.push(centralBytes, end);
  return new Blob(chunks as BlobPart[], { type: "application/zip" });
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export interface ExportResult {
  types: number;
  resources: number;
}

/**
 * Export the aggregated directory (across the given servers) as a ZIP of
 * per-resource-type NDJSON files, then trigger a download.
 */
export async function exportAggregateNdjson(
  serverKeys: string[],
  timestamp: string,
): Promise<ExportResult> {
  const resources = await getResourcesByServers(serverKeys);
  if (resources.length === 0) return { types: 0, resources: 0 };

  const byType = buildNdjsonByType(resources);
  const files = [...byType.entries()].map(([type, content]) => ({
    name: `${type}.ndjson`,
    content,
  }));

  const zip = createZip(files);
  downloadBlob(zip, `plan-net-directory-${timestamp}.zip`);
  return { types: files.length, resources: resources.length };
}

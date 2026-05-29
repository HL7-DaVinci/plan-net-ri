import type { Identifier } from "fhir/r4";
import { NPI_IDENTIFIER_SYSTEM } from "@/lib/plan-net-types";
import type { DuplicateGroup, StoredResource } from "./types";

interface BusinessIdentifier {
  system: string;
  value: string;
}

/**
 * Pick a single business identifier to match on across servers. Prefers NPI
 * (Practitioner/Organization), otherwise the first identifier with both a
 * system and value.
 */
function businessIdentifier(
  resource: StoredResource,
): BusinessIdentifier | undefined {
  const identifiers = (resource.resource as { identifier?: Identifier[] })
    .identifier;
  if (!identifiers?.length) return undefined;

  const npi = identifiers.find(
    (id) => id.system === NPI_IDENTIFIER_SYSTEM && id.value,
  );
  if (npi?.value) return { system: NPI_IDENTIFIER_SYSTEM, value: npi.value };

  const first = identifiers.find((id) => id.system && id.value);
  if (first?.system && first.value) {
    return { system: first.system, value: first.value };
  }
  return undefined;
}

/**
 * Find resources that share a business identifier across two or more distinct
 * servers. This flags overlap between payer directories WITHOUT merging them
 * (Plan-Net is single-payer scoped and defines no cross-server dedup).
 */
export function findDuplicates(resources: StoredResource[]): DuplicateGroup[] {
  const groups = new Map<string, DuplicateGroup>();

  for (const resource of resources) {
    const identifier = businessIdentifier(resource);
    if (!identifier) continue;

    const groupKey = `${resource.resourceType}|${identifier.system}|${identifier.value}`;
    let group = groups.get(groupKey);
    if (!group) {
      group = {
        identifierSystem: identifier.system,
        identifierValue: identifier.value,
        resourceType: resource.resourceType,
        members: [],
      };
      groups.set(groupKey, group);
    }
    group.members.push({
      serverKey: resource.serverKey,
      serverLabel: resource.serverLabel,
      id: resource.id,
      key: resource.key,
    });
  }

  return Array.from(groups.values()).filter((group) => {
    const servers = new Set(group.members.map((member) => member.serverKey));
    return servers.size >= 2;
  });
}

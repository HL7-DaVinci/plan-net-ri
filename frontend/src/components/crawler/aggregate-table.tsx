import type { ColumnDef } from "@tanstack/react-table";
import type { FhirResource } from "fhir/r4";
import { Search } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { DataTable } from "@/components/data-table";
import { JsonViewerDialog } from "@/components/json-viewer-dialog";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { getResourcesByServers } from "@/lib/crawler/db";
import type {
  DuplicateGroup,
  ScopeServer,
  StoredResource,
} from "@/lib/crawler/types";
import { getColumnsForResourceType } from "@/lib/fhir-columns";
import { PLAN_NET_RESOURCE_TYPES } from "@/lib/plan-net-types";

type AggregateRow = FhirResource & {
  __serverLabel: string;
  __serverKey: string;
  __duplicate: boolean;
  __stored: StoredResource;
  /** Lowercased id + resource JSON, for client-side filtering. */
  __search: string;
};

interface AggregateTableProps {
  scope: ScopeServer[];
  duplicateGroups: DuplicateGroup[];
  perType: Record<string, number>;
  /** Bump to force a reload after a crawl. */
  refreshKey: number;
}

export function AggregateTable({
  scope,
  duplicateGroups,
  perType,
  refreshKey,
}: AggregateTableProps) {
  const [activeType, setActiveType] = useState<string>(
    PLAN_NET_RESOURCE_TYPES[0],
  );
  const [resources, setResources] = useState<StoredResource[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [viewing, setViewing] = useState<FhirResource | null>(null);
  const [filter, setFilter] = useState("");

  const duplicateKeys = useMemo(() => {
    const set = new Set<string>();
    for (const group of duplicateGroups) {
      for (const member of group.members) set.add(member.key);
    }
    return set;
  }, [duplicateGroups]);

  const serverKeys = scope.map((s) => s.serverKey).join(",");

  // biome-ignore lint/correctness/useExhaustiveDependencies: refreshKey forces a reload after each crawl
  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    getResourcesByServers(serverKeys ? serverKeys.split(",") : [], activeType)
      .then((items) => {
        if (!cancelled) setResources(items);
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [serverKeys, activeType, refreshKey]);

  const rows = useMemo<AggregateRow[]>(
    () =>
      resources.map((stored) => ({
        ...stored.resource,
        __serverLabel: stored.serverLabel,
        __serverKey: stored.serverKey,
        __duplicate: duplicateKeys.has(stored.key),
        __stored: stored,
        __search:
          `${stored.id} ${JSON.stringify(stored.resource)}`.toLowerCase(),
      })),
    [resources, duplicateKeys],
  );

  const filteredRows = useMemo(() => {
    const query = filter.trim().toLowerCase();
    return query ? rows.filter((row) => row.__search.includes(query)) : rows;
  }, [rows, filter]);

  const columns = useMemo<ColumnDef<FhirResource, unknown>[]>(() => {
    const base = getColumnsForResourceType(activeType, (resource) =>
      setViewing((resource as AggregateRow).__stored.resource),
    );
    const sourceColumn: ColumnDef<FhirResource, unknown> = {
      id: "__source",
      header: "Source",
      size: 160,
      enableSorting: false,
      accessorFn: (row) => (row as AggregateRow).__serverLabel,
      cell: ({ row }) => {
        const r = row.original as AggregateRow;
        return (
          <span className="flex items-center gap-1.5 min-w-0">
            <span className="truncate">{r.__serverLabel}</span>
            {r.__duplicate && (
              <Badge variant="outline" className="text-[10px] px-1 py-0">
                dup
              </Badge>
            )}
          </span>
        );
      },
    };
    return [base[0], sourceColumn, ...base.slice(1)];
  }, [activeType]);

  return (
    <div className="flex flex-col">
      <Tabs
        value={activeType}
        onValueChange={(value) => {
          setActiveType(value);
          setFilter("");
        }}
      >
        <TabsList className="flex-wrap h-auto">
          {PLAN_NET_RESOURCE_TYPES.map((type) => (
            <TabsTrigger key={type} value={type} className="text-xs">
              {type}
              <span className="ml-1.5 text-muted-foreground tabular-nums">
                {(perType[type] ?? 0).toLocaleString()}
              </span>
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      <div className="mt-3 flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder={`Filter ${activeType} by id or any field...`}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="h-9 pl-8"
          />
        </div>
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
          {filter
            ? `${filteredRows.length.toLocaleString()} of ${rows.length.toLocaleString()}`
            : `${rows.length.toLocaleString()} rows`}
        </span>
      </div>

      <div className="mt-3 h-[480px]">
        <DataTable
          columns={columns}
          data={filteredRows as FhirResource[]}
          onRowClick={(row) =>
            setViewing((row as AggregateRow).__stored.resource)
          }
          isLoading={isLoading}
          emptyMessage={
            filter
              ? `No ${activeType} resources match "${filter}"`
              : `No ${activeType} resources in the local directory`
          }
          className="h-full"
        />
      </div>

      <JsonViewerDialog data={viewing} onClose={() => setViewing(null)} />
    </div>
  );
}

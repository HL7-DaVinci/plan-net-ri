import {
  createFileRoute,
  useNavigate,
  useSearch,
} from "@tanstack/react-router";
import type { SortingState } from "@tanstack/react-table";
import type { FhirResource } from "fhir/r4";
import {
  ChevronLeft,
  ChevronRight,
  FileJson,
  Loader2,
  RefreshCw,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import { DataTable } from "@/components/data-table";
import { ResourceFilters } from "@/components/data-table/resource-filters";
import { JsonViewerDialog } from "@/components/json-viewer-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  getPaginationLinks,
  getResourceTypes,
  useCapabilityStatement,
  useResourceSearchWithParams,
} from "@/hooks/use-fhir-api";
import { useFhirServer } from "@/hooks/use-fhir-server";
import { usePlatform } from "@/hooks/use-platform";
import { getColumnsForResourceType } from "@/lib/fhir-columns";
import type { FhirColumnMeta } from "@/lib/fhir-columns/templates";

export const Route = createFileRoute("/resources/")({
  component: ResourceBrowser,
  validateSearch: (search: Record<string, unknown>): { type?: string } => ({
    type: typeof search.type === "string" ? search.type : undefined,
  }),
});

/**
 * Convert TanStack Table sorting state to FHIR _sort parameter.
 * Uses column meta.sortParam to get the FHIR search parameter name.
 * Supports composite sort params (e.g., "family,given" becomes "-family,-given" when descending).
 */
function sortingToFhirSort(
  sorting: SortingState,
  columns: ReturnType<typeof getColumnsForResourceType>,
): string | undefined {
  if (sorting.length === 0) return undefined;

  const sortParts = sorting
    .map((sort) => {
      const column = columns.find((col) => col.id === sort.id);
      const meta = column?.meta as FhirColumnMeta | undefined;
      const sortParam = meta?.sortParam;
      if (!sortParam) return null;

      // Handle composite sort params (e.g., "family,given")
      // When descending, prefix each part with "-"
      if (sort.desc) {
        return sortParam
          .split(",")
          .map((p) => `-${p.trim()}`)
          .join(",");
      }
      return sortParam;
    })
    .filter(Boolean);

  return sortParts.length > 0 ? sortParts.join(",") : undefined;
}

function ResourceBrowser() {
  const resourceTypeSelectId = useId();
  const { modifierKey } = usePlatform();
  const { serverUrl } = useFhirServer();
  const { data: capability, isLoading: isLoadingCapability } =
    useCapabilityStatement(serverUrl);
  const search = useSearch({ from: "/resources/" });
  const navigate = useNavigate();

  const [selectedType, setSelectedType] = useState<string>(search.type || "");
  const [pageUrl, setPageUrl] = useState<string | undefined>(undefined);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [sorting, setSorting] = useState<SortingState>([]);
  const [viewingResource, setViewingResource] = useState<FhirResource | null>(
    null,
  );
  const [pageSize, setPageSize] = useState(50);

  const resourceTypes = getResourceTypes(capability);

  const prevSearchType = useRef(search.type);

  const columns = useMemo(
    () => getColumnsForResourceType(selectedType, setViewingResource),
    [selectedType],
  );

  // Convert sorting state to FHIR _sort parameter
  const fhirSort = useMemo(
    () => sortingToFhirSort(sorting, columns),
    [sorting, columns],
  );

  // Merge filters with sort parameter
  const searchParams = useMemo(() => {
    const params = { ...filters };
    if (fhirSort) {
      params._sort = fhirSort;
    }
    return params;
  }, [filters, fhirSort]);

  useEffect(() => {
    if (search.type !== prevSearchType.current) {
      prevSearchType.current = search.type;
      setSelectedType(search.type || "");
      setPageUrl(undefined);
      setFilters({});
      setSorting([]);
    }
  }, [search.type]);

  const {
    data: bundle,
    isLoading: isLoadingResources,
    isFetching,
    error,
    refetch,
  } = useResourceSearchWithParams(
    serverUrl,
    selectedType,
    searchParams,
    pageUrl,
    pageSize,
  );

  const paginationLinks = getPaginationLinks(bundle);
  const resources = useMemo(
    () => bundle?.entry?.map((e) => e.resource).filter(Boolean) || [],
    [bundle?.entry],
  );

  const handleTypeChange = useCallback(
    (type: string) => {
      setSelectedType(type);
      setPageUrl(undefined);
      setFilters({});
      setSorting([]);
      // Update URL to reflect the new type selection
      prevSearchType.current = type;
      navigate({
        to: "/resources",
        search: type ? { type } : undefined,
        replace: true,
      });
    },
    [navigate],
  );

  const handlePageChange = useCallback((url: string | undefined) => {
    if (url) setPageUrl(url);
  }, []);

  const handleFiltersChange = useCallback(
    (
      newFilters:
        | Record<string, string>
        | ((prev: Record<string, string>) => Record<string, string>),
    ) => {
      setFilters(newFilters);
      setPageUrl(undefined);
    },
    [],
  );

  const handleSortingChange = useCallback(
    (updater: SortingState | ((old: SortingState) => SortingState)) => {
      setSorting(updater);
      setPageUrl(undefined); // Reset to first page when sorting changes
    },
    [],
  );

  const handleCloseViewer = useCallback(() => setViewingResource(null), []);

  return (
    <div className="flex flex-col h-full">
      <div className="shrink-0 border-b bg-background">
        <div className="px-4 py-3 space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center gap-4">
            <div className="flex items-center gap-3">
              <label
                htmlFor={resourceTypeSelectId}
                className="text-sm font-medium whitespace-nowrap"
              >
                Resource Type
              </label>
              <Select
                value={selectedType}
                onValueChange={handleTypeChange}
                disabled={isLoadingCapability}
              >
                <SelectTrigger id={resourceTypeSelectId} className="w-48 h-9">
                  <SelectValue
                    placeholder={
                      isLoadingCapability ? "Loading..." : "Select type"
                    }
                  />
                </SelectTrigger>
                <SelectContent>
                  {resourceTypes.map((type) => (
                    <SelectItem key={type} value={type}>
                      {type}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {selectedType && (
              <div className="flex items-center gap-2">
                {bundle?.total !== undefined && (
                  <Badge variant="secondary" className="h-6">
                    {bundle.total.toLocaleString()} total
                  </Badge>
                )}
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2"
                  onClick={() => refetch()}
                  disabled={isFetching}
                  title="Refresh data"
                >
                  <RefreshCw
                    className={`h-3.5 w-3.5 ${isFetching ? "animate-spin" : ""}`}
                  />
                </Button>
              </div>
            )}
          </div>

          {/* Filters */}
          {selectedType && (
            <ResourceFilters
              resourceType={selectedType}
              filters={filters}
              onFiltersChange={handleFiltersChange}
            />
          )}
        </div>
      </div>

      <div className="flex-1 overflow-hidden">
        {error && (
          <div className="flex items-start gap-2 p-3 rounded-md bg-destructive/10 text-destructive text-sm mb-4">
            <span>
              {error instanceof Error
                ? error.message
                : "Failed to fetch resources"}
            </span>
          </div>
        )}

        {!selectedType && !isLoadingCapability && (
          <div className="flex flex-col items-center justify-center h-full text-center px-4">
            <div className="absolute inset-0 overflow-hidden pointer-events-none">
              <div className="absolute top-1/3 left-1/3 w-48 h-48 bg-primary/5 rounded-full blur-3xl" />
              <div className="absolute bottom-1/3 right-1/3 w-64 h-64 bg-primary/3 rounded-full blur-3xl" />
            </div>

            <div className="relative space-y-6 max-w-md">
              <div className="flex justify-center">
                <div className="relative">
                  <div className="absolute inset-0 bg-primary/10 rounded-2xl blur-xl scale-125" />
                  <div className="relative flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-primary/10 to-primary/5 border border-primary/20">
                    <FileJson className="h-10 w-10 text-primary/70" />
                  </div>
                </div>
              </div>

              <div className="space-y-2">
                <h3 className="text-xl font-semibold">
                  Select a Resource Type
                </h3>
                <p className="text-sm text-muted-foreground leading-relaxed">
                  Choose a FHIR resource type from the dropdown above to browse
                  data. You can search, filter, and view detailed JSON
                  representations.
                </p>
              </div>

              <div className="pt-2 space-y-2 text-left">
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
                  Quick tips
                </p>
                <div className="grid gap-2 text-sm">
                  <div className="flex items-start gap-2 p-2 rounded-lg bg-muted/50">
                    <div className="h-5 w-5 rounded bg-primary/10 flex items-center justify-center shrink-0 mt-0.5">
                      <span className="text-xs font-medium text-primary">
                        1
                      </span>
                    </div>
                    <span className="text-muted-foreground">
                      Use <kbd className="kbd text-xs mx-1">{modifierKey}K</kbd>{" "}
                      to quickly navigate to any resource type
                    </span>
                  </div>
                  <div className="flex items-start gap-2 p-2 rounded-lg bg-muted/50">
                    <div className="h-5 w-5 rounded bg-primary/10 flex items-center justify-center shrink-0 mt-0.5">
                      <span className="text-xs font-medium text-primary">
                        2
                      </span>
                    </div>
                    <span className="text-muted-foreground">
                      Click any row to view the complete JSON resource
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {isLoadingCapability && (
          <div className="flex flex-col items-center justify-center h-full text-center px-4">
            <div className="space-y-6">
              <div className="relative flex items-center justify-center">
                <div className="absolute h-16 w-16 rounded-full border-2 border-primary/20" />
                <div className="absolute h-16 w-16 rounded-full border-2 border-transparent border-t-primary animate-spin" />
                <Loader2 className="h-6 w-6 animate-spin text-primary" />
              </div>

              <div className="space-y-1">
                <p className="font-medium">Loading server capabilities</p>
                <p className="text-sm text-muted-foreground">
                  Fetching available resource types...
                </p>
              </div>
            </div>
          </div>
        )}

        {selectedType && !isLoadingCapability && (
          <DataTable
            columns={columns}
            data={resources as FhirResource[]}
            onRowClick={setViewingResource}
            isLoading={isLoadingResources}
            emptyMessage={`No ${selectedType} resources found`}
            className="h-full"
            manualSorting
            sorting={sorting}
            onSortingChange={handleSortingChange}
          />
        )}
      </div>

      {selectedType && (
        <div className="shrink-0 border-t bg-background">
          <div className="flex items-center justify-between px-4 py-3">
            <div className="flex items-center gap-4">
              <span className="text-sm text-muted-foreground">
                {bundle?.total !== undefined && (
                  <>
                    Showing {resources.length} of{" "}
                    {bundle.total.toLocaleString()}
                  </>
                )}
              </span>
              <div className="flex items-center gap-2">
                <label
                  htmlFor="page-size-select"
                  className="text-sm text-muted-foreground"
                >
                  Per page:
                </label>
                <Select
                  value={pageSize.toString()}
                  onValueChange={(v) => {
                    setPageSize(Number(v));
                    setPageUrl(undefined);
                  }}
                >
                  <SelectTrigger id="page-size-select" className="w-20 h-8">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="20">20</SelectItem>
                    <SelectItem value="50">50</SelectItem>
                    <SelectItem value="100">100</SelectItem>
                    <SelectItem value="200">200</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => handlePageChange(paginationLinks.previous)}
                disabled={!paginationLinks.previous || isLoadingResources}
              >
                <ChevronLeft className="h-4 w-4 mr-1" />
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => handlePageChange(paginationLinks.next)}
                disabled={!paginationLinks.next || isLoadingResources}
              >
                Next
                <ChevronRight className="h-4 w-4 ml-1" />
              </Button>
            </div>
          </div>
        </div>
      )}

      <JsonViewerDialog data={viewingResource} onClose={handleCloseViewer} />
    </div>
  );
}

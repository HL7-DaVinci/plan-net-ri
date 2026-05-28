import { Filter, Search, X } from "lucide-react";
import { memo, useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";

type FiltersUpdater =
  | Record<string, string>
  | ((prev: Record<string, string>) => Record<string, string>);

interface ResourceFiltersProps {
  resourceType: string;
  filters: Record<string, string>;
  onFiltersChange: (filters: FiltersUpdater) => void;
  className?: string;
}

function parseSearchString(searchString: string): Record<string, string> {
  const params: Record<string, string> = {};
  if (!searchString.trim()) return params;

  // Split by & but be careful with values that might contain special chars
  const pairs = searchString.split("&");
  for (const pair of pairs) {
    const eqIndex = pair.indexOf("=");
    if (eqIndex > 0) {
      const key = pair.substring(0, eqIndex).trim();
      const value = pair.substring(eqIndex + 1).trim();
      if (key && value) {
        params[key] = value;
      }
    }
  }
  return params;
}

function filtersToSearchString(filters: Record<string, string>): string {
  return Object.entries(filters)
    .filter(([key]) => key !== "_id")
    .map(([key, value]) => `${key}=${value}`)
    .join("&");
}

export const ResourceFilters = memo(function ResourceFilters({
  resourceType,
  filters,
  onFiltersChange,
  className,
}: ResourceFiltersProps) {
  const [idSearch, setIdSearch] = useState(filters._id || "");
  const [searchString, setSearchString] = useState(() =>
    filtersToSearchString(filters),
  );

  const appliedId = filters._id || "";
  const appliedSearchString = filtersToSearchString(filters);
  const hasUnappliedChanges =
    idSearch !== appliedId || searchString !== appliedSearchString;
  const hasActiveFilters = appliedId || appliedSearchString;

  useEffect(() => {
    setIdSearch(filters._id || "");
    setSearchString(filtersToSearchString(filters));
  }, [filters]);

  // biome-ignore lint/correctness/useExhaustiveDependencies: intentionally reset on resource type change
  useEffect(() => {
    setIdSearch("");
    setSearchString("");
    onFiltersChange({});
  }, [resourceType]);

  const applyFilters = useCallback(() => {
    const parsed = parseSearchString(searchString);
    const newFilters: Record<string, string> = {};

    if (idSearch.trim()) {
      newFilters._id = idSearch.trim();
    }

    // Merge in the parsed search string params
    Object.assign(newFilters, parsed);

    onFiltersChange(newFilters);
  }, [idSearch, searchString, onFiltersChange]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter") {
        applyFilters();
      }
    },
    [applyFilters],
  );

  const handleClearId = useCallback(() => {
    setIdSearch("");
    onFiltersChange((prev) => {
      const newFilters = { ...prev };
      delete newFilters._id;
      return newFilters;
    });
  }, [onFiltersChange]);

  const handleClearSearch = useCallback(() => {
    setSearchString("");
    onFiltersChange((prev) => {
      const newFilters: Record<string, string> = {};
      if (prev._id) {
        newFilters._id = prev._id;
      }
      return newFilters;
    });
  }, [onFiltersChange]);

  const handleClearAll = useCallback(() => {
    setIdSearch("");
    setSearchString("");
    onFiltersChange({});
  }, [onFiltersChange]);

  return (
    <div className={className}>
      <div className="flex items-center gap-3">
        <div className="relative w-48">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="ID (exact)"
            value={idSearch}
            onChange={(e) => setIdSearch(e.target.value)}
            onKeyDown={handleKeyDown}
            className="pl-9 h-9 font-mono text-sm"
          />
          {idSearch && (
            <Button
              variant="ghost"
              size="sm"
              className="absolute right-1 top-1/2 -translate-y-1/2 h-6 w-6 p-0"
              onClick={handleClearId}
              aria-label="Clear ID search"
            >
              <X className="h-3 w-3" />
            </Button>
          )}
        </div>

        <div className="relative flex-1 max-w-lg">
          <Tooltip>
            <TooltipTrigger asChild>
              <Input
                placeholder="FHIR search params (e.g., name=John&gender=female)"
                value={searchString}
                onChange={(e) => setSearchString(e.target.value)}
                onKeyDown={handleKeyDown}
                className="h-9 font-mono text-sm pr-8"
              />
            </TooltipTrigger>
            <TooltipContent side="bottom" className="max-w-sm">
              <p className="font-medium mb-1">FHIR Search Syntax</p>
              <p className="text-xs mb-2">
                Enter parameters separated by &amp; then press Enter or click
                Apply
              </p>
              <ul className="text-xs space-y-0.5">
                <li>Examples:</li>
                <li>
                  <code className="bg-foreground/20 px-1 rounded">
                    name=Smith
                  </code>{" "}
                  - text search
                </li>
                <li>
                  <code className="bg-foreground/20 px-1 rounded">
                    birthdate=gt2000-01-01
                  </code>{" "}
                  - date comparison
                </li>
                <li>
                  <code className="bg-foreground/20 px-1 rounded">
                    gender=female
                  </code>{" "}
                  - token match
                </li>
                <li>
                  <code className="bg-foreground/20 px-1 rounded">
                    identifier=http://example.com|12345
                  </code>{" "}
                  - system|code
                </li>
              </ul>
            </TooltipContent>
          </Tooltip>
          {searchString && (
            <Button
              variant="ghost"
              size="sm"
              className="absolute right-1 top-1/2 -translate-y-1/2 h-6 w-6 p-0"
              onClick={handleClearSearch}
              aria-label="Clear search string"
            >
              <X className="h-3 w-3" />
            </Button>
          )}
        </div>

        {hasUnappliedChanges && (
          <Button size="sm" className="h-9" onClick={applyFilters}>
            <Filter className="h-4 w-4 mr-1.5" />
            Apply
          </Button>
        )}

        {hasActiveFilters && !hasUnappliedChanges && (
          <Button
            variant="outline"
            size="sm"
            className="h-9 text-muted-foreground"
            onClick={handleClearAll}
          >
            <X className="h-4 w-4 mr-1.5" />
            Clear
          </Button>
        )}
      </div>

      {hasActiveFilters && (
        <div className="mt-2 text-xs text-muted-foreground">
          <span className="font-medium">Active filters:</span>{" "}
          <code className="bg-muted px-1.5 py-0.5 rounded">
            {[
              appliedId ? `_id=${appliedId}` : null,
              appliedSearchString || null,
            ]
              .filter(Boolean)
              .join("&")}
          </code>
        </div>
      )}
    </div>
  );
});

import {
  type ColumnDef,
  type ColumnResizeMode,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  type OnChangeFn,
  type SortingState,
  useReactTable,
  type VisibilityState,
} from "@tanstack/react-table";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react";
import { useRef, useState } from "react";
import { cn } from "@/lib/utils";

interface DataTableProps<TData> {
  columns: ColumnDef<TData, unknown>[];
  data: TData[];
  onRowClick?: (row: TData) => void;
  isLoading?: boolean;
  emptyMessage?: string;
  className?: string;
  /** Enable server-side (manual) sorting. When true, sorting state must be controlled externally. */
  manualSorting?: boolean;
  /** Current sorting state (required when manualSorting is true) */
  sorting?: SortingState;
  /** Callback when sorting changes (required when manualSorting is true) */
  onSortingChange?: OnChangeFn<SortingState>;
}

const COLUMN_RESIZE_MODE: ColumnResizeMode = "onChange";
const SKELETON_ROWS = Array.from({ length: 10 }, (_, i) => `skeleton-row-${i}`);

export function DataTable<TData>({
  columns,
  data,
  onRowClick,
  isLoading,
  emptyMessage = "No data available",
  className,
  manualSorting = false,
  sorting: controlledSorting,
  onSortingChange: controlledOnSortingChange,
}: DataTableProps<TData>) {
  // Use internal state only when not using manual sorting
  const [internalSorting, setInternalSorting] = useState<SortingState>([]);
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>({});

  // Use controlled state when manualSorting is enabled
  const sorting = manualSorting ? (controlledSorting ?? []) : internalSorting;
  const onSortingChange = manualSorting
    ? controlledOnSortingChange
    : setInternalSorting;

  const table = useReactTable({
    data,
    columns,
    state: {
      sorting,
      columnVisibility,
    },
    onSortingChange,
    onColumnVisibilityChange: setColumnVisibility,
    getCoreRowModel: getCoreRowModel(),
    // Only use client-side sorting when not in manual mode
    ...(manualSorting
      ? { manualSorting: true }
      : { getSortedRowModel: getSortedRowModel() }),
    columnResizeMode: COLUMN_RESIZE_MODE,
    enableColumnResizing: true,
  });

  const { rows } = table.getRowModel();

  const parentRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 40, // Row height
    overscan: 10,
  });

  const virtualRows = virtualizer.getVirtualItems();
  const totalSize = virtualizer.getTotalSize();

  const paddingTop = virtualRows.length > 0 ? virtualRows[0].start : 0;
  const paddingBottom =
    virtualRows.length > 0
      ? totalSize - virtualRows[virtualRows.length - 1].end
      : 0;

  if (isLoading) {
    return (
      <div className={cn("border-y", className)}>
        <div className="data-table">
          <div className="border-b bg-muted/50">
            <div className="flex h-10">
              {columns.map((col, i) => (
                <div
                  key={`skeleton-header-${String(col.id) || `col-${i}`}`}
                  className="flex-1 px-3 py-2"
                >
                  <div className="h-4 bg-muted rounded animate-pulse" />
                </div>
              ))}
            </div>
          </div>
          {SKELETON_ROWS.map((rowKey) => (
            <div key={rowKey} className="flex h-10 border-b">
              {columns.map((col, colIndex) => (
                <div
                  key={`${rowKey}-${String(col.id) || `col-${colIndex}`}`}
                  className="flex-1 px-3 py-2"
                >
                  <div className="h-4 bg-muted/50 rounded animate-pulse" />
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div className={cn("border-y", className)}>
        <div className="data-table">
          <div className="border-b bg-muted/30">
            <div className="flex">
              {table.getHeaderGroups().map((headerGroup) =>
                headerGroup.headers.map((header) => (
                  <div
                    key={header.id}
                    className="flex-1 min-w-0 px-3 py-2 text-xs font-medium text-muted-foreground uppercase tracking-wider"
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(
                          header.column.columnDef.header,
                          header.getContext(),
                        )}
                  </div>
                )),
              )}
            </div>
          </div>
          <div className="flex items-center justify-center h-48 text-muted-foreground">
            {emptyMessage}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={cn("border-y overflow-hidden flex flex-col", className)}>
      <div className="border-b bg-muted/30 shrink-0">
        <div className="flex w-full">
          {table.getHeaderGroups().map((headerGroup) =>
            headerGroup.headers.map((header) => (
              <div
                key={header.id}
                className="relative group flex-1 min-w-0"
                style={{
                  flex: `${header.getSize()} 1 0%`,
                  minWidth: Math.min(header.getSize(), 100),
                }}
              >
                <button
                  type="button"
                  className={cn(
                    "h-10 px-3 flex items-center gap-1 text-xs font-medium text-muted-foreground uppercase tracking-wider w-full text-left bg-transparent border-0",
                    header.column.getCanSort() &&
                      "cursor-pointer select-none hover:text-foreground",
                  )}
                  onClick={header.column.getToggleSortingHandler()}
                  disabled={!header.column.getCanSort()}
                >
                  <span className="truncate">
                    {header.isPlaceholder
                      ? null
                      : flexRender(
                          header.column.columnDef.header,
                          header.getContext(),
                        )}
                  </span>
                  {header.column.getCanSort() && (
                    <span className="shrink-0 ml-1">
                      {header.column.getIsSorted() === "asc" ? (
                        <ArrowUp className="h-3 w-3" />
                      ) : header.column.getIsSorted() === "desc" ? (
                        <ArrowDown className="h-3 w-3" />
                      ) : (
                        <ArrowUpDown className="h-3 w-3 opacity-0 group-hover:opacity-50" />
                      )}
                    </span>
                  )}
                </button>
                {header.column.getCanResize() && (
                  // biome-ignore lint/a11y/noStaticElementInteractions: resize handle is intentionally mouse/touch only
                  <div
                    onMouseDown={header.getResizeHandler()}
                    onTouchStart={header.getResizeHandler()}
                    className={cn(
                      "absolute right-0 top-0 h-full w-2 cursor-col-resize select-none touch-none flex items-center justify-center",
                      "opacity-0 group-hover:opacity-100 hover:bg-primary/20",
                      header.column.getIsResizing() &&
                        "bg-primary/20 opacity-100",
                    )}
                  >
                    <div className="w-px h-4 bg-border" />
                  </div>
                )}
              </div>
            )),
          )}
        </div>
      </div>

      <div ref={parentRef} className="flex-1 overflow-auto">
        <div
          style={{
            height: `${totalSize}px`,
            width: "100%",
            position: "relative",
          }}
        >
          {paddingTop > 0 && <div style={{ height: `${paddingTop}px` }} />}
          {virtualRows.map((virtualRow) => {
            const row = rows[virtualRow.index];
            const isEven = virtualRow.index % 2 === 0;
            return (
              // biome-ignore lint/a11y/noStaticElementInteractions: row is interactive only when onRowClick is provided (role="button")
              <div
                key={row.id}
                data-index={virtualRow.index}
                ref={virtualizer.measureElement}
                role={onRowClick ? "button" : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                className={cn(
                  "flex border-b border-border/50 transition-colors w-full text-left",
                  isEven ? "bg-transparent" : "bg-muted/30",
                  onRowClick && "cursor-pointer hover:bg-primary/5",
                )}
                onClick={
                  onRowClick ? () => onRowClick(row.original) : undefined
                }
                onKeyDown={
                  onRowClick
                    ? (e) => {
                        if (e.key === "Enter" || e.key === " ") {
                          e.preventDefault();
                          onRowClick(row.original);
                        }
                      }
                    : undefined
                }
              >
                {row.getVisibleCells().map((cell) => (
                  <div
                    key={cell.id}
                    className="h-10 px-3 flex items-center text-sm min-w-0 flex-1"
                    style={{
                      flex: `${cell.column.getSize()} 1 0%`,
                      minWidth: Math.min(cell.column.getSize(), 100),
                    }}
                  >
                    <span className="truncate w-full">
                      {flexRender(
                        cell.column.columnDef.cell,
                        cell.getContext(),
                      )}
                    </span>
                  </div>
                ))}
              </div>
            );
          })}
          {paddingBottom > 0 && (
            <div style={{ height: `${paddingBottom}px` }} />
          )}
        </div>
      </div>
    </div>
  );
}

export type { SortingState };

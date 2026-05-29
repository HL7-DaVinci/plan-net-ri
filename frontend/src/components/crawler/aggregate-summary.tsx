import { Server } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { AggregateCounts } from "@/lib/crawler/db";
import type { ScopeServer } from "@/lib/crawler/types";
import { PLAN_NET_RESOURCE_TYPES } from "@/lib/plan-net-types";
import { getResourceIcon } from "@/lib/resource-icons";

interface AggregateSummaryProps {
  aggregate: AggregateCounts;
  scope: ScopeServer[];
}

export function AggregateSummary({ aggregate, scope }: AggregateSummaryProps) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">Resource breakdown</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
          {PLAN_NET_RESOURCE_TYPES.map((type) => {
            const Icon = getResourceIcon(type);
            const count = aggregate.perType[type] ?? 0;
            return (
              <div
                key={type}
                className="flex items-center gap-2.5 p-2.5 rounded-lg border bg-card text-sm"
              >
                <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-muted/50">
                  <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                </div>
                <span className="min-w-0 truncate font-medium">{type}</span>
                <span className="ml-auto tabular-nums text-xs text-muted-foreground">
                  {count.toLocaleString()}
                </span>
              </div>
            );
          })}
        </div>

        {scope.length >= 2 && (
          <div className="flex flex-wrap gap-2">
            {scope.map((srv) => (
              <div
                key={srv.serverKey}
                className="flex items-center gap-2 rounded-full border bg-card px-3 py-1 text-xs"
              >
                <Server className="h-3 w-3 text-muted-foreground" />
                <span className="font-medium">{srv.serverLabel}</span>
                <span className="tabular-nums text-muted-foreground">
                  {(aggregate.perServer[srv.serverKey] ?? 0).toLocaleString()}
                </span>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

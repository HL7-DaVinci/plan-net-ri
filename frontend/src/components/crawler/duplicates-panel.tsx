import { ChevronDown, ChevronRight, Copy } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import type { DuplicateGroup } from "@/lib/crawler/types";
import { NPI_IDENTIFIER_SYSTEM } from "@/lib/plan-net-types";

interface DuplicatesPanelProps {
  duplicateGroups: DuplicateGroup[];
  scopeCount: number;
}

function systemLabel(system: string): string {
  return system === NPI_IDENTIFIER_SYSTEM ? "NPI" : system;
}

export function DuplicatesPanel({
  duplicateGroups,
  scopeCount,
}: DuplicatesPanelProps) {
  const [open, setOpen] = useState(false);

  if (scopeCount < 2) return null;

  return (
    <Card>
      <Collapsible open={open} onOpenChange={setOpen}>
        <CardHeader className="pb-3">
          <CollapsibleTrigger className="flex w-full cursor-pointer items-center gap-2 text-left">
            {open ? (
              <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
            ) : (
              <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
            )}
            <Copy className="h-4 w-4 shrink-0" />
            <CardTitle className="text-base">Cross-server overlap</CardTitle>
            <Badge variant="secondary" className="ml-1">
              {duplicateGroups.length} flagged
            </Badge>
          </CollapsibleTrigger>
        </CardHeader>
        <CollapsibleContent>
          <CardContent>
            <CardDescription className="mb-3">
              Records sharing a business identifier across servers. Flagged for
              visibility only. Plan-Net is single-payer scoped and defines no
              cross-directory merge, so entries are kept separate.
            </CardDescription>
            {duplicateGroups.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No shared identifiers found across the selected servers.
              </p>
            ) : (
              <div className="space-y-1">
                <div className="flex items-center gap-3 text-xs font-medium text-muted-foreground uppercase tracking-wider pb-1 border-b">
                  <span className="w-40 shrink-0">Identifier</span>
                  <span className="w-32 shrink-0">Type</span>
                  <span className="flex-1">Sources</span>
                </div>
                {duplicateGroups.slice(0, 50).map((group) => (
                  <div
                    key={`${group.resourceType}|${group.identifierSystem}|${group.identifierValue}`}
                    className="flex items-center gap-3 text-sm py-1.5"
                  >
                    <span className="w-40 shrink-0 truncate font-mono text-xs">
                      <span className="text-muted-foreground">
                        {systemLabel(group.identifierSystem)}:
                      </span>{" "}
                      {group.identifierValue}
                    </span>
                    <span className="w-32 shrink-0 truncate">
                      {group.resourceType}
                    </span>
                    <span className="flex-1 flex flex-wrap gap-1">
                      {group.members.map((member) => (
                        <Badge
                          key={member.key}
                          variant="outline"
                          className="text-xs font-normal"
                        >
                          {member.serverLabel}/{member.id}
                        </Badge>
                      ))}
                    </span>
                  </div>
                ))}
                {duplicateGroups.length > 50 && (
                  <p className="text-xs text-muted-foreground pt-2">
                    Showing 50 of {duplicateGroups.length} overlap groups.
                  </p>
                )}
              </div>
            )}
          </CardContent>
        </CollapsibleContent>
      </Collapsible>
    </Card>
  );
}

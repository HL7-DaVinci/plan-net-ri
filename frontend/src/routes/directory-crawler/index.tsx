import { createFileRoute } from "@tanstack/react-router";
import {
  Download,
  Folders,
  Loader2,
  Radar,
  SlidersHorizontal,
} from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { AggregateStats } from "@/components/crawler/aggregate-stats";
import { AggregateSummary } from "@/components/crawler/aggregate-summary";
import { AggregateTable } from "@/components/crawler/aggregate-table";
import { CrawlControlPanel } from "@/components/crawler/crawl-control-panel";
import { CrawlDiffPanel } from "@/components/crawler/crawl-diff-panel";
import { CrawlSummaryRow } from "@/components/crawler/crawl-summary-row";
import { DemoWalkthrough } from "@/components/crawler/demo-walkthrough";
import { DuplicatesPanel } from "@/components/crawler/duplicates-panel";
import { HeadlineMetrics } from "@/components/crawler/headline-metrics";
import { MutationPanel } from "@/components/crawler/mutation-panel";
import { RequestLog } from "@/components/crawler/request-log";
import { ServerScopeSelector } from "@/components/crawler/server-scope-selector";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useCrawler } from "@/hooks/use-crawler";
import { useDemoWalkthrough } from "@/hooks/use-demo-walkthrough";
import { exportAggregateNdjson } from "@/lib/crawler/export-ndjson";

export const Route = createFileRoute("/directory-crawler/")({
  component: DirectoryCrawler,
});

function DirectoryCrawler() {
  const crawler = useCrawler();
  const demo = useDemoWalkthrough(crawler);

  const refreshKey = crawler.runs.length * 1_000_000 + crawler.aggregate.total;
  const [exporting, setExporting] = useState(false);

  const handleExport = async () => {
    setExporting(true);
    try {
      const stamp = new Date().toISOString().replace(/[:.]/g, "-");
      const result = await exportAggregateNdjson(
        crawler.scope.map((s) => s.serverKey),
        stamp,
      );
      if (result.resources === 0) {
        toast.info("Nothing to export. Run a crawl first.");
      } else {
        toast.success(
          `Exported ${result.resources.toLocaleString()} resources to ${result.types} NDJSON file(s).`,
        );
      }
    } catch (error) {
      toast.error("Export failed", {
        description: error instanceof Error ? error.message : String(error),
      });
    } finally {
      setExporting(false);
    }
  };

  return (
    // On xl, fill the viewport (minus the app header) so the left column scrolls
    // internally and the request log stays anchored full-height beside it.
    // On smaller screens, fall back to normal page flow.
    <div className="p-6 xl:h-[calc(100vh-3rem)] xl:overflow-hidden">
      <div className="mx-auto flex flex-col gap-6 xl:h-full">
        <div className="space-y-1 xl:shrink-0">
          <h1 className="text-xl font-semibold flex items-center gap-2">
            <Radar className="h-5 w-5 text-primary" />
            Local Directory Crawler
          </h1>
          <p className="text-sm text-muted-foreground">
            Crawl one or more servers locally in the browser. Aggregated results
            are stored in the browser, not published on the server.
          </p>
        </div>

        <div className="grid grid-cols-1 gap-6 xl:min-h-0 xl:flex-1 xl:grid-cols-[minmax(0,1fr)_400px]">
          {/* Main column: scrolls internally on xl */}
          <div className="min-w-0 space-y-6 xl:overflow-y-auto xl:pr-2">
            <ServerScopeSelector
              scope={crawler.scope}
              setScope={crawler.setScope}
              metas={crawler.metas}
              disabled={crawler.isCrawling}
            />

            <DemoWalkthrough demo={demo} disabled={crawler.isCrawling} />

            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-2">
                  <SlidersHorizontal className="h-4 w-4" />
                  Crawl controls
                </CardTitle>
              </CardHeader>
              <CardContent>
                <CrawlControlPanel
                  isCrawling={crawler.isCrawling}
                  canIncremental={crawler.canIncremental}
                  hasServers={crawler.scope.length > 0}
                  progress={crawler.progress}
                  onFull={crawler.startFullCrawl}
                  onIncremental={crawler.startIncrementalCrawl}
                  onCancel={crawler.cancel}
                  onClear={crawler.clearStore}
                />
              </CardContent>
            </Card>

            <HeadlineMetrics
              efficiency={crawler.efficiency}
              aggregate={crawler.aggregate}
              latest={crawler.latestCrawl}
            />

            <AggregateSummary
              aggregate={crawler.aggregate}
              scope={crawler.scope}
            />

            <CrawlDiffPanel latest={crawler.latestCrawl} runs={crawler.runs} />

            <DuplicatesPanel
              duplicateGroups={crawler.duplicateGroups}
              scopeCount={crawler.scope.length}
            />

            <MutationPanel
              scope={crawler.scope}
              mutate={crawler.mutateResource}
              remove={crawler.deleteResource}
              onChanged={() => {}}
              refreshKey={refreshKey}
              disabled={crawler.isCrawling}
            />

            <Card>
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between gap-3">
                  <CardTitle className="text-base flex items-center gap-2">
                    <Folders className="h-4 w-4" />
                    Aggregated data
                  </CardTitle>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleExport}
                    disabled={exporting || crawler.aggregate.total === 0}
                    title="Download one NDJSON file per resource type (zipped)"
                  >
                    {exporting ? (
                      <Loader2 className="h-4 w-4 mr-1 animate-spin" />
                    ) : (
                      <Download className="h-4 w-4 mr-1" />
                    )}
                    Export NDJSON
                  </Button>
                </div>
              </CardHeader>
              <CardContent>
                <AggregateTable
                  scope={crawler.scope}
                  duplicateGroups={crawler.duplicateGroups}
                  perType={crawler.aggregate.perType}
                  refreshKey={refreshKey}
                />
              </CardContent>
            </Card>
          </div>

          {/* Secondary column: at-a-glance stats + live request log, anchored on xl */}
          <aside className="flex min-w-0 flex-col gap-4 xl:min-h-0">
            <AggregateStats
              aggregate={crawler.aggregate}
              scope={crawler.scope}
              latest={crawler.latestCrawl}
            />
            <CrawlSummaryRow latest={crawler.latestCrawl} />
            <div className="h-112 xl:h-auto xl:min-h-0 xl:flex-1">
              <RequestLog requestLog={crawler.requestLog} />
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}

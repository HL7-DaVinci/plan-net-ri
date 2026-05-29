import { createFileRoute } from "@tanstack/react-router";
import { Folders, Radar, SlidersHorizontal } from "lucide-react";
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
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useCrawler } from "@/hooks/use-crawler";
import { useDemoWalkthrough } from "@/hooks/use-demo-walkthrough";

export const Route = createFileRoute("/directory-crawler/")({
  component: DirectoryCrawler,
});

function DirectoryCrawler() {
  const crawler = useCrawler();
  const demo = useDemoWalkthrough(crawler);

  const refreshKey = crawler.runs.length * 1_000_000 + crawler.aggregate.total;

  return (
    // On xl, fill the viewport (minus the app header) so the left column scrolls
    // internally and the request log stays anchored full-height beside it.
    // On smaller screens, fall back to normal page flow.
    <div className="p-6 xl:h-[calc(100vh-3rem)] xl:overflow-hidden">
      <div className="mx-auto flex flex-col gap-6 xl:h-full">
        <div className="space-y-1 xl:shrink-0">
          <h1 className="text-xl font-semibold flex items-center gap-2">
            <Radar className="h-5 w-5 text-primary" />
            Directory Crawler
          </h1>
          <p className="text-sm text-muted-foreground">
            Keep payer provider directories in sync. Crawl one or more servers,
            detect updates and deletions, and aggregate it all locally.
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
                <CardTitle className="text-base flex items-center gap-2">
                  <Folders className="h-4 w-4" />
                  Aggregated data
                </CardTitle>
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

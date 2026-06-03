import { createFileRoute } from "@tanstack/react-router";
import {
  BarChart3,
  CalendarClock,
  Folders,
  History,
  Radio,
} from "lucide-react";
import { useState } from "react";
import { JobStats } from "@/components/api/job-stats";
import { JobsPanel } from "@/components/api/jobs-panel";
import { ManifestBrowser } from "@/components/api/manifest-browser";
import { PlayByPlay } from "@/components/api/play-by-play";
import { RunHistory } from "@/components/api/run-history";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export const Route = createFileRoute("/crawl-jobs/")({
  component: CrawlJobs,
});

function CrawlJobs() {
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const [liveBatchId, setLiveBatchId] = useState<string | null>(null);

  return (
    <div className="p-6">
      <div className="space-y-6">
        <div className="space-y-1">
          <h1 className="text-xl font-semibold flex items-center gap-2">
            <CalendarClock className="h-5 w-5 text-primary" />
            Crawl Jobs
          </h1>
          <p className="text-sm text-muted-foreground">
            Schedule server-side crawls against one or more FHIR servers. Each
            run retains a directory snapshot and publishes a fetchable manifest.
          </p>
        </div>

        <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
          <div className="space-y-6">
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-2">
                  <CalendarClock className="h-4 w-4" />
                  Jobs
                </CardTitle>
              </CardHeader>
              <CardContent>
                <JobsPanel
                  selectedJobId={selectedJobId}
                  onSelectJob={setSelectedJobId}
                  onRunTriggered={setLiveBatchId}
                />
              </CardContent>
            </Card>

            {liveBatchId && (
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-base flex items-center gap-2">
                    <Radio className="h-4 w-4 text-primary" />
                    Live play-by-play
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <PlayByPlay batchId={liveBatchId} />
                </CardContent>
              </Card>
            )}

            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-2">
                  <BarChart3 className="h-4 w-4" />
                  Job stats
                </CardTitle>
              </CardHeader>
              <CardContent>
                <JobStats jobId={selectedJobId} />
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-2">
                  <History className="h-4 w-4" />
                  Run history
                </CardTitle>
              </CardHeader>
              <CardContent>
                <RunHistory jobId={selectedJobId} />
              </CardContent>
            </Card>
          </div>

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base flex items-center gap-2">
                <Folders className="h-4 w-4" />
                Published manifests
              </CardTitle>
            </CardHeader>
            <CardContent>
              <ManifestBrowser />
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

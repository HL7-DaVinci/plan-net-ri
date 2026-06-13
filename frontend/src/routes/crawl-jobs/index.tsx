import { createFileRoute } from "@tanstack/react-router";
import {
  BarChart3,
  CalendarClock,
  ChevronDown,
  ChevronRight,
  Folders,
  History,
  Radio,
} from "lucide-react";
import { useEffect, useState } from "react";
import { JobStats } from "@/components/api/job-stats";
import { JobsPanel } from "@/components/api/jobs-panel";
import { ManifestBrowser } from "@/components/api/manifest-browser";
import { PlayByPlay } from "@/components/api/play-by-play";
import { RunHistory } from "@/components/api/run-history";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { useJobs } from "@/hooks/use-api";

export const Route = createFileRoute("/crawl-jobs/")({
  component: CrawlJobs,
});

function CrawlJobs() {
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const [livePlayOpen, setLivePlayOpen] = useState(true);
  const { data: jobs } = useJobs();

  // The live panel follows the selected job and only exists while it is running;
  // completed runs are reviewed in the run history section instead.
  const selectedJob = jobs?.find((job) => job.id === selectedJobId);
  const liveBatchId =
    (selectedJob?.running ? selectedJob.currentBatchId : null) ?? null;

  // Re-open the live panel each time a new run starts.
  useEffect(() => {
    if (liveBatchId) setLivePlayOpen(true);
  }, [liveBatchId]);

  // Surface a run already underway (scheduled, or started before this page
  // loaded) when the user has not picked a job yet.
  useEffect(() => {
    if (selectedJobId) return;
    const active = jobs?.find((job) => job.running && job.currentBatchId);
    if (active) {
      setSelectedJobId(active.id);
    }
  }, [jobs, selectedJobId]);

  return (
    <div className="p-6">
      <div className="space-y-6">
        <div className="space-y-1">
          <h1 className="text-xl font-semibold flex items-center gap-2">
            <CalendarClock className="h-5 w-5 text-primary" />
            Server Crawl Jobs
          </h1>
          <p className="text-sm text-muted-foreground">
            Schedule server-side crawls against one or more FHIR servers. Each
            run is stored on the server and published as a manifest.json URL
            that downstream systems can fetch.
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
                />
              </CardContent>
            </Card>

            {liveBatchId && selectedJob && (
              <Card>
                <Collapsible open={livePlayOpen} onOpenChange={setLivePlayOpen}>
                  <CardHeader className="pb-3">
                    <CollapsibleTrigger className="flex w-full cursor-pointer items-center gap-2 text-left">
                      {livePlayOpen ? (
                        <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
                      ) : (
                        <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                      )}
                      <Radio className="h-4 w-4 shrink-0 text-primary" />
                      <CardTitle className="text-base">
                        Live play-by-play: {selectedJob.name}
                      </CardTitle>
                    </CollapsibleTrigger>
                  </CardHeader>
                  <CollapsibleContent>
                    <CardContent>
                      <PlayByPlay
                        batchId={liveBatchId}
                        jobName={selectedJob.name}
                      />
                    </CardContent>
                  </CollapsibleContent>
                </Collapsible>
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

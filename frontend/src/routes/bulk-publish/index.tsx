import { useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";
import { ManifestPanel } from "@/components/publish/manifest-panel";
import { PublishMutationPanel } from "@/components/publish/mutation-panel";
import { SnapshotTimeline } from "@/components/publish/snapshot-timeline";
import { WatchLog } from "@/components/publish/watch-log";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  useBulkPublishWatcher,
  usePublishSnapshots,
} from "@/hooks/use-bulk-publish";
import { useFhirServer } from "@/hooks/use-fhir-server";

export const Route = createFileRoute("/bulk-publish/")({
  component: BulkPublishDemo,
});

function BulkPublishDemo() {
  const { serverUrl, server } = useFhirServer();
  const watcher = useBulkPublishWatcher(serverUrl);
  const snapshots = usePublishSnapshots(serverUrl);
  const queryClient = useQueryClient();

  // A new manifest means a new snapshot may exist on disk; refresh the timeline.
  // biome-ignore lint/correctness/useExhaustiveDependencies: watcher.etag is the re-run trigger, not read in the body
  useEffect(() => {
    queryClient.invalidateQueries({ queryKey: ["publish", "snapshots"] });
  }, [watcher.etag, queryClient]);

  return (
    <div className="p-6 space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Bulk Publish</h1>
        <p className="text-sm text-muted-foreground">
          Demonstrates the FHIR Bulk Data $bulk-publish operation against{" "}
          {server?.name ?? serverUrl}: manifest caching with ETags, per-type
          file reuse across snapshots, and change detection.
        </p>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-4">
          <ManifestPanel
            manifest={watcher.manifest}
            status={watcher.status}
            etag={watcher.etag}
          />
          <PublishMutationPanel serverUrl={serverUrl} />
        </div>
        <div className="space-y-4">
          <WatchLog
            log={watcher.log}
            paused={watcher.paused}
            onTogglePause={() => watcher.setPaused(!watcher.paused)}
            onCheckNow={() => void watcher.checkNow()}
            serverUrl={serverUrl}
            etag={watcher.etag}
          />
          <Card>
            <CardHeader>
              <CardTitle>Snapshot Timeline</CardTitle>
            </CardHeader>
            <CardContent>
              {snapshots.isError ? (
                <p className="text-sm text-muted-foreground">
                  Snapshot internals (retained directories, per-type file
                  ownership) are available when the selected server is a
                  Plan-Net reference implementation deployment.
                </p>
              ) : (
                <SnapshotTimeline snapshots={snapshots.data ?? []} />
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

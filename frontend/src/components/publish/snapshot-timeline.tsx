import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { fileOrigin, type SnapshotListing } from "@/lib/publish/manifest";

function shortId(id: string): string {
  return id.slice(0, 8);
}

export function SnapshotTimeline({
  snapshots,
}: {
  snapshots: SnapshotListing[];
}) {
  if (snapshots.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">No snapshots on disk yet.</p>
    );
  }
  return (
    <div className="space-y-3">
      {snapshots.map((snapshot) => (
        <Card
          key={snapshot.id}
          className={snapshot.current ? "border-primary" : undefined}
        >
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm">
              <span className="font-mono">{shortId(snapshot.id)}</span>
              <span className="font-normal text-muted-foreground">
                {snapshot.transactionTime}
              </span>
              {snapshot.current && <Badge>current</Badge>}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2 pt-0">
            {snapshot.files.map((file) =>
              fileOrigin(snapshot.id, file) === "exported" ? (
                <Badge key={file.type} title={`${file.count} resources`}>
                  {file.type}
                </Badge>
              ) : (
                <Badge
                  key={file.type}
                  variant="secondary"
                  title={`${file.count} resources, file owned by snapshot ${file.snapshotId}`}
                >
                  {file.type} (reused from {shortId(file.snapshotId)})
                </Badge>
              ),
            )}
            {snapshot.files.length === 0 && (
              <span className="text-xs text-muted-foreground">
                No exported types.
              </span>
            )}
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

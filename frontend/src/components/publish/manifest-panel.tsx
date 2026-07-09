import { Download, Eye, FileJson } from "lucide-react";
import { toast } from "sonner";
import {
  JsonViewerDialog,
  useJsonViewer,
} from "@/components/json-viewer-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { ManifestStatus } from "@/hooks/use-bulk-publish";
import type { PublishManifest } from "@/lib/publish/manifest";

const PEEK_LINES = 20;

const STATUS_MESSAGES: Record<Exclude<ManifestStatus, "ready">, string> = {
  loading: "Fetching the manifest...",
  "no-snapshot":
    "The server responded 503: no snapshot has been published yet. The first publish should appear shortly.",
  unsupported:
    "This server did not answer $bulk-publish (404 or error response). It may not implement the Bulk Publish operation.",
  unreachable:
    "Could not reach the server from the browser. The server may be down or may not allow cross-origin requests (CORS).",
};

export function ManifestPanel({
  manifest,
  status,
  etag,
}: {
  manifest: PublishManifest | null;
  status: ManifestStatus;
  etag: string | null;
}) {
  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  const peek = async (url: string, type: string) => {
    try {
      const response = await fetch(url);
      if (!response.ok) {
        toast.error(`Fetch failed: ${response.status}`);
        return;
      }
      const text = await response.text();
      const lines = text.split("\n").filter(Boolean);
      const shown = lines.slice(0, PEEK_LINES).join("\n");
      openViewer(
        shown || "(empty file)",
        `${type}.ndjson`,
        `First ${Math.min(lines.length, PEEK_LINES)} of ${lines.length} lines`,
      );
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Fetch failed");
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between">
          <span>Manifest</span>
          {manifest && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => openViewer(manifest, "$bulk-publish manifest")}
            >
              <FileJson className="h-4 w-4 mr-1" />
              Raw JSON
            </Button>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {status !== "ready" && (
          <p className="text-sm text-muted-foreground">
            {STATUS_MESSAGES[status as Exclude<ManifestStatus, "ready">]}
          </p>
        )}
        {manifest && (
          <>
            <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm md:grid-cols-4">
              <div>
                <div className="text-muted-foreground">Transaction time</div>
                <div className="font-mono">{manifest.transactionTime}</div>
              </div>
              <div>
                <div className="text-muted-foreground">Update cadence</div>
                <div className="font-mono">{manifest.updateCadence ?? "-"}</div>
              </div>
              <div>
                <div className="text-muted-foreground">Requires token</div>
                <div>{manifest.requiresAccessToken ? "yes" : "no"}</div>
              </div>
              <div>
                <div className="text-muted-foreground">ETag</div>
                <div className="font-mono truncate" title={etag ?? undefined}>
                  {etag ?? "-"}
                </div>
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-muted-foreground">
                    <th className="py-1 pr-4 font-medium">Type</th>
                    <th className="py-1 pr-4 font-medium">Count</th>
                    <th className="py-1 pr-4 font-medium">Size (bytes)</th>
                    <th className="py-1 font-medium">File</th>
                  </tr>
                </thead>
                <tbody>
                  {manifest.output.map((entry) => (
                    <tr key={entry.type} className="border-b last:border-0">
                      <td className="py-1 pr-4">
                        <Badge variant="outline">{entry.type}</Badge>
                      </td>
                      <td className="py-1 pr-4 font-mono">{entry.count}</td>
                      <td className="py-1 pr-4 font-mono">{entry.fileSize}</td>
                      <td className="py-1">
                        <div className="flex items-center gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => peek(entry.url, entry.type)}
                          >
                            <Eye className="h-4 w-4 mr-1" />
                            Peek
                          </Button>
                          <Button variant="ghost" size="sm" asChild>
                            <a href={entry.url} download>
                              <Download className="h-4 w-4 mr-1" />
                              Download
                            </a>
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        {viewerData && (
          <JsonViewerDialog
            data={viewerData.data}
            title={viewerData.title}
            description={viewerData.description}
            onClose={closeViewer}
          />
        )}
      </CardContent>
    </Card>
  );
}

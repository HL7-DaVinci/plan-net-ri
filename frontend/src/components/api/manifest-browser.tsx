import { useQuery } from "@tanstack/react-query";
import {
  Check,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  ExternalLink,
  Trash2,
} from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { useDeleteManifest, useManifests } from "@/hooks/use-api";
import { api } from "@/lib/api/client";

function formatTime(iso: string | null): string {
  if (!iso) return "-";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)} s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${Math.round(seconds % 60)}s`;
}

function ManifestDetail({ manifestId }: { manifestId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ["api", "manifest", manifestId],
    queryFn: () => api.getManifest(manifestId),
  });

  if (isLoading) {
    return (
      <p className="px-3 py-2 text-xs text-muted-foreground">
        Loading manifest...
      </p>
    );
  }
  if (!data || data.output.length === 0) {
    return <p className="px-3 py-2 text-xs text-muted-foreground">No files.</p>;
  }
  return (
    <ul className="space-y-1 px-3 py-2">
      {data.output.map((entry) => (
        <li
          key={entry.url}
          className="flex items-center justify-between gap-2 text-sm"
        >
          <span className="font-medium">{entry.type}</span>
          <span className="flex items-center gap-3">
            <span className="tabular-nums text-muted-foreground">
              {entry.count.toLocaleString()}
            </span>
            <a
              href={entry.url}
              download
              className="inline-flex items-center gap-1 text-primary hover:underline"
            >
              <Download className="h-3.5 w-3.5" />
              ndjson
            </a>
          </span>
        </li>
      ))}
    </ul>
  );
}

export function ManifestBrowser() {
  const { data: manifests, isLoading } = useManifests();
  const deleteManifest = useDeleteManifest();
  const [expanded, setExpanded] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const copyUrl = (id: string) => {
    navigator.clipboard.writeText(api.manifestUrl(id)).then(() => {
      setCopied(id);
      setTimeout(() => setCopied((c) => (c === id ? null : c)), 1500);
    });
  };

  if (isLoading) {
    return (
      <p className="text-sm text-muted-foreground">Loading manifests...</p>
    );
  }
  if (!manifests || manifests.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No published manifests yet. Run a crawl job to publish one.
      </p>
    );
  }

  return (
    <div className="space-y-2">
      {manifests.map((manifest) => {
        const isOpen = expanded === manifest.id;
        return (
          <div key={manifest.id} className="rounded-lg border">
            <div className="flex items-center justify-between gap-3 p-3">
              <button
                type="button"
                onClick={() => setExpanded(isOpen ? null : manifest.id)}
                className="flex min-w-0 cursor-pointer items-center gap-2 text-left"
              >
                {isOpen ? (
                  <ChevronDown className="h-4 w-4 shrink-0" />
                ) : (
                  <ChevronRight className="h-4 w-4 shrink-0" />
                )}
                <span className="min-w-0">
                  <span className="block text-sm font-medium">
                    {manifest.jobName ?? "Unknown job"}
                  </span>
                  <span className="block text-xs text-muted-foreground">
                    {formatTime(manifest.transactionTime)}
                  </span>
                  <span className="block text-xs text-muted-foreground">
                    {manifest.totalResources.toLocaleString()} resources
                    {manifest.windowSince ? " (incremental)" : " (full)"}, built
                    in {formatDuration(manifest.buildDurationMs)}
                  </span>
                </span>
              </button>
              <div className="flex shrink-0 items-center gap-1">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => copyUrl(manifest.id)}
                  className="cursor-pointer"
                  title="Copy manifest URL"
                >
                  {copied === manifest.id ? (
                    <Check className="h-4 w-4 mr-1" />
                  ) : (
                    <Copy className="h-4 w-4 mr-1" />
                  )}
                  Copy URL
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  asChild
                  className="cursor-pointer"
                >
                  <a
                    href={api.manifestUrl(manifest.id)}
                    target="_blank"
                    rel="noreferrer"
                    title="Open manifest.json"
                  >
                    <ExternalLink className="h-4 w-4" />
                  </a>
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => deleteManifest.mutate(manifest.id)}
                  disabled={deleteManifest.isPending}
                  className="cursor-pointer text-destructive"
                  title="Delete manifest"
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
            {isOpen && (
              <div className="border-t">
                <ManifestDetail manifestId={manifest.id} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

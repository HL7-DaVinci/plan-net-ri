import { ExternalLink, FileJson, Loader2, Terminal } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { ErrorBoundary } from "@/components/error-boundary";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useTheme } from "@/hooks/use-theme";
import { formatBytes } from "@/lib/crawler/format";
import type { RequestLogEntry } from "@/lib/crawler/types";

const MonacoEditor = lazy(() =>
  import("@monaco-editor/react").then((mod) => ({ default: mod.Editor })),
);

interface RequestLogProps {
  requestLog: RequestLogEntry[];
}

function methodClass(method: string): string {
  switch (method) {
    case "GET":
      return "text-sky-500";
    case "PUT":
      return "text-amber-500";
    case "DELETE":
      return "text-destructive";
    default:
      return "text-muted-foreground";
  }
}

/** Path + query only, dropping the scheme/host for readability. */
function shortUrl(url: string): string {
  return url.replace(/^https?:\/\/[^/]+/, "") || url;
}

interface Payloads {
  request?: string;
  response?: string;
}

function payloads(entry: RequestLogEntry): Payloads {
  const result: Payloads = {};
  if (entry.requestBody !== undefined) {
    result.request = JSON.stringify(entry.requestBody, null, 2);
  }
  if (entry.responseBody !== undefined) {
    result.response = JSON.stringify(entry.responseBody, null, 2);
  }
  return result;
}

/** Label describing which payloads are available to view. */
function viewLabel({ request, response }: Payloads): string | null {
  if (request && response) return "view request & response";
  if (response) return "view response";
  if (request) return "view request";
  return null;
}

function JsonPane({ value }: { value: string }) {
  const { effectiveTheme } = useTheme();
  return (
    <ErrorBoundary
      fallback={
        <pre className="h-full overflow-auto bg-muted p-4 text-xs">{value}</pre>
      }
    >
      <Suspense
        fallback={
          <div className="flex h-full items-center justify-center">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        }
      >
        <MonacoEditor
          height="100%"
          language="json"
          value={value}
          theme={effectiveTheme === "dark" ? "vs-dark" : "light"}
          options={{
            readOnly: true,
            minimap: { enabled: false },
            fontSize: 13,
            lineNumbers: "on",
            scrollBeyondLastLine: false,
            wordWrap: "on",
            folding: true,
            automaticLayout: true,
          }}
        />
      </Suspense>
    </ErrorBoundary>
  );
}

function RequestDetailDialog({
  entry,
  onClose,
}: {
  entry: RequestLogEntry;
  onClose: () => void;
}) {
  const { request, response } = payloads(entry);
  const [tab, setTab] = useState(request ? "request" : "response");

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex h-[90vh] w-[90vw] max-w-full flex-col">
        <DialogHeader className="shrink-0">
          <DialogTitle className="flex items-center gap-2">
            <span className={`font-mono ${methodClass(entry.method)}`}>
              {entry.method}
            </span>
            <span className="font-mono text-sm">{shortUrl(entry.url)}</span>
          </DialogTitle>
          <DialogDescription>
            HTTP {entry.status}, {entry.ms}ms, {formatBytes(entry.bytes)}
          </DialogDescription>
        </DialogHeader>

        <Tabs
          value={tab}
          onValueChange={setTab}
          className="flex min-h-0 flex-1 flex-col"
        >
          <TabsList className="shrink-0">
            {request && <TabsTrigger value="request">Request body</TabsTrigger>}
            {response && (
              <TabsTrigger value="response">Response body</TabsTrigger>
            )}
          </TabsList>
          {request && (
            <TabsContent
              value="request"
              className="mt-3 min-h-0 flex-1 overflow-hidden rounded-md border"
            >
              <JsonPane value={request} />
            </TabsContent>
          )}
          {response && (
            <TabsContent
              value="response"
              className="mt-3 min-h-0 flex-1 overflow-hidden rounded-md border"
            >
              <JsonPane value={response} />
            </TabsContent>
          )}
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}

const ROW_CLASS =
  "block w-full cursor-pointer border-b border-border/40 px-3 py-1.5 text-left hover:bg-muted/40 disabled:cursor-default disabled:opacity-60";

function LogRowBody({ entry }: { entry: RequestLogEntry }) {
  const isGet = entry.method === "GET";
  const label = viewLabel(payloads(entry));
  return (
    <>
      <div className="flex items-center gap-2">
        <span
          className={`w-12 shrink-0 font-semibold ${methodClass(entry.method)}`}
        >
          {entry.method}
        </span>
        <span className="flex-1 truncate" title={entry.url}>
          {shortUrl(entry.url)}
        </span>
        {isGet ? (
          <ExternalLink className="h-3 w-3 shrink-0 text-muted-foreground" />
        ) : label ? (
          <FileJson className="h-3 w-3 shrink-0 text-muted-foreground" />
        ) : null}
      </div>
      <div className="flex items-center justify-between pl-14">
        <span className="shrink-0 text-[10px] text-muted-foreground tabular-nums">
          {entry.status}, {entry.ms}ms, {formatBytes(entry.bytes)}
        </span>
        {!isGet && label && (
          <span className="text-[10px] text-primary">{label}</span>
        )}
      </div>
    </>
  );
}

function LogRow({
  entry,
  onView,
}: {
  entry: RequestLogEntry;
  onView: (entry: RequestLogEntry) => void;
}) {
  if (entry.method === "GET") {
    return (
      <a
        href={entry.url}
        target="_blank"
        rel="noreferrer"
        className={ROW_CLASS}
      >
        <LogRowBody entry={entry} />
      </a>
    );
  }
  const hasPayload = viewLabel(payloads(entry)) !== null;
  return (
    <button
      type="button"
      className={ROW_CLASS}
      onClick={() => onView(entry)}
      disabled={!hasPayload}
    >
      <LogRowBody entry={entry} />
    </button>
  );
}

export function RequestLog({ requestLog }: RequestLogProps) {
  const [viewing, setViewing] = useState<RequestLogEntry | null>(null);

  return (
    <div className="flex h-full flex-col rounded-lg border bg-card">
      <div className="flex shrink-0 items-center gap-2 border-b px-4 py-3 text-sm font-medium">
        <Terminal className="h-4 w-4 text-muted-foreground" />
        FHIR request log
        <span className="text-xs font-normal text-muted-foreground">
          ({requestLog.length})
        </span>
      </div>

      <div className="flex-1 overflow-auto font-mono text-xs">
        {requestLog.length === 0 ? (
          <div className="px-4 py-6 text-center font-sans text-muted-foreground">
            No requests yet. Run a crawl to see the actual FHIR calls.
          </div>
        ) : (
          requestLog.map((entry) => (
            <LogRow key={entry.id} entry={entry} onView={setViewing} />
          ))
        )}
      </div>

      {viewing && (
        <RequestDetailDialog entry={viewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
}

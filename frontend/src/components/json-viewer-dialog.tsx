import { Check, Code, Copy, Loader2 } from "lucide-react";
import {
  lazy,
  memo,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { toast } from "sonner";
import { ErrorBoundary } from "@/components/error-boundary";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useTheme } from "@/hooks/use-theme";

const MonacoEditor = lazy(() =>
  import("@monaco-editor/react").then((mod) => ({ default: mod.Editor })),
);

export interface JsonViewerData {
  data: unknown;
  title: string;
  description?: string;
}

interface JsonViewerDialogProps {
  /** The data to display. Can be a FHIR resource or any JSON-serializable data */
  data: unknown;
  /** Custom title. If not provided, derives from resourceType/id for FHIR resources */
  title?: string;
  /** Optional description shown below the title */
  description?: string;
  onClose: () => void;
}

/** Type guard to check if data looks like a FHIR resource */
function isFhirLike(
  data: unknown,
): data is { resourceType: string; id?: string } {
  return (
    typeof data === "object" &&
    data !== null &&
    "resourceType" in data &&
    typeof (data as Record<string, unknown>).resourceType === "string"
  );
}

export const JsonViewerDialog = memo(function JsonViewerDialog({
  data,
  title,
  description,
  onClose,
}: JsonViewerDialogProps) {
  const { effectiveTheme } = useTheme();
  const [copied, setCopied] = useState(false);
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const jsonString = useMemo(
    () => (data ? JSON.stringify(data, null, 2) : ""),
    [data],
  );

  // Derive title from FHIR resource if not provided
  const displayTitle = useMemo(() => {
    if (title) return title;
    if (isFhirLike(data)) {
      return data.id ? `${data.resourceType}/${data.id}` : data.resourceType;
    }
    return "JSON";
  }, [title, data]);

  // Default description for FHIR resources
  const displayDescription =
    description ??
    (isFhirLike(data) && !title ? "Raw JSON representation" : undefined);

  const monacoTheme = effectiveTheme === "dark" ? "vs-dark" : "light";

  useEffect(() => {
    return () => {
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
      }
    };
  }, []);

  // biome-ignore lint/correctness/useExhaustiveDependencies: intentionally reset copied state when data changes
  useEffect(() => {
    setCopied(false);
  }, [displayTitle]);

  const handleCopy = useCallback(async () => {
    if (!jsonString) return;

    try {
      await navigator.clipboard.writeText(jsonString);
      setCopied(true);
      toast.success("JSON copied to clipboard");
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
      }
      copyTimeoutRef.current = setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Failed to copy to clipboard");
    }
  }, [jsonString]);

  if (!data) return null;

  return (
    <Dialog open={!!data} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-full w-[90vw] h-[90vh] flex flex-col">
        <DialogHeader className="shrink-0">
          <div className="flex items-center justify-between">
            <div>
              <DialogTitle className="flex items-center gap-2">
                <Code className="h-4 w-4" />
                {displayTitle}
              </DialogTitle>
              <DialogDescription>{displayDescription}</DialogDescription>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={handleCopy}
              className="mr-8"
            >
              {copied ? (
                <>
                  <Check className="h-4 w-4 mr-1" />
                  Copied
                </>
              ) : (
                <>
                  <Copy className="h-4 w-4 mr-1" />
                  Copy
                </>
              )}
            </Button>
          </div>
        </DialogHeader>
        <div className="flex-1 min-h-0 border rounded-md overflow-hidden">
          <ErrorBoundary
            fallback={
              <div className="flex flex-col items-center justify-center h-full text-muted-foreground">
                <p className="text-sm">Failed to load JSON editor</p>
                <pre className="mt-2 p-4 bg-muted rounded text-xs overflow-auto max-w-full">
                  {jsonString}
                </pre>
              </div>
            }
          >
            <Suspense
              fallback={
                <div className="flex items-center justify-center h-full">
                  <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                </div>
              }
            >
              <MonacoEditor
                height="100%"
                language="json"
                value={jsonString}
                theme={monacoTheme}
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
        </div>
      </DialogContent>
    </Dialog>
  );
});

/**
 * Hook to manage JSON viewer state
 */
export function useJsonViewer() {
  const [viewerData, setViewerData] = useState<JsonViewerData | null>(null);

  const openViewer = useCallback(
    (data: unknown, title: string, description?: string) => {
      setViewerData({ data, title, description });
    },
    [],
  );

  const closeViewer = useCallback(() => {
    setViewerData(null);
  }, []);

  return {
    viewerData,
    openViewer,
    closeViewer,
  };
}

import { AlertCircle, Check, Code, Copy, Loader2, Save } from "lucide-react";
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

interface JsonEditorDialogProps {
  /** The data to edit */
  data: unknown;
  /** Dialog title */
  title: string;
  /** Optional description */
  description?: string;
  /** Called when dialog is closed without saving */
  onClose: () => void;
  /** Called when user saves changes */
  onSave: (data: unknown) => void;
}

export const JsonEditorDialog = memo(function JsonEditorDialog({
  data,
  title,
  description,
  onClose,
  onSave,
}: JsonEditorDialogProps) {
  const { effectiveTheme } = useTheme();
  const [copied, setCopied] = useState(false);
  const [editedJson, setEditedJson] = useState("");
  const [parseError, setParseError] = useState<string | null>(null);
  const [hasChanges, setHasChanges] = useState(false);
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const initialJson = useMemo(
    () => (data ? JSON.stringify(data, null, 2) : ""),
    [data],
  );

  // Initialize edited JSON when data changes
  useEffect(() => {
    setEditedJson(initialJson);
    setParseError(null);
    setHasChanges(false);
  }, [initialJson]);

  const monacoTheme = effectiveTheme === "dark" ? "vs-dark" : "light";

  useEffect(() => {
    return () => {
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
      }
    };
  }, []);

  const handleEditorChange = useCallback(
    (value: string | undefined) => {
      const newValue = value ?? "";
      setEditedJson(newValue);
      setHasChanges(newValue !== initialJson);

      // Validate JSON
      try {
        JSON.parse(newValue);
        setParseError(null);
      } catch (e) {
        setParseError(e instanceof Error ? e.message : "Invalid JSON");
      }
    },
    [initialJson],
  );

  const handleCopy = useCallback(async () => {
    if (!editedJson) return;

    try {
      await navigator.clipboard.writeText(editedJson);
      setCopied(true);
      toast.success("JSON copied to clipboard");
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
      }
      copyTimeoutRef.current = setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Failed to copy to clipboard");
    }
  }, [editedJson]);

  const handleSave = useCallback(() => {
    if (parseError) {
      toast.error("Cannot save: Invalid JSON");
      return;
    }

    try {
      const parsed = JSON.parse(editedJson);
      onSave(parsed);
      toast.success("Changes saved");
    } catch {
      toast.error("Failed to parse JSON");
    }
  }, [editedJson, parseError, onSave]);

  const handleClose = useCallback(() => {
    onClose();
  }, [onClose]);

  if (!data) return null;

  return (
    <Dialog open={!!data} onOpenChange={(open) => !open && handleClose()}>
      <DialogContent className="max-w-full w-[90vw] h-[90vh] flex flex-col">
        <DialogHeader className="shrink-0">
          <div className="flex items-center justify-between">
            <div>
              <DialogTitle className="flex items-center gap-2">
                <Code className="h-4 w-4" />
                {title}
              </DialogTitle>
              <DialogDescription>{description}</DialogDescription>
            </div>
            <div className="flex items-center gap-2 mr-8">
              <Button variant="outline" size="sm" onClick={handleCopy}>
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
              <Button
                size="sm"
                onClick={handleSave}
                disabled={!hasChanges || !!parseError}
              >
                <Save className="h-4 w-4 mr-1" />
                Save
              </Button>
            </div>
          </div>
        </DialogHeader>

        {/* Error banner */}
        {parseError && (
          <div className="flex items-center gap-2 px-3 py-2 bg-destructive/10 border border-destructive/30 rounded-md text-destructive text-sm">
            <AlertCircle className="h-4 w-4 shrink-0" />
            <span className="truncate">{parseError}</span>
          </div>
        )}

        <div className="flex-1 min-h-0 border rounded-md overflow-hidden">
          <ErrorBoundary
            fallback={
              <div className="flex flex-col items-center justify-center h-full text-muted-foreground">
                <p className="text-sm">Failed to load JSON editor</p>
                <textarea
                  className="mt-2 p-4 bg-muted rounded text-xs w-full h-64 font-mono"
                  value={editedJson}
                  onChange={(e) => handleEditorChange(e.target.value)}
                />
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
                value={editedJson}
                onChange={handleEditorChange}
                theme={monacoTheme}
                options={{
                  readOnly: false,
                  minimap: { enabled: false },
                  fontSize: 13,
                  lineNumbers: "on",
                  scrollBeyondLastLine: false,
                  wordWrap: "on",
                  folding: true,
                  automaticLayout: true,
                  formatOnPaste: true,
                  formatOnType: true,
                }}
              />
            </Suspense>
          </ErrorBoundary>
        </div>

        {/* Footer with status */}
        <div className="flex items-center justify-between text-xs text-muted-foreground pt-2">
          <span>
            {hasChanges ? (
              <span className="text-yellow-600 dark:text-yellow-500">
                Unsaved changes
              </span>
            ) : (
              "No changes"
            )}
          </span>
          <span>Press Ctrl+S to format</span>
        </div>
      </DialogContent>
    </Dialog>
  );
});

/**
 * Hook to manage JSON editor state
 */
export function useJsonEditor() {
  const [editorData, setEditorData] = useState<{
    data: unknown;
    title: string;
    description?: string;
    onSave: (data: unknown) => void;
  } | null>(null);

  const openEditor = useCallback(
    (
      data: unknown,
      title: string,
      onSave: (data: unknown) => void,
      description?: string,
    ) => {
      setEditorData({ data, title, onSave, description });
    },
    [],
  );

  const closeEditor = useCallback(() => {
    setEditorData(null);
  }, []);

  return {
    editorData,
    openEditor,
    closeEditor,
  };
}

import { useNavigate } from "@tanstack/react-router";
import { Command } from "cmdk";
import {
  FileJson,
  LayoutDashboard,
  Moon,
  Search,
  Server,
  Settings,
  Sun,
} from "lucide-react";
import { VisuallyHidden } from "radix-ui";
import { useCallback, useEffect, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { getResourceTypes, useCapabilityStatement } from "@/hooks/use-fhir-api";
import { useFhirServer } from "@/hooks/use-fhir-server";
import { useTheme } from "@/hooks/use-theme";

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onOpenSettings?: () => void;
}

export function CommandPalette({
  open,
  onOpenChange,
  onOpenSettings,
}: CommandPaletteProps) {
  const navigate = useNavigate();
  const { serverUrl } = useFhirServer();
  const { data: capability } = useCapabilityStatement(serverUrl);
  const { theme, setTheme } = useTheme();
  const [search, setSearch] = useState("");

  const resourceTypes = getResourceTypes(capability);

  // Reset search when closing
  useEffect(() => {
    if (!open) {
      setSearch("");
    }
  }, [open]);

  const handleSelect = useCallback(
    (value: string) => {
      onOpenChange(false);

      if (value === "dashboard") {
        navigate({ to: "/" });
      } else if (value === "resources") {
        navigate({ to: "/resources", search: {} });
      } else if (value === "settings") {
        onOpenSettings?.();
      } else if (value === "theme-light") {
        setTheme("light");
      } else if (value === "theme-dark") {
        setTheme("dark");
      } else if (value === "theme-system") {
        setTheme("system");
      } else if (value.startsWith("resource-type:")) {
        const resourceType = value.replace("resource-type:", "");
        navigate({
          to: "/resources",
          search: { type: resourceType },
        });
      }
    },
    [navigate, onOpenChange, onOpenSettings, setTheme],
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="overflow-hidden p-0 shadow-lg max-w-lg">
        <VisuallyHidden.Root>
          <DialogHeader>
            <DialogTitle>Command Palette</DialogTitle>
            <DialogDescription>
              Command palette for quick navigation and actions
            </DialogDescription>
          </DialogHeader>
        </VisuallyHidden.Root>
        <Command className="**:[[cmdk-group-heading]]:px-2 **:[[cmdk-group-heading]]:font-medium **:[[cmdk-group-heading]]:text-muted-foreground">
          <div className="flex items-center border-b px-3">
            <Search className="mr-2 h-4 w-4 shrink-0 opacity-50" />
            <Command.Input
              placeholder="Search commands..."
              className="flex h-11 w-full rounded-md bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50"
              value={search}
              onValueChange={setSearch}
            />
          </div>
          <Command.List className="max-h-[400px] overflow-y-auto p-2">
            <Command.Empty className="py-6 text-center text-sm text-muted-foreground">
              No results found.
            </Command.Empty>

            {/* Navigation */}
            <Command.Group heading="Navigation">
              <Command.Item
                value="dashboard"
                onSelect={handleSelect}
                className="flex items-center gap-3 px-2 py-1.5 text-sm rounded-md cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"
              >
                <LayoutDashboard className="h-4 w-4 text-muted-foreground" />
                <span>Dashboard</span>
              </Command.Item>
              <Command.Item
                value="resources"
                onSelect={handleSelect}
                className="flex items-center gap-3 px-2 py-1.5 text-sm rounded-md cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"
              >
                <FileJson className="h-4 w-4 text-muted-foreground" />
                <span>Resources</span>
              </Command.Item>
              <Command.Item
                value="settings"
                onSelect={handleSelect}
                className="flex items-center gap-3 px-2 py-1.5 text-sm rounded-md cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"
              >
                <Settings className="h-4 w-4 text-muted-foreground" />
                <span>Settings</span>
              </Command.Item>
            </Command.Group>

            {/* Resource Types */}
            {resourceTypes.length > 0 && (
              <Command.Group heading="Resource Types">
                {resourceTypes.map((type) => (
                  <Command.Item
                    key={type}
                    value={`resource-type:${type}`}
                    onSelect={handleSelect}
                    className="flex items-center gap-3 px-2 py-1.5 text-sm rounded-md cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"
                  >
                    <Server className="h-4 w-4 text-muted-foreground" />
                    <span>{type}</span>
                  </Command.Item>
                ))}
              </Command.Group>
            )}

            {/* Theme */}
            <Command.Group heading="Theme">
              <Command.Item
                value="theme-light"
                onSelect={handleSelect}
                className="flex items-center gap-3 px-2 py-1.5 text-sm rounded-md cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"
              >
                <Sun className="h-4 w-4 text-muted-foreground" />
                <span>Light Mode</span>
                {theme === "light" && (
                  <span className="ml-auto text-xs text-muted-foreground">
                    Active
                  </span>
                )}
              </Command.Item>
              <Command.Item
                value="theme-dark"
                onSelect={handleSelect}
                className="flex items-center gap-3 px-2 py-1.5 text-sm rounded-md cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"
              >
                <Moon className="h-4 w-4 text-muted-foreground" />
                <span>Dark Mode</span>
                {theme === "dark" && (
                  <span className="ml-auto text-xs text-muted-foreground">
                    Active
                  </span>
                )}
              </Command.Item>
              <Command.Item
                value="theme-system"
                onSelect={handleSelect}
                className="flex items-center gap-3 px-2 py-1.5 text-sm rounded-md cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"
              >
                <Settings className="h-4 w-4 text-muted-foreground" />
                <span>System Theme</span>
                {theme === "system" && (
                  <span className="ml-auto text-xs text-muted-foreground">
                    Active
                  </span>
                )}
              </Command.Item>
            </Command.Group>
          </Command.List>
        </Command>
      </DialogContent>
    </Dialog>
  );
}

// Hook to handle global Cmd+K shortcut
export function useCommandPalette() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };

    document.addEventListener("keydown", down);
    return () => document.removeEventListener("keydown", down);
  }, []);

  return { open, setOpen };
}

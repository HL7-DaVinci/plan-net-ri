import { TanStackDevtools } from "@tanstack/react-devtools";
import { createRootRoute, Outlet } from "@tanstack/react-router";
import { TanStackRouterDevtoolsPanel } from "@tanstack/react-router-devtools";
import { Settings } from "lucide-react";
import { useState } from "react";
import { AppSidebar } from "@/components/app-sidebar";
import {
  CommandPalette,
  useCommandPalette,
} from "@/components/command-palette";
import { ServerStatus } from "@/components/server-status";
import { SettingsDialog } from "@/components/settings-dialog";
import { Button } from "@/components/ui/button";
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { usePlatform } from "@/hooks/use-platform";
import { NotFoundComponent } from "./-not-found";

export const Route = createRootRoute({
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
});

function RootComponent() {
  const { open: commandPaletteOpen, setOpen: setCommandPaletteOpen } =
    useCommandPalette();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const { modifierKey } = usePlatform();

  return (
    <TooltipProvider>
      <SidebarProvider>
        <AppSidebar />
        <SidebarInset>
          <header className="flex h-12 shrink-0 items-center justify-between gap-2 border-b px-4 bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
            <div className="flex items-center gap-2">
              <SidebarTrigger className="-ml-1" />
            </div>

            <div className="flex items-center gap-3">
              <ServerStatus showLatency />

              <Button
                variant="outline"
                size="sm"
                className="h-8 gap-2 text-muted-foreground"
                onClick={() => setCommandPaletteOpen(true)}
              >
                <span className="hidden sm:inline-flex">Search...</span>
                <kbd className="kbd hidden sm:inline-flex">{modifierKey}K</kbd>
              </Button>

              <Button
                variant="ghost"
                size="icon"
                className="h-8 w-8"
                onClick={() => setSettingsOpen(true)}
                aria-label="Open settings"
              >
                <Settings className="h-4 w-4" />
              </Button>
            </div>
          </header>

          <main className="flex-1 overflow-auto bg-gradient-to-br from-slate-50/50 to-background dark:from-slate-950/30 dark:to-background">
            <Outlet />
          </main>
        </SidebarInset>
      </SidebarProvider>

      <CommandPalette
        open={commandPaletteOpen}
        onOpenChange={setCommandPaletteOpen}
        onOpenSettings={() => setSettingsOpen(true)}
      />

      <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />

      <TanStackDevtools
        config={{
          position: "bottom-right",
        }}
        plugins={[
          {
            name: "Tanstack Router",
            render: <TanStackRouterDevtoolsPanel />,
          },
        ]}
      />
    </TooltipProvider>
  );
}

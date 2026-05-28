import { useMemo } from "react";

function isMacOS(): boolean {
  if (typeof navigator === "undefined") return false;
  if ("userAgentData" in navigator) {
    const platform = (
      navigator as Navigator & { userAgentData?: { platform: string } }
    ).userAgentData?.platform;
    if (platform) return platform.toLowerCase().includes("mac");
  }
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform);
}

export function usePlatform() {
  const isMac = useMemo(() => isMacOS(), []);
  const modifierKey = useMemo(() => (isMac ? "⌘" : "Ctrl+"), [isMac]);

  return { isMac, modifierKey };
}

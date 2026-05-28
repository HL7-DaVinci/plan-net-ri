import { useCallback, useMemo, useSyncExternalStore } from "react";

export type Theme = "light" | "dark" | "system";

const STORAGE_KEY = "theme";
const THEMES = ["light", "dark", "system"] as const;

interface ThemeState {
  theme: Theme;
  systemTheme: "light" | "dark";
}

const listeners = new Set<() => void>();

function getSystemTheme(): "light" | "dark" {
  if (
    typeof window === "undefined" ||
    typeof window.matchMedia !== "function"
  ) {
    return "light";
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light";
}

function getStoredTheme(): Theme {
  if (typeof window === "undefined") {
    return "system";
  }
  const stored = localStorage.getItem(STORAGE_KEY) as Theme | null;
  return stored || "system";
}

let state: ThemeState | null = null;
let initialized = false;

function getState(): ThemeState {
  if (state === null) {
    state = {
      theme: getStoredTheme(),
      systemTheme: getSystemTheme(),
    };
  }
  return state;
}

/**
 * Reset the theme store state - FOR TESTING ONLY
 * This allows tests to reset module-level state between test cases
 */
export function __resetThemeStore(): void {
  state = null;
  initialized = false;
  listeners.clear();
}

function emitChange(): void {
  for (const listener of listeners) {
    listener();
  }
}

function applyThemeToDocument(effectiveTheme: "light" | "dark"): void {
  if (typeof window === "undefined") return;
  const root = window.document.documentElement;
  root.classList.remove("light", "dark");
  root.classList.add(effectiveTheme);
}

function setTheme(newTheme: Theme): void {
  if (typeof window !== "undefined") {
    localStorage.setItem(STORAGE_KEY, newTheme);
  }
  const current = getState();
  state = { ...current, theme: newTheme };
  const effectiveTheme = newTheme === "system" ? current.systemTheme : newTheme;
  applyThemeToDocument(effectiveTheme);
  emitChange();
}

function setSystemTheme(newSystemTheme: "light" | "dark"): void {
  const current = getState();
  state = { ...current, systemTheme: newSystemTheme };
  if (current.theme === "system") {
    applyThemeToDocument(newSystemTheme);
  }
  emitChange();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot(): ThemeState {
  return getState();
}

function getServerSnapshot(): ThemeState {
  return { theme: "system", systemTheme: "light" };
}

function initializeThemeStore(): void {
  if (initialized || typeof window === "undefined") return;
  initialized = true;

  // Apply initial theme
  const current = getState();
  const effectiveTheme =
    current.theme === "system" ? current.systemTheme : current.theme;
  applyThemeToDocument(effectiveTheme);

  // Listen for system theme changes
  const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
  mediaQuery.addEventListener("change", (e) => {
    setSystemTheme(e.matches ? "dark" : "light");
  });
}

interface UseThemeResult {
  theme: Theme;
  effectiveTheme: "light" | "dark";
  setTheme: (theme: Theme) => void;
  themes: typeof THEMES;
}

export function useTheme(): UseThemeResult {
  initializeThemeStore();

  const currentState = useSyncExternalStore(
    subscribe,
    getSnapshot,
    getServerSnapshot,
  );

  const effectiveTheme = useMemo(
    () =>
      currentState.theme === "system"
        ? currentState.systemTheme
        : currentState.theme,
    [currentState.theme, currentState.systemTheme],
  );

  const stableSetTheme = useCallback((newTheme: Theme) => {
    setTheme(newTheme);
  }, []);

  return {
    theme: currentState.theme,
    effectiveTheme,
    setTheme: stableSetTheme,
    themes: THEMES,
  };
}

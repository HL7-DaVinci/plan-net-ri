import { useCallback, useState, useSyncExternalStore } from "react";
import {
  FHIR_SERVERS,
  type FhirServer,
  getServerByUrl,
  getStoredServerUrl,
  setStoredServerUrl,
} from "@/lib/fhir-config";

export interface UseFhirServerResult {
  serverUrl: string;
  server: FhirServer | undefined;
  presetServers: FhirServer[];
  setServerUrl: (url: string) => void;
  isCustomServer: boolean;
}

const serverUrlStore = {
  listeners: new Set<() => void>(),

  getSnapshot(): string {
    return getStoredServerUrl();
  },

  getServerSnapshot(): string {
    return FHIR_SERVERS[0]?.url ?? "";
  },

  subscribe(listener: () => void): () => void {
    serverUrlStore.listeners.add(listener);
    return () => serverUrlStore.listeners.delete(listener);
  },

  emit(): void {
    for (const listener of serverUrlStore.listeners) {
      listener();
    }
  },

  setServerUrl(url: string): void {
    setStoredServerUrl(url);
    serverUrlStore.emit();
  },
};

export function useFhirServer(): UseFhirServerResult {
  const serverUrl = useSyncExternalStore(
    serverUrlStore.subscribe,
    serverUrlStore.getSnapshot,
    serverUrlStore.getServerSnapshot,
  );

  const setServerUrl = useCallback((url: string) => {
    serverUrlStore.setServerUrl(url);
  }, []);

  const server = getServerByUrl(serverUrl);
  const isCustomServer = !server;

  return {
    serverUrl,
    server,
    presetServers: FHIR_SERVERS,
    setServerUrl,
    isCustomServer,
  };
}

export interface UseServerSelectionResult {
  customUrl: string;
  setCustomUrl: (url: string) => void;
  showCustomInput: boolean;
  isEditing: boolean;
  handleServerChange: (value: string) => void;
  handleCustomUrlSubmit: () => void;
}

export function useServerSelection(
  setServerUrl: (url: string) => void,
  isCustomServer: boolean,
  currentServerUrl: string,
): UseServerSelectionResult {
  const [customUrl, setCustomUrl] = useState("");
  const [showCustomInput, setShowCustomInput] = useState(false);
  const [isEditing, setIsEditing] = useState(false);

  const startEditing = useCallback(() => {
    if (isCustomServer && !isEditing) {
      setCustomUrl(currentServerUrl);
      setIsEditing(true);
    }
  }, [isCustomServer, isEditing, currentServerUrl]);

  const handleServerChange = useCallback(
    (value: string) => {
      if (value === "custom") {
        setShowCustomInput(true);
        setCustomUrl("");
        setIsEditing(false);
      } else {
        setShowCustomInput(false);
        setIsEditing(false);
        setServerUrl(value);
      }
    },
    [setServerUrl],
  );

  const handleCustomUrlSubmit = useCallback(() => {
    if (customUrl.trim()) {
      setServerUrl(customUrl.trim().replace(/\/+$/, ""));
      setShowCustomInput(false);
      setIsEditing(false);
      setCustomUrl("");
    }
  }, [customUrl, setServerUrl]);

  const showInput = showCustomInput || isCustomServer;

  return {
    customUrl: isCustomServer && !isEditing ? currentServerUrl : customUrl,
    setCustomUrl: (url: string) => {
      startEditing();
      setCustomUrl(url);
    },
    showCustomInput: showInput,
    isEditing: isEditing || showCustomInput,
    handleServerChange,
    handleCustomUrlSubmit,
  };
}

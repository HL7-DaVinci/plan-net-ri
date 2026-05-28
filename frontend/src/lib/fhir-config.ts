export interface FhirServer {
  name: string;
  url: string;
}

export interface CdsServer {
  name: string;
  url: string;
}

interface AppConfig {
  fhirServers?: FhirServer[];
  cdsServers?: CdsServer[];
}

declare global {
  interface Window {
    APP_CONFIG?: AppConfig;
  }
}

const DEFAULT_FHIR_SERVERS: FhirServer[] = [
  {
    name: "Local Server",
    url: "http://localhost:8080/fhir",
  },
];

function isValidFhirServer(server: unknown): server is FhirServer {
  return (
    typeof server === "object" &&
    server !== null &&
    typeof (server as FhirServer).name === "string" &&
    typeof (server as FhirServer).url === "string"
  );
}

function parseFhirServers(): FhirServer[] {
  if (
    window?.APP_CONFIG?.fhirServers &&
    Array.isArray(window.APP_CONFIG.fhirServers)
  ) {
    const servers = window.APP_CONFIG.fhirServers.filter(isValidFhirServer);
    if (servers.length > 0) {
      return servers;
    }
  }

  const envServers = import.meta.env.VITE_FHIR_SERVERS;
  if (envServers) {
    try {
      const parsed = JSON.parse(envServers);
      if (Array.isArray(parsed)) {
        const servers = parsed.filter(isValidFhirServer);
        if (servers.length > 0) {
          return servers;
        }
      }
    } catch {
      console.warn("Failed to parse VITE_FHIR_SERVERS, using defaults");
    }
  }

  return DEFAULT_FHIR_SERVERS;
}

export const FHIR_SERVERS: FhirServer[] = parseFhirServers();

const STORAGE_KEY = "fhir-server-url";

export function getStoredServerUrl(): string {
  if (typeof window === "undefined") {
    return FHIR_SERVERS[0].url;
  }
  return localStorage.getItem(STORAGE_KEY) || FHIR_SERVERS[0].url;
}

export function setStoredServerUrl(url: string): void {
  if (typeof window !== "undefined") {
    localStorage.setItem(STORAGE_KEY, url);
  }
}

export function getServerByUrl(url: string): FhirServer | undefined {
  return FHIR_SERVERS.find((server) => server.url === url);
}

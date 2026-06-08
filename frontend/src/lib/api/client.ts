import { getApiBaseUrl } from "@/lib/fhir-config";
import type {
  JobRequest,
  JobResponse,
  JobStats,
  ManifestJson,
  ManifestSummary,
  RunPage,
  RunTriggerResponse,
} from "./types";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${getApiBaseUrl()}${path}`, {
    headers: { "Content-Type": "application/json", ...init?.headers },
    ...init,
  });
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new ApiError(response.status, body || response.statusText);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const contentType = response.headers.get("content-type") ?? "";
  return (
    contentType.includes("json") ? await response.json() : await response.text()
  ) as T;
}

export const api = {
  listJobs: () => request<JobResponse[]>("/api/jobs"),
  getJob: (id: string) => request<JobResponse>(`/api/jobs/${id}`),
  createJob: (body: JobRequest) =>
    request<JobResponse>("/api/jobs", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  updateJob: (id: string, body: JobRequest) =>
    request<JobResponse>(`/api/jobs/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  deleteJob: (id: string) =>
    request<void>(`/api/jobs/${id}`, { method: "DELETE" }),
  runJob: (id: string) =>
    request<RunTriggerResponse>(`/api/jobs/${id}/run`, { method: "POST" }),
  pauseJob: (id: string) =>
    request<JobResponse>(`/api/jobs/${id}/pause`, { method: "POST" }),
  resumeJob: (id: string) =>
    request<JobResponse>(`/api/jobs/${id}/resume`, { method: "POST" }),
  listRuns: (jobId: string, page = 0, size = 25) =>
    request<RunPage>(
      `/api/runs?jobId=${encodeURIComponent(jobId)}&page=${page}&size=${size}`,
    ),
  getJobStats: (id: string) => request<JobStats>(`/api/jobs/${id}/stats`),
  listManifests: () => request<ManifestSummary[]>("/api/manifests"),
  getManifest: (id: string) =>
    request<ManifestJson>(`/api/manifests/${id}/manifest.json`),
  deleteManifest: (id: string) =>
    request<void>(`/api/manifests/${id}`, { method: "DELETE" }),
  manifestUrl: (id: string) =>
    `${getApiBaseUrl()}/api/manifests/${id}/manifest.json`,
};

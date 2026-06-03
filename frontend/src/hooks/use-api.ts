import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { ApiError, api } from "@/lib/api/client";
import type { JobRequest } from "@/lib/api/types";

const JOBS_KEY = ["api", "jobs"] as const;
const MANIFESTS_KEY = ["api", "manifests"] as const;

function describe(error: unknown): string {
  if (error instanceof ApiError) {
    return `HTTP ${error.status}: ${error.message}`;
  }
  return error instanceof Error ? error.message : String(error);
}

export function useJobs() {
  return useQuery({
    queryKey: JOBS_KEY,
    queryFn: api.listJobs,
    // Poll while any job is running so the "running" indicator clears on completion.
    refetchInterval: (query) =>
      query.state.data?.some((job) => job.running) ? 3000 : false,
  });
}

export function useRuns(
  jobId: string | undefined,
  page: number,
  size: number,
) {
  return useQuery({
    queryKey: ["api", "runs", jobId, page, size],
    queryFn: () => api.listRuns(jobId as string, page, size),
    enabled: Boolean(jobId),
    refetchInterval: 5000,
    // Keep the current page visible while the next one loads.
    placeholderData: keepPreviousData,
  });
}

export function useManifests() {
  return useQuery({
    queryKey: MANIFESTS_KEY,
    queryFn: api.listManifests,
    refetchInterval: 10000,
  });
}

export function useJobStats(jobId: string | undefined) {
  return useQuery({
    queryKey: ["api", "stats", jobId],
    queryFn: () => api.getJobStats(jobId as string),
    enabled: Boolean(jobId),
    refetchInterval: 5000,
  });
}

export function useDeleteManifest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteManifest(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MANIFESTS_KEY });
      toast.success("Manifest deleted");
    },
    onError: (error) =>
      toast.error("Delete failed", { description: describe(error) }),
  });
}

export function useCreateJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: JobRequest) => api.createJob(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: JOBS_KEY });
      toast.success("Crawl job created");
    },
    onError: (error) =>
      toast.error("Create failed", { description: describe(error) }),
  });
}

export function useUpdateJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: JobRequest }) =>
      api.updateJob(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: JOBS_KEY });
      toast.success("Crawl job updated");
    },
    onError: (error) =>
      toast.error("Update failed", { description: describe(error) }),
  });
}

export function useDeleteJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteJob(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: JOBS_KEY });
      toast.success("Crawl job deleted");
    },
    onError: (error) =>
      toast.error("Delete failed", { description: describe(error) }),
  });
}

export function useRunJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.runJob(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: JOBS_KEY });
      queryClient.invalidateQueries({ queryKey: MANIFESTS_KEY });
      toast.success("Crawl started");
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        toast.info("This job is already running");
        return;
      }
      toast.error("Could not start crawl", { description: describe(error) });
    },
  });
}

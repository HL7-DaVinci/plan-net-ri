import type { Organization } from "fhir/r4";
import { useCallback, useRef, useState } from "react";
import type { UseCrawlerResult } from "@/hooks/use-crawler";
import { countByServers, getCrawlRuns } from "@/lib/crawler/db";
import { summarizeLatestBatch } from "@/lib/crawler/summary";
import type { StoredResource } from "@/lib/crawler/types";

export interface DemoStepMeta {
  id: string;
  title: string;
  description: string;
  action: string;
}

const STEP_META: DemoStepMeta[] = [
  {
    id: "full-crawl",
    title: "Crawl the directory",
    description:
      "Pull every provider, organization, location, and plan from the server(s) into one local copy.",
    action: "Run full crawl",
  },
  {
    id: "update",
    title: "A provider updates their details",
    description:
      "We change one organization on the server, just like a real payer would when a provider's info changes.",
    action: "Update a provider",
  },
  {
    id: "incremental-update",
    title: "Re-crawl: only the change is pulled",
    description:
      "Using _lastUpdated, the crawler fetches just the changed record instead of the whole directory.",
    action: "Run incremental crawl",
  },
  {
    id: "delete",
    title: "A provider leaves the network",
    description:
      "We delete one record on the server. Deletions don't show up in a normal search.",
    action: "Remove a provider",
  },
  {
    id: "incremental-delete",
    title: "Re-crawl: the deletion is detected and verified",
    description:
      "Using system _history with _since, the crawler finds the deletion and removes it from the local copy.",
    action: "Run incremental crawl",
  },
];

export interface UseDemoWalkthroughResult {
  steps: DemoStepMeta[];
  currentStep: number;
  results: (string | null)[];
  runningStep: number | null;
  runStep: (index: number) => Promise<void>;
  reset: () => Promise<void>;
}

function pickTarget(
  resources: StoredResource[],
  excludeId?: string,
): StoredResource | undefined {
  return resources.find((r) => r.id !== excludeId) ?? resources[0];
}

export function useDemoWalkthrough(
  crawler: UseCrawlerResult,
): UseDemoWalkthroughResult {
  const [currentStep, setCurrentStep] = useState(0);
  const [results, setResults] = useState<(string | null)[]>(() =>
    STEP_META.map(() => null),
  );
  const [runningStep, setRunningStep] = useState<number | null>(null);
  const updatedIdRef = useRef<string | undefined>(undefined);

  const serverKeys = crawler.scope.map((s) => s.serverKey);

  const runStep = useCallback(
    async (index: number) => {
      if (runningStep !== null) return;
      if (index !== currentStep) return;
      setRunningStep(index);
      try {
        let message = "";
        const step = STEP_META[index];

        if (step.id === "full-crawl") {
          await crawler.startFullCrawl();
          const counts = await countByServers(serverKeys);
          message = `Full crawl complete: ${counts.total.toLocaleString()} resources aggregated across ${serverKeys.length} server(s).`;
        } else if (step.id === "update") {
          const orgs = await crawler.loadResources("Organization");
          const target = pickTarget(orgs);
          if (!target) {
            message = "No Organization found. Run the full crawl first.";
            setResults((prev) => withResult(prev, index, message));
            setRunningStep(null);
            return;
          }
          const next = structuredClone(target.resource) as Organization;
          const base = (next.name ?? "Provider").replace(
            / \(updated .*\)$/,
            "",
          );
          next.name = `${base} (updated ${new Date().toLocaleTimeString()})`;
          await crawler.mutateResource(target, next);
          updatedIdRef.current = target.id;
          message = `Updated Organization/${target.id} on ${target.serverLabel}.`;
        } else if (step.id === "incremental-update") {
          await crawler.startIncrementalCrawl();
          const summary = summarizeLatestBatch(await getCrawlRuns(serverKeys));
          message = summary
            ? `Incremental crawl pulled ${summary.records} changed record(s): ${summary.updated} updated, ${summary.added} added.`
            : "Incremental crawl complete.";
        } else if (step.id === "delete") {
          // Prefer leaf resources: the server enforces referential integrity,
          // so deleting a referenced resource (e.g. Organization) returns 409.
          let target: StoredResource | undefined;
          for (const leafType of [
            "PractitionerRole",
            "OrganizationAffiliation",
            "Endpoint",
          ]) {
            target = pickTarget(await crawler.loadResources(leafType));
            if (target) break;
          }
          if (!target) {
            message =
              "No resource available to delete. Run the full crawl first.";
            setResults((prev) => withResult(prev, index, message));
            setRunningStep(null);
            return;
          }
          await crawler.deleteResource(target);
          message = `Deleted ${target.resourceType}/${target.id} on ${target.serverLabel}.`;
        } else if (step.id === "incremental-delete") {
          await crawler.startIncrementalCrawl();
          const summary = summarizeLatestBatch(await getCrawlRuns(serverKeys));
          message = summary
            ? `Incremental crawl detected ${summary.deleted} deletion(s) via _history. Local directory verified accurate.`
            : "Incremental crawl complete.";
        }

        setResults((prev) => withResult(prev, index, message));
        setCurrentStep(index + 1);
      } finally {
        setRunningStep(null);
      }
    },
    [crawler, currentStep, runningStep, serverKeys],
  );

  const reset = useCallback(async () => {
    await crawler.clearStore();
    updatedIdRef.current = undefined;
    setResults(STEP_META.map(() => null));
    setCurrentStep(0);
  }, [crawler]);

  return {
    steps: STEP_META,
    currentStep,
    results,
    runningStep,
    runStep,
    reset,
  };
}

function withResult(
  prev: (string | null)[],
  index: number,
  message: string,
): (string | null)[] {
  const next = [...prev];
  next[index] = message;
  return next;
}

import type { CopilotRouteResult } from "./contract";

export type CopilotMemoryMutation = {
  key: string;
  memoryId: string;
  status: "ACTIVE" | "REVOKED";
};

const activeMemoryStatuses = new Set(["ACTIVE", "SAVED", "PERSISTED", "DUPLICATE_ACTIVE", "DUPLICATE_REPLAY"]);

function normalizedMemoryStatus(value: unknown): CopilotMemoryMutation["status"] | undefined {
  if (typeof value !== "string") return undefined;
  const status = value.trim().toUpperCase();
  if (status === "REVOKED") return "REVOKED";
  return activeMemoryStatuses.has(status) ? "ACTIVE" : undefined;
}

/** A stable identity for one confirmed memory mutation in one turn. */
export function memoryMutation(memoryId: unknown, status: unknown): CopilotMemoryMutation | undefined {
  if (typeof memoryId !== "string" || !/^[\w.-]{1,160}$/u.test(memoryId)) return undefined;
  const normalized = normalizedMemoryStatus(status);
  return normalized ? { key: `${memoryId}:${normalized}`, memoryId, status: normalized } : undefined;
}

export function routeMemoryMutation(route: CopilotRouteResult | undefined): CopilotMemoryMutation | undefined {
  if (!route || route.resolutionStatus !== "RESOLVED") return undefined;
  if (route.intent !== "MEMORY_WRITE_REQUEST" && route.intent !== "MEMORY_FORGET_REQUEST") return undefined;
  return memoryMutation(route.memoryId, route.memoryStatus);
}

import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import type { AgentContext, AgentCreateResponse, AgentRun } from "./contract";

export type AgentMode = "mock" | "real";
export const agentMode: AgentMode = import.meta.env.VITE_AGENT_MODE === "real" ? "real" : "mock";

export class AgentRuntimeUnavailableError extends Error {
  constructor(message = "Agent Runtime 暂不可用") { super(message); this.name = "AgentRuntimeUnavailableError"; }
}

const auth = () => ({ Authorization: `Bearer ${permanence.token.useToken()}` });

export async function createAgentRun(goal: string, context: AgentContext, mode: AgentMode = agentMode): Promise<AgentCreateResponse> {
  try {
    const result = await req.post<{ goal: string; context: AgentContext; mode: AgentMode }, AgentCreateResponse>("/api/agent/runs", { goal, context, mode }, auth());
    if (!result?.runId) throw new AgentRuntimeUnavailableError();
    return result;
  } catch (error) {
    if (error instanceof AgentRuntimeUnavailableError) throw error;
    throw new AgentRuntimeUnavailableError();
  }
}

export async function getAgentRun(runId: string): Promise<AgentRun> {
  try { return await req.get<AgentRun>(`/api/agent/runs/${encodeURIComponent(runId)}`, auth()); }
  catch { throw new AgentRuntimeUnavailableError(); }
}

export async function approveAgentAction(runId: string, actionId: string): Promise<AgentRun> {
  try { return await req.post<Record<string, never>, AgentRun>(`/api/agent/runs/${encodeURIComponent(runId)}/actions/${encodeURIComponent(actionId)}/approve`, {}, auth()); }
  catch { throw new AgentRuntimeUnavailableError(); }
}

export async function rejectAgentAction(runId: string, actionId: string): Promise<AgentRun> {
  try { return await req.post<Record<string, never>, AgentRun>(`/api/agent/runs/${encodeURIComponent(runId)}/actions/${encodeURIComponent(actionId)}/reject`, {}, auth()); }
  catch { throw new AgentRuntimeUnavailableError(); }
}

export async function cancelAgentRun(runId: string): Promise<AgentRun> {
  try { return await req.post<Record<string, never>, AgentRun>(`/api/agent/runs/${encodeURIComponent(runId)}/cancel`, {}, auth()); }
  catch { throw new AgentRuntimeUnavailableError(); }
}

export async function pollAgentRun(runId: string, onRun: (run: AgentRun) => void, intervalMs = 1200): Promise<AgentRun> {
  let run = await getAgentRun(runId); onRun(run);
  while (!["COMPLETED", "FAILED", "CANCELLED", "WAITING_FOR_APPROVAL"].includes(run.status)) {
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    run = await getAgentRun(runId); onRun(run);
  }
  return run;
}

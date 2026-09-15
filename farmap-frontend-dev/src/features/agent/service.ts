import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import { readServerSentEvents, requestUrl, type ServerSentEvent } from "@/utils/sse";
import type { AgentContext, AgentCreateResponse, AgentRun } from "./contract";

export type AgentMode = "mock" | "real";
export type AgentRunProvenance = { diagnosisId?: string; recommendationId?: string; intent?: string };
// Formal pages are production-first. Set VITE_AGENT_MODE=mock only for an
// intentional local/demo fallback.
export const agentMode: AgentMode = import.meta.env.VITE_AGENT_MODE === "mock" ? "mock" : "real";

export class AgentRuntimeUnavailableError extends Error {
  constructor(message = "Agent Runtime 暂不可用") { super(message); this.name = "AgentRuntimeUnavailableError"; }
}

const auth = () => ({ Authorization: `Bearer ${permanence.token.useToken()}` });

export async function createAgentRun(goal: string, context?: AgentContext, mode: AgentMode = agentMode, provenance: AgentRunProvenance = {}): Promise<AgentCreateResponse> {
  try {
    const result = await req.post<{ goal: string; context?: AgentContext; mode: AgentMode; diagnosisId?: string; recommendationId?: string; intent?: string }, AgentCreateResponse>("/api/agent/runs", { goal, context, mode, ...provenance }, auth());
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
  const startedAt = Date.now();
  const maxDurationMs = 15 * 60 * 1000;
  const maxConsecutiveErrors = 5;
  let delayMs = intervalMs;
  let consecutiveErrors = 0;
  let run: AgentRun;

  const read = async (): Promise<AgentRun> => {
    try {
      const next = await getAgentRun(runId);
      consecutiveErrors = 0;
      delayMs = intervalMs;
      onRun(next);
      return next;
    } catch (error: unknown) {
      consecutiveErrors += 1;
      if (consecutiveErrors >= maxConsecutiveErrors) throw error;
      delayMs = Math.min(10_000, Math.max(intervalMs, delayMs * 2));
      throw new RetryableAgentPollingError();
    }
  };

  while (true) {
    if (Date.now() - startedAt > maxDurationMs) throw new AgentRuntimeUnavailableError("Agent 运行时间过长，任务仍保留在服务器；请刷新后继续查看。");
    try {
      run = await read();
      break;
    } catch (error: unknown) {
      if (!(error instanceof RetryableAgentPollingError)) throw error;
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
  while (!["COMPLETED", "FAILED", "CANCELLED", "WAITING_FOR_APPROVAL"].includes(run.status)) {
    if (Date.now() - startedAt > maxDurationMs) throw new AgentRuntimeUnavailableError("Agent 运行时间过长，任务仍保留在服务器；请刷新后继续查看。");
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    try {
      run = await read();
    } catch (error: unknown) {
      if (!(error instanceof RetryableAgentPollingError)) throw error;
    }
  }
  return run;
}

export type AgentEventStreamResult = { completed: boolean; status?: string };

/**
 * Subscribes to the live Agent trace. The caller can fall back to
 * pollAgentRun when a proxy or server closes the stream before `done`.
 */
export async function streamAgentEvents(
  runId: string,
  onEvent: (event: ServerSentEvent) => void,
  signal?: AbortSignal,
): Promise<AgentEventStreamResult> {
  let completed = false;
  let status: string | undefined;
  const response = await fetch(requestUrl(`/api/agent/runs/${encodeURIComponent(runId)}/events`), {
    method: "GET",
    headers: { Accept: "text/event-stream", ...auth() },
    signal,
  });
  if (!response.ok) throw new AgentRuntimeUnavailableError(`Agent 事件流连接失败（${response.status}）。`);
  await readServerSentEvents(response, (event) => {
    if (event.event === "done") {
      completed = true;
      if (event.data && typeof event.data === "object") status = String((event.data as Record<string, unknown>).status ?? "");
    }
    onEvent(event);
  });
  return { completed, status };
}

class RetryableAgentPollingError extends Error {
  constructor() {
    super("retryable agent polling failure");
  }
}

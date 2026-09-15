import { permanence } from "@/utils/permanence";
import { HttpRequestError, req } from "@/utils/reqeust";
import { readServerSentEvents, requestUrl, type ServerSentEvent } from "@/utils/sse";
import { toCopilotContext, type AIContext } from "@/store/aiContext";
import type { OverviewCopilotContext } from "@/features/overview/copilot/context";
import type { CopilotChatRequest, CopilotConversationView, CopilotMemoryView, CopilotPageContext, CopilotRouteRequest, CopilotRouteResult } from "./contract";

const auth = () => ({ Authorization: `Bearer ${permanence.token.useToken()}` });
const sessionKey = "farmap.copilot.session-id";
const conversationKey = "farmap.copilot.conversation-id";
const versionKey = "farmap.copilot.context-version";

function getSessionId(): string {
  if (typeof window === "undefined") return "copilot-browser-session";
  const existing = window.sessionStorage.getItem(sessionKey);
  if (existing) return existing;
  const created = typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `copilot-${Date.now()}`;
  window.sessionStorage.setItem(sessionKey, created);
  return created;
}

export function getCopilotConversationId(): string | undefined {
  if (typeof window === "undefined") return undefined;
  return window.sessionStorage.getItem(conversationKey) || undefined;
}

function getContextVersion(): number | undefined {
  if (typeof window === "undefined") return undefined;
  const value = window.sessionStorage.getItem(versionKey);
  return value ? Number(value) : undefined;
}

function rememberConversation(result: { conversationId?: string; contextVersion?: number }): void {
  if (typeof window === "undefined") return;
  if (result.conversationId) window.sessionStorage.setItem(conversationKey, result.conversationId);
  if (result.contextVersion !== undefined) window.sessionStorage.setItem(versionKey, String(result.contextVersion));
}

function positiveFarmId(value: number | undefined): string | undefined {
  return value !== undefined && value > 0 ? String(value) : undefined;
}

/** Sends only stable page IDs. Weather, evidence, titles and client state never become routing authority. */
export function toCopilotPageContext(context: AIContext, discussion?: OverviewCopilotContext): CopilotPageContext {
  const canonical = toCopilotContext(context);
  return {
    route: canonical.pageKey,
    farmId: positiveFarmId(canonical.farmId),
    fieldId: canonical.fieldId,
    diagnosisId: canonical.diagnosisId || discussion?.diagnosis?.id,
    recommendationId: context.selectedDiagnosis?.sourceRecommendationId || discussion?.recommendation?.id,
    taskId: canonical.taskId || discussion?.operation?.taskId,
    runId: canonical.agentRunId || discussion?.agent?.runId,
  };
}

export function createCopilotRequest(message: string, context: AIContext, discussion?: OverviewCopilotContext): CopilotChatRequest {
  return {
    message,
    sessionId: getSessionId(),
    conversationId: getCopilotConversationId(),
    clientMessageId: typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `client-${Date.now()}`,
    contextVersion: getContextVersion(),
    pageContext: toCopilotPageContext(context, discussion),
  };
}

export async function routeCopilot(message: string, context: AIContext, discussion?: OverviewCopilotContext): Promise<CopilotRouteResult> {
  const createBody = (): CopilotRouteRequest => createCopilotRequest(message, context, discussion);
  try {
    const result = await req.post<CopilotRouteRequest, CopilotRouteResult>("/api/copilot/route", createBody(), auth());
    rememberConversation(result);
    return result;
  } catch (error: unknown) {
    // Browser tabs can outlive the server-side short-term conversation or
    // carry an older context version. Recover once with a clean context.
    if (!(error instanceof HttpRequestError) || ![404, 409].includes(error.status)) throw error;
    clearCopilotConversation();
    const result = await req.post<CopilotRouteRequest, CopilotRouteResult>("/api/copilot/route", createBody(), auth());
    rememberConversation(result);
    return result;
  }
}

export async function streamCopilotChat(
  request: CopilotChatRequest,
  onEvent: (event: ServerSentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(requestUrl("/api/copilot/chat"), {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream", ...auth() },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok) {
    let message = `Copilot 对话连接失败（${response.status}）。`;
    try {
      const body = await response.json() as { message?: string; msg?: string };
      message = body.message || body.msg || message;
    } catch { /* retain status message */ }
    throw new Error(message);
  }
  await readServerSentEvents(response, (event) => {
    if (event.event === "route" && event.data && typeof event.data === "object") {
      rememberConversation(event.data as { conversationId?: string; contextVersion?: number });
    }
    onEvent(event);
  });
}

export async function loadCopilotConversation(): Promise<CopilotConversationView | undefined> {
  const conversationId = getCopilotConversationId();
  if (!conversationId) return undefined;
  try {
    const view = await req.get<CopilotConversationView>(`/api/copilot/conversations/${encodeURIComponent(conversationId)}`, auth());
    rememberConversation(view);
    return view;
  } catch (error: unknown) {
    if (error instanceof HttpRequestError && [404, 409].includes(error.status)) {
      clearCopilotConversation();
      return undefined;
    }
    throw error;
  }
}

export async function createCopilotConversation(): Promise<CopilotConversationView> {
  const view = await req.post<Record<string, never>, CopilotConversationView>("/api/copilot/conversations", {}, auth());
  rememberConversation(view);
  return view;
}

export function clearCopilotConversation(): void {
  if (typeof window === "undefined") return;
  window.sessionStorage.removeItem(conversationKey);
  window.sessionStorage.removeItem(versionKey);
}

export async function listCopilotMemories(context: AIContext): Promise<CopilotMemoryView[]> {
  const page = toCopilotPageContext(context);
  const query = new URLSearchParams();
  if (page.farmId) query.set("farmId", page.farmId);
  if (page.fieldId) query.set("fieldId", page.fieldId);
  return req.get<CopilotMemoryView[]>(`/api/copilot/memories${query.toString() ? `?${query.toString()}` : ""}`, auth());
}

export async function revokeCopilotMemory(memoryId: string): Promise<CopilotMemoryView> {
  return req.post<Record<string, never>, CopilotMemoryView>(`/api/copilot/memories/${encodeURIComponent(memoryId)}/revoke`, {}, auth());
}

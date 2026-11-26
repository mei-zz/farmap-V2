import { useCallback, useEffect, useRef, useState } from "react";
import type { ServerSentEvent } from "@/utils/sse";
import { applyChatActivity, type ChatActivity } from "./chatActivity";
import { hasVisibleMessageParts, mergeMessagePart, messagePartText, parseMessagePart, parseMessageParts, type MessagePart } from "./messageParts";
import { mergePresentation, parsePresentation, updatePresentationBlocks, type MessagePresentation } from "./presentation";
import { abortCopilotAgent, controlCopilotAgent, getCopilotConversationId, loadCopilotConversation, streamCopilotChat } from "./service";
import { parseUiBlock, parseUiBlocks, uiId, type AgentUiBlock } from "./uiBlocks";
import type { CopilotChatRequest, CopilotResponseMetadata, CopilotRouteResult } from "./contract";

export type CopilotStreamPhase = "idle" | "streaming" | "completed" | "failed" | "aborted";

export type CopilotWorkspaceTarget = {
  runId: string;
  farmId: number;
  fieldId?: string;
  view?: "history" | "ranking";
};

export type CopilotAgentTarget = {
  runId: string;
  diagnosisId?: string;
  status?: string;
};

export type CopilotReferenceImage = {
  refId: string;
  label: string;
  imageUrl: string;
  match?: string;
};

export type CopilotStreamSnapshot = {
  requestId?: string;
  phase: CopilotStreamPhase;
  parts: MessagePart[];
  text: string;
  legacyText: string;
  blocks: AgentUiBlock[];
  presentation?: MessagePresentation;
  activity?: ChatActivity;
  route?: CopilotRouteResult;
  responseMeta?: CopilotResponseMetadata;
  referenceImages: CopilotReferenceImage[];
  referenceImageNotice?: string;
  workspace?: CopilotWorkspaceTarget;
  workspacePending: boolean;
  agentRun?: CopilotAgentTarget;
  statusMessage?: string;
  clarification?: string;
  error?: string;
  doneStatus?: string;
  terminal: boolean;
  eventCount: number;
  timedOut: boolean;
  startedAt?: number;
  finishedAt?: number;
};

export type CopilotControlTarget = { conversationId: string; contextVersion: number };

type CopilotStreamOptions = {
  requestId: string;
  timeoutMs?: number;
  onUpdate?: (snapshot: CopilotStreamSnapshot) => void;
  onTimeout?: () => void;
} & ({ request: CopilotChatRequest; continuation?: never } | { request?: never; continuation: CopilotControlTarget });

const emptySnapshot = (): CopilotStreamSnapshot => ({
  phase: "idle",
  parts: [],
  text: "",
  legacyText: "",
  blocks: [],
  referenceImages: [],
  workspacePending: false,
  terminal: false,
  eventCount: 0,
  timedOut: false,
});

function record(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function messageText(snapshot: CopilotStreamSnapshot): string {
  return snapshot.presentation?.summary ?? (messagePartText(snapshot.parts) || snapshot.legacyText);
}

function withText(snapshot: CopilotStreamSnapshot): CopilotStreamSnapshot {
  return { ...snapshot, text: messageText(snapshot) };
}

function hasAnswer(snapshot: CopilotStreamSnapshot): boolean {
  return Boolean(messageText(snapshot).trim() || snapshot.blocks.length || hasVisibleMessageParts(snapshot.parts));
}

function protocolActivity(activity: ChatActivity | undefined, part: ReturnType<typeof parseMessagePart>): ChatActivity | undefined {
  if (part?.type !== "tool_call") return activity;
  const event = part.status === "done" ? "tool.completed" : part.status === "failed" ? "tool.failed" : "tool.started";
  return applyChatActivity(activity, event, { toolName: part.toolName, status: part.status });
}

function keyedBlockPart(value: Record<string, unknown>): MessagePart | undefined {
  const block = parseUiBlock(value.block ?? value);
  const rawKey = typeof value.blockKey === "string" && /^[\w.:-]{1,180}$/u.test(value.blockKey) ? value.blockKey : undefined;
  const blockKey = rawKey ?? block?.blockId;
  if (!block || !blockKey) return undefined;
  return { type: "ui_block", blockKey, block };
}

function upsertBlock(blocks: AgentUiBlock[], block: AgentUiBlock): AgentUiBlock[] {
  const index = blocks.findIndex((item) => item.blockId === block.blockId);
  return index < 0 ? [...blocks, block] : blocks.map((item, position) => position === index ? block : item);
}

function removeBlockPart(parts: MessagePart[], value: Record<string, unknown>): MessagePart[] {
  const blockId = uiId(value.blockId);
  const block = parseUiBlock(value.block ?? value);
  const blockKey = typeof value.blockKey === "string" ? value.blockKey : undefined;
  if (!blockId && !block?.blockId && !blockKey) return parts;
  return parts.filter((part) => part.type !== "ui_block"
    || (part.block.blockId !== blockId && part.block.blockId !== block?.blockId && part.blockKey !== blockKey));
}

const privateEventPattern = /^(?:reasoning|thoughts?|chain[_-]?of[_-]?thought|private[._-]?reasoning)(?:\.|$)/iu;

function applyEvent(snapshot: CopilotStreamSnapshot, event: ServerSentEvent): CopilotStreamSnapshot {
  if (privateEventPattern.test(event.event)) return snapshot;
  const data = record(event.data) ?? {};
  let next: CopilotStreamSnapshot = { ...snapshot, eventCount: snapshot.eventCount + 1 };

  if (event.event === "message.part") {
    const raw = data.part ?? data;
    const part = parseMessagePart(raw);
    if (!part) return withText(next);
    next = { ...next, parts: mergeMessagePart(snapshot.parts, raw), activity: protocolActivity(snapshot.activity, part) };
    if (part.type === "ui_block") {
      next.blocks = upsertBlock(snapshot.blocks, part.block);
    } else if (part.type === "follow_ups") {
      next.presentation = { ...snapshot.presentation, followUps: part.items };
    }
    return withText(next);
  }

  if (event.event === "content.delta") {
    if (typeof data.delta !== "string" || !data.delta) return next;
    next.legacyText = (snapshot.legacyText + data.delta).slice(0, 16_000);
    return withText(next);
  }

  if (event.event === "message.delta") return next;

  if (event.event.startsWith("presentation.")) {
    next.presentation = mergePresentation(snapshot.presentation, event.event, data);
    if (event.event === "presentation.followups" && Array.isArray(data.items)) {
      next.parts = mergeMessagePart(snapshot.parts, { type: "follow_ups", items: data.items });
    }
    return withText(next);
  }

  if (event.event === "route") {
    next.route = data as CopilotRouteResult;
    return withText(next);
  }

  if (event.event === "response.meta") {
    next.responseMeta = data as CopilotResponseMetadata;
    return withText(next);
  }

  if (event.event === "ui.block.created" || event.event === "ui.block.updated" || event.event === "ui.block.removed") {
    next.blocks = updatePresentationBlocks(snapshot.blocks, event.event, data);
    if (event.event === "ui.block.removed") {
      next.parts = removeBlockPart(snapshot.parts, data);
    } else {
      const part = keyedBlockPart(data);
      if (part) next.parts = mergeMessagePart(snapshot.parts, part);
    }
    return withText(next);
  }

  if (event.event === "ui.block.upserted") {
    const part = keyedBlockPart(data);
    if (part?.type === "ui_block") {
      next = {
        ...next,
        parts: mergeMessagePart(snapshot.parts, part),
        blocks: upsertBlock(snapshot.blocks, part.block),
      };
    }
    return withText(next);
  }

  if (event.event === "status") {
    next.statusMessage = typeof data.message === "string" ? data.message : snapshot.statusMessage;
    return withText(next);
  }

  if (event.event === "knowledge") {
    next.activity = applyChatActivity(snapshot.activity, event.event, data);
    return withText(next);
  }

  if (event.event === "agent.runtime" || event.event.startsWith("tool.") || event.event.startsWith("tool_execution_") || event.event.startsWith("web.search.")) {
    next.activity = applyChatActivity(snapshot.activity, event.event, data);
    return withText(next);
  }

  if (event.event === "analysis.workspace.pending") {
    next.workspacePending = true;
    return withText(next);
  }

  if (event.event === "analysis.workspace") {
    const runId = uiId(data.runId);
    const farmId = Number(data.farmId);
    if (runId && Number.isSafeInteger(farmId) && farmId > 0) {
      const view = data.view === "history" || data.view === "ranking" ? data.view : undefined;
      next.workspace = { runId, farmId, fieldId: uiId(data.fieldId), view };
      next.workspacePending = false;
    }
    return withText(next);
  }

  if (event.event === "run.created") {
    const runId = uiId(data.runId);
    if (runId) next.agentRun = { runId, diagnosisId: uiId(data.diagnosisId), status: typeof data.status === "string" ? data.status : undefined };
    return withText(next);
  }

  if (event.event === "diagnosis.job") {
    next.statusMessage = typeof data.message === "string" ? data.message : snapshot.statusMessage;
    return withText(next);
  }

  if (event.event === "knowledge.evidence") {
    const raw = {
      id: `evidence-${String(data.evidenceId ?? "")}`,
      kind: "evidence",
      evidenceId: data.evidenceId,
      title: data.title,
      summary: data.summary,
      source: data.source,
    };
    next.parts = mergeMessagePart(snapshot.parts, raw);
    return withText(next);
  }

  if (event.event === "reference.images") {
    const items = Array.isArray(data.items) ? data.items : [];
    next.referenceImages = items.flatMap((item) => {
      const image = record(item);
      const refId = uiId(image?.refId);
      const label = typeof image?.label === "string" ? image.label : undefined;
      const imageUrl = typeof image?.imageUrl === "string" ? image.imageUrl : undefined;
      return refId && label && imageUrl ? [{ refId, label, imageUrl, match: typeof image?.match === "string" ? image.match : undefined }] : [];
    });
    next.referenceImageNotice = typeof data.message === "string" && data.message.trim() ? data.message : undefined;
    return withText(next);
  }

  if (event.event === "error") {
    next.error = typeof data.message === "string" && data.message.trim() ? data.message : "Copilot 暂时不可用，请稍后重试。";
    return withText(next);
  }

  if (event.event === "done") {
    const doneStatus = typeof data.status === "string" ? data.status : "COMPLETED";
    const phase: CopilotStreamPhase = doneStatus === "ABORTED" ? "aborted" : doneStatus === "FAILED" || doneStatus === "REJECTED" ? "failed" : "completed";
    next = {
      ...next,
      phase,
      terminal: true,
      doneStatus,
      finishedAt: Date.now(),
      error: phase === "failed" ? snapshot.error ?? "生成未完成，请稍后重试。" : snapshot.error,
    };
    if (phase === "completed" && !hasAnswer(next) && next.route?.clarificationQuestion) next.clarification = next.route.clarificationQuestion;
    return withText(next);
  }

  return withText(next);
}

function finishWithoutTerminal(snapshot: CopilotStreamSnapshot): CopilotStreamSnapshot {
  if (snapshot.terminal) {
    const clarification = !hasAnswer(snapshot) && snapshot.route?.clarificationQuestion ? snapshot.route.clarificationQuestion : snapshot.clarification;
    return withText({ ...snapshot, clarification });
  }
  return withText({
    ...snapshot,
    phase: "failed",
    terminal: true,
    finishedAt: Date.now(),
    error: snapshot.error ?? "连接已结束，服务端完成状态尚未确认。",
  });
}

async function recoverStoredReply(snapshot: CopilotStreamSnapshot): Promise<CopilotStreamSnapshot | undefined> {
  const route = snapshot.route;
  if (snapshot.error || !route?.traceId || !route.conversationId || getCopilotConversationId() !== route.conversationId) return undefined;
  try {
    const conversation = await loadCopilotConversation();
    if (conversation?.conversationId !== route.conversationId) return undefined;
    const turns = conversation.historyTurns ?? conversation.recentTurns;
    const stored = [...turns].reverse().find((turn) => turn.role === "ASSISTANT" && turn.traceId === route.traceId);
    if (!stored?.message) return undefined;
    return withText({ ...snapshot, phase: "completed", terminal: true, doneStatus: "COMPLETED", finishedAt: Date.now(),
      legacyText: stored.message, parts: parseMessageParts(stored.responseMetadata?.messageParts),
      blocks: parseUiBlocks(stored.responseMetadata?.uiBlocks), presentation: parsePresentation(stored.responseMetadata?.presentation) });
  } catch { return undefined; }
}

function finishAborted(snapshot: CopilotStreamSnapshot): CopilotStreamSnapshot {
  return withText({
    ...snapshot,
    phase: "aborted",
    terminal: true,
    doneStatus: snapshot.doneStatus ?? "ABORTED",
    finishedAt: Date.now(),
  });
}

function finishFailed(snapshot: CopilotStreamSnapshot, error: unknown): CopilotStreamSnapshot {
  const message = error instanceof Error ? error.message : "Copilot 流式连接中断。";
  return withText({
    ...snapshot,
    phase: "failed",
    terminal: true,
    finishedAt: Date.now(),
    error: snapshot.error ?? message,
  });
}

/**
 * Owns the ordered Copilot SSE state machine. Legacy events remain available
 * as fallback data, but once `message.part` arrives it is the canonical order
 * for text, tools, activity, blocks and evidence.
 */
export function useCopilotStream() {
  const [snapshot, setSnapshot] = useState<CopilotStreamSnapshot>(emptySnapshot);
  const snapshotRef = useRef(snapshot);
  const controllerRef = useRef<AbortController | undefined>(undefined);
  const generationRef = useRef(0);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      generationRef.current += 1;
      controllerRef.current?.abort();
      controllerRef.current = undefined;
    };
  }, []);

  const reset = useCallback(() => {
    generationRef.current += 1;
    controllerRef.current?.abort();
    controllerRef.current = undefined;
    const next = emptySnapshot();
    snapshotRef.current = next;
    if (mountedRef.current) setSnapshot(next);
  }, []);

  const stop = useCallback((target?: CopilotControlTarget) => {
    if (target) void abortCopilotAgent(target.conversationId, target.contextVersion).catch(() => undefined);
    controllerRef.current?.abort();
  }, []);

  const enqueue = useCallback(async (target: CopilotControlTarget, message: string, operation: "followUp" | "steer" = "followUp") => {
    let accepted = false;
    let failed = false;
    await controlCopilotAgent(target.conversationId, target.contextVersion, operation, (event) => {
      if (event.event === "error") failed = true;
      if (event.event === "done") accepted = record(event.data)?.status === "COMPLETED";
    }, message);
    return accepted && !failed;
  }, []);

  const start = useCallback(async (options: CopilotStreamOptions): Promise<CopilotStreamSnapshot> => {
    generationRef.current += 1;
    const generation = generationRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    let timedOut = false;
    let timer: number | undefined;
    const initial: CopilotStreamSnapshot = {
      ...emptySnapshot(),
      requestId: options.requestId,
      phase: "streaming",
      startedAt: Date.now(),
    };
    snapshotRef.current = initial;
    if (mountedRef.current) setSnapshot(initial);

    const commit = (next: CopilotStreamSnapshot) => {
      if (!mountedRef.current || generationRef.current !== generation) return;
      snapshotRef.current = next;
      setSnapshot(next);
      options.onUpdate?.(next);
    };

    try {
      if (options.timeoutMs !== undefined) {
        timer = window.setTimeout(() => {
          timedOut = true;
          options.onTimeout?.();
          controller.abort();
        }, options.timeoutMs);
      }
      const onEvent = (event: ServerSentEvent) => {
        if (!mountedRef.current || generationRef.current !== generation) return;
        const next = applyEvent(snapshotRef.current, event);
        if (next !== snapshotRef.current) commit(next);
      };
      if (options.continuation) {
        await controlCopilotAgent(options.continuation.conversationId, options.continuation.contextVersion,
          "continue", onEvent, undefined, controller.signal);
      } else {
        await streamCopilotChat(options.request, onEvent, controller.signal);
      }
      if (generationRef.current !== generation) return snapshotRef.current;
      const restored = !timedOut && !snapshotRef.current.terminal ? await recoverStoredReply(snapshotRef.current) : undefined;
      if (generationRef.current !== generation) return snapshotRef.current;
      const final = timedOut
        ? finishFailed({ ...snapshotRef.current, timedOut: true }, new Error("本次请求超时，已自动停止。"))
        : restored ?? finishWithoutTerminal(snapshotRef.current);
      commit(final);
      return final;
    } catch (error: unknown) {
      if (generationRef.current !== generation) return snapshotRef.current;
      const aborted = controller.signal.aborted && !timedOut;
      const restored = !timedOut && !aborted ? await recoverStoredReply(snapshotRef.current) : undefined;
      if (generationRef.current !== generation) return snapshotRef.current;
      const final = timedOut
        ? { ...finishFailed(snapshotRef.current, new Error("本次请求超时，已自动停止。")), timedOut: true }
        : aborted ? finishAborted(snapshotRef.current) : restored ?? finishFailed(snapshotRef.current, error);
      commit(final);
      return final;
    } finally {
      if (timer !== undefined) window.clearTimeout(timer);
      if (controllerRef.current === controller) controllerRef.current = undefined;
    }
  }, []);

  return { snapshot, start, stop, reset, enqueue };
}

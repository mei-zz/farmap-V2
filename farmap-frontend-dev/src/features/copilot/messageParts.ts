import { parseUiBlock, type AgentUiBlock } from "./uiBlocks";

export type MessageToolStatus = "running" | "done" | "failed";
export type MessageThinkingStatus = "running" | "done";
export type CitationModality = "knowledge" | "camera" | "historical_case" | "weather" | "field" | "atlas";

export type CitationRef = {
  index: number;
  evidenceId: string;
  modality: CitationModality;
  title: string;
  sourceName: string;
  stageLabel?: string;
  snippet?: string;
  imageUrl?: string;
  documentName?: string;
  expertStatus?: string;
  score?: number;
};

export type MessageThinkingPart = {
  type: "thinking";
  id: string;
  status: MessageThinkingStatus;
  /** Public progress label. Private reasoning content is never mapped here. */
  label?: string;
};

export type MessageToolCallPart = {
  type: "tool_call";
  callId: string;
  toolName: string;
  label: string;
  status: MessageToolStatus;
  summary?: string;
};

export type MessageTextPart = {
  type: "text";
  id: string;
  markdown: string;
  citations?: CitationRef[];
};

export type MessageUiBlockPart = {
  type: "ui_block";
  blockKey: string;
  block: AgentUiBlock;
  citations?: CitationRef[];
};

export type MessageFollowUpsPart = {
  type: "follow_ups";
  items: { label: string; prompt: string }[];
};

export type MessagePart =
  | MessageThinkingPart
  | MessageToolCallPart
  | MessageTextPart
  | MessageUiBlockPart
  | MessageFollowUpsPart;

const MAX_PARTS = 160;
const MAX_TEXT = 16_000;
const MAX_CITATIONS = 96;
const partIdPattern = /^[\w.:-]{1,180}$/u;
const toolNamePattern = /^[a-zA-Z0-9_.-]{1,100}$/u;
const evidenceIdPattern = /^E[1-9][0-9]{0,3}$/u;
const privatePartTypes = new Set([
  "reasoning", "reasoning_delta", "reasoning.delta", "private_reasoning", "thought", "thoughts",
  "chain_of_thought", "chain-of-thought", "cot", "analysis", "analysis_delta",
]);
const citationModalities = new Set<CitationModality>(["knowledge", "camera", "historical_case", "weather", "field", "atlas"]);
const legacyTypeMap: Record<string, MessagePart["type"] | "evidence"> = {
  tool: "tool_call",
  activity: "thinking",
  block: "ui_block",
  evidence: "evidence",
  followups: "follow_ups",
  follow_ups: "follow_ups",
};

function record(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function text(value: unknown, limit: number, allowWhitespace = false): string | undefined {
  if (typeof value !== "string") return undefined;
  if (!allowWhitespace && !value.trim()) return undefined;
  return value.slice(0, limit);
}

function key(value: unknown): string | undefined {
  return typeof value === "string" && partIdPattern.test(value) ? value : undefined;
}

function unwrapPart(value: unknown): Record<string, unknown> | undefined {
  const input = record(value);
  return record(input?.part) ?? input;
}

function normalizeStatus(value: unknown): MessageToolStatus | undefined {
  if (typeof value !== "string") return undefined;
  switch (value.toLowerCase()) {
    case "running":
    case "started":
    case "pending":
    case "updated":
      return "running";
    case "done":
    case "completed":
    case "complete":
    case "success":
      return "done";
    case "failed":
    case "error":
      return "failed";
    default:
      return undefined;
  }
}

function thinkingStatus(value: unknown): MessageThinkingStatus | undefined {
  const status = normalizeStatus(value);
  return status === "failed" ? undefined : status;
}

function citationModality(value: unknown): CitationModality {
  return typeof value === "string" && citationModalities.has(value as CitationModality)
    ? value as CitationModality : "knowledge";
}

function validImageUrl(value: unknown): string | undefined {
  const candidate = text(value, 2_000);
  if (!candidate) return undefined;
  return /^(?:https?:\/\/|\/(?!\/))/iu.test(candidate) && !/[\u0000-\u001f]/u.test(candidate) ? candidate : undefined;
}

function parseCitation(value: unknown, position = 0): CitationRef | undefined {
  const input = record(value);
  if (!input) return undefined;
  const evidenceId = text(input.evidenceId, 16);
  if (!evidenceId || !evidenceIdPattern.test(evidenceId)) return undefined;
  const title = text(input.title, 180) ?? text(input.documentName, 180);
  if (!title) return undefined;
  const rawIndex = typeof input.index === "number" && Number.isSafeInteger(input.index)
    ? input.index : Number.parseInt(evidenceId.slice(1), 10);
  const index = rawIndex >= 1 && rawIndex <= 9_999 ? rawIndex : position + 1;
  const score = typeof input.score === "number" && Number.isFinite(input.score) && input.score >= 0 && input.score <= 1
    ? input.score : undefined;
  return {
    index,
    evidenceId,
    modality: citationModality(input.modality),
    title,
    sourceName: text(input.sourceName, 160) ?? text(input.source, 160) ?? "FarMap",
    stageLabel: text(input.stageLabel, 80),
    snippet: text(input.snippet, 4_000) ?? text(input.summary, 4_000) ?? text(input.content, 4_000),
    imageUrl: validImageUrl(input.imageUrl),
    documentName: text(input.documentName, 180),
    expertStatus: text(input.expertStatus, 80),
    score,
  };
}

function parseCitations(value: unknown): CitationRef[] {
  if (!Array.isArray(value)) return [];
  const citations = new Map<string, CitationRef>();
  for (const [position, entry] of value.slice(0, MAX_CITATIONS).entries()) {
    const citation = parseCitation(entry, position);
    if (citation) citations.set(citation.evidenceId, citation);
  }
  return [...citations.values()];
}

function mergeCitations(previous: CitationRef[] | undefined, next: CitationRef[] | undefined): CitationRef[] | undefined {
  if (!previous?.length && !next?.length) return undefined;
  const merged = new Map<string, CitationRef>();
  for (const citation of previous ?? []) merged.set(citation.evidenceId, citation);
  for (const citation of next ?? []) merged.set(citation.evidenceId, citation);
  const result = [...merged.values()].sort((left, right) => left.index - right.index || left.evidenceId.localeCompare(right.evidenceId));
  return result.length ? result : undefined;
}

function legacyEvidenceCitation(input: Record<string, unknown>): CitationRef | undefined {
  const evidenceId = text(input.evidenceId, 16);
  const title = text(input.title, 180);
  if (!evidenceId || !evidenceIdPattern.test(evidenceId) || !title) return undefined;
  return parseCitation({
    ...input,
    evidenceId,
    title,
    sourceName: input.sourceName ?? input.source,
  });
}

/**
 * Parses the public ordered presentation protocol. The canonical discriminator
 * is `type`; the former `kind` shape is normalized here before it reaches state.
 * Reasoning and other private chain-of-thought payloads are always dropped.
 */
export function parseMessagePart(value: unknown): MessagePart | undefined {
  const input = unwrapPart(value);
  if (!input) return undefined;
  const rawType = typeof input.type === "string" ? input.type
    : typeof input.kind === "string" ? input.kind : "";
  const normalizedType = legacyTypeMap[rawType] ?? rawType;
  if (!normalizedType || privatePartTypes.has(rawType.toLowerCase()) || privatePartTypes.has(normalizedType.toLowerCase())) {
    return undefined;
  }

  if (normalizedType === "text") {
    const id = key(input.id);
    const citations = parseCitations(input.citations);
    const markdown = text(input.markdown ?? input.text ?? input.delta, MAX_TEXT, true) ?? "";
    if (!id || (!markdown && !citations.length)) return undefined;
    return { type: "text", id, markdown, ...(citations.length ? { citations } : {}) };
  }

  if (normalizedType === "thinking") {
    const id = key(input.id);
    const status = thinkingStatus(input.status);
    if (!id || !status) return undefined;
    return { type: "thinking", id, status, label: text(input.label ?? (rawType === "activity" ? input.text : undefined), 300) };
  }

  if (normalizedType === "tool_call") {
    const toolName = text(input.toolName, 100);
    const status = normalizeStatus(input.status);
    const callId = key(input.callId ?? input.toolCallId ?? input.id ?? toolName);
    if (!callId || !toolName || !toolNamePattern.test(toolName) || !status) return undefined;
    return {
      type: "tool_call",
      callId,
      toolName,
      label: text(input.label, 100) ?? toolName,
      status,
      summary: text(input.summary, 800),
    };
  }

  if (normalizedType === "ui_block") {
    const block = parseUiBlock(input.block ?? input);
    const blockKey = key(input.blockKey) ?? block?.blockId;
    if (!block || !blockKey) return undefined;
    const citations = parseCitations(input.citations);
    return { type: "ui_block", blockKey, block, ...(citations.length ? { citations } : {}) };
  }

  if (normalizedType === "follow_ups") {
    const rawItems = Array.isArray(input.items) ? input.items
      : Array.isArray(input.followUps) ? input.followUps : [];
    const items = rawItems.slice(0, 6).flatMap((entry) => {
      const item = record(entry);
      const label = text(item?.label, 80);
      const prompt = text(item?.prompt, 600);
      return label && prompt ? [{ label, prompt }] : [];
    });
    return items.length ? { type: "follow_ups", items } : undefined;
  }

  if (normalizedType === "evidence") {
    const citation = legacyEvidenceCitation(input);
    const id = key(input.id) ?? (citation ? `legacy-evidence-${citation.evidenceId}` : undefined);
    return citation && id ? { type: "text", id, markdown: "", citations: [citation] } : undefined;
  }

  return undefined;
}

export function parseMessageParts(value: unknown): MessagePart[] {
  if (!Array.isArray(value)) return [];
  const parts: MessagePart[] = [];
  for (const entry of value.slice(0, MAX_PARTS)) {
    const part = parseMessagePart(entry);
    if (part) parts.push(part);
  }
  return parts;
}

function appendPart(parts: MessagePart[], part: MessagePart): MessagePart[] {
  const next = [...parts, part];
  return next.length > MAX_PARTS ? next.slice(next.length - MAX_PARTS) : next;
}

/**
 * Applies a live protocol part without changing the position of an existing id.
 * Text deltas append to their id; snapshot markdown replaces it. UI blocks are
 * keyed by blockKey and replace immediately in place.
 */
export function mergeMessagePart(parts: MessagePart[], value: unknown): MessagePart[] {
  const raw = unwrapPart(value);
  const parsed = parseMessagePart(raw);
  if (!parsed) return parts;

  if (parsed.type === "text") {
    const index = parts.findIndex((part) => part.type === "text" && part.id === parsed.id);
    if (index < 0) return appendPart(parts, parsed);
    const current = parts[index] as MessageTextPart;
    if (typeof raw?.delta === "string") {
      const delta = raw.delta.slice(0, MAX_TEXT);
      if (!delta) return parts;
      const next: MessageTextPart = {
        ...current,
        markdown: (current.markdown + delta).slice(0, MAX_TEXT),
        citations: mergeCitations(current.citations, parsed.citations),
      };
      return parts.map((part, position) => position === index ? next : part);
    }
    const next: MessageTextPart = {
      ...current,
      markdown: parsed.markdown || current.markdown,
      citations: mergeCitations(current.citations, parsed.citations),
    };
    return parts.map((part, position) => position === index ? next : part);
  }

  if (parsed.type === "ui_block") {
    const index = parts.findIndex((part) => part.type === "ui_block"
      && (part.blockKey === parsed.blockKey || part.block.blockId === parsed.block.blockId));
    return index < 0
      ? appendPart(parts, parsed)
      : parts.map((part, position) => position === index ? parsed : part);
  }

  if (parsed.type === "follow_ups") {
    const index = parts.findLastIndex((part) => part.type === "follow_ups");
    return index < 0
      ? appendPart(parts, parsed)
      : parts.map((part, position) => position === index ? parsed : part);
  }

  const index = parts.findIndex((part) => part.type === parsed.type
    && (part.type === "thinking" ? part.id === parsed.id : part.type === "tool_call" ? part.callId === parsed.callId : false));
  return index < 0
    ? appendPart(parts, parsed)
    : parts.map((part, position) => position === index ? parsed : part);
}

export function messagePartText(parts: MessagePart[]): string {
  return parts.flatMap((part) => part.type === "text" ? [part.markdown] : []).join("").slice(0, MAX_TEXT);
}

export function messagePartCitations(parts: MessagePart[]): Map<string, CitationRef> {
  const citations = new Map<string, CitationRef>();
  for (const part of parts) {
    if (part.type !== "text" && part.type !== "ui_block") continue;
    for (const citation of part.citations ?? []) citations.set(citation.evidenceId, citation);
  }
  return citations;
}

export function messagePartFollowUps(parts: MessagePart[]): { label: string; prompt: string }[] {
  const part = parts.findLast((candidate) => candidate.type === "follow_ups");
  return part?.type === "follow_ups" ? part.items : [];
}

export function hasVisibleMessageParts(parts: MessagePart[]): boolean {
  return parts.some((part) => part.type !== "text" || Boolean(part.markdown.trim()));
}

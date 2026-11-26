import { parseUiBlock, uiId, type AgentUiBlock } from "./uiBlocks";

export type PresentationFollowUp = { label: string; prompt: string };
export type MessagePresentation = { summary?: string; analysis?: string; followUps?: PresentationFollowUp[] };

function asObject(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function asText(value: unknown, limit: number): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, limit) : undefined;
}

export function parsePresentation(value: unknown): MessagePresentation | undefined {
  const data = asObject(value);
  if (!data) return undefined;
  const summary = asText(data.summary, 2000);
  const analysis = asText(data.analysis, 8000);
  const followUps = Array.isArray(data.followUps) ? data.followUps.slice(0, 4).flatMap((item) => {
    const row = asObject(item);
    const label = asText(row?.label, 48);
    const prompt = asText(row?.prompt, 400);
    return label && prompt ? [{ label, prompt }] : [];
  }) : [];
  return summary || analysis || followUps.length ? { summary, analysis, followUps } : undefined;
}

export function mergePresentation(previous: MessagePresentation | undefined, event: string, value: unknown): MessagePresentation | undefined {
  const data = asObject(value);
  if (event === "presentation.summary") {
    const summary = asText(data?.text, 2000);
    return summary && !previous?.summary ? { ...previous, summary } : previous;
  }
  if (event === "presentation.analysis") {
    const analysis = asText(data?.text, 8000);
    return analysis ? { ...previous, analysis } : previous;
  }
  if (event === "presentation.analysis.delta" && typeof data?.delta === "string" && data.delta) {
    return { ...previous, analysis: ((previous?.analysis ?? "") + data.delta).slice(0, 8000) };
  }
  if (event === "presentation.followups") {
    const items = parsePresentation({ followUps: data?.items })?.followUps;
    return items?.length ? { ...previous, followUps: items } : previous;
  }
  return previous;
}

export const isExecutionBlock = (block: AgentUiBlock) => block.componentType === "FarmInspectionProgress";

/** Preserve the first position of each block even when its status or renderer changes. */
export function updatePresentationBlocks(blocks: AgentUiBlock[], event: string, value: unknown): AgentUiBlock[] {
  const data = asObject(value);
  const next = parseUiBlock(data?.block ?? data);
  const id = next?.blockId ?? uiId(data?.blockId);
  if (!id) return blocks;
  if (event === "ui.block.removed") return blocks.filter((block) => block.blockId !== id);
  if (!next || next.componentType === "KnowledgeSourceCard" || (next.componentType === "EvidenceCard" && next.title === "知识依据")) return blocks;
  return blocks.some((block) => block.blockId === id) ? blocks.map((block) => block.blockId === id ? next : block) : [...blocks, next];
}

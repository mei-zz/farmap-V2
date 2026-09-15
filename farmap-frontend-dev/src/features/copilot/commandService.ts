import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import { loadAgriculturalContext } from "@/features/agricultural/context";
import { getDiagnosis, listDiagnosisFields, type DiagnosisField } from "@/features/diagnosis/service";
import { searchKnowledge } from "@/features/knowledge-expert/service";
import type { OperationsTaskRaw } from "@/types/operations";

type ApiEnvelope<T> = { data?: T; msg?: string };

export type CommandPriorityItem = {
  id: string;
  kind: "DIAGNOSIS" | "OPERATION";
  priority: "HIGH" | "MEDIUM" | "LOW";
  title: string;
  summary: string;
  fieldId?: string;
  fieldName?: string;
  crop?: string;
  status?: string;
  updatedAt?: string;
  path: string;
};

export type CommandPrioritiesResult = {
  status: "READY" | "EMPTY" | "UNAVAILABLE";
  reason?: string;
  items: CommandPriorityItem[];
  sources: { diagnoses: string; operations: string };
};

export type HistoricalCaseItem = {
  caseId?: string;
  similarityScore?: number;
  vectorDistance?: number;
  treeSpecies?: string;
  diseaseType?: string;
  pestType?: string;
  nutritionStatus?: string;
  updatedAt?: string;
  expertStatus?: string;
};

export type HistoricalCaseSearchResult = {
  status: "READY" | "EMPTY" | "IMAGE_REQUIRED" | "UNAVAILABLE";
  reason?: string;
  provider?: string;
  model?: string;
  count?: number;
  items: HistoricalCaseItem[];
};

export type CommandEvidenceItem = {
  label: string;
  value: string;
  source?: string;
};

export type CommandInsightResult = {
  status: "READY" | "EMPTY" | "UNAVAILABLE";
  reason?: string;
  title: string;
  summary?: string;
  evidence: CommandEvidenceItem[];
  sources: string[];
  updatedAt?: string;
};

export type CommandKnowledgeItem = {
  title: string;
  source?: string;
  content?: string;
  score?: number;
  chunkId?: string;
};

export type CommandKnowledgeResult = {
  status: "READY" | "EMPTY" | "UNAVAILABLE";
  reason?: string;
  provider?: string;
  items: CommandKnowledgeItem[];
};

export type CommandDiagnosisResult = {
  status: "READY" | "EMPTY" | "UNAVAILABLE";
  reason?: string;
  diagnosisId?: string;
  title?: string;
  riskLevel?: string;
  confidence?: number;
  summary?: string;
  evidence: CommandEvidenceItem[];
  sources: string[];
  updatedAt?: string;
};

export type CopilotAnswerResult = {
  answer: string;
  model?: string;
  latencyMs?: number;
  grounded: boolean;
  available?: boolean;
  providerStatus?: string;
  errorType?: string;
  evidenceCount?: number;
  modelTier?: "FAST" | "STANDARD";
};

function auth() {
  return { Authorization: `Bearer ${permanence.token.useToken()}` };
}

function fieldName(fields: DiagnosisField[], fieldId: string | undefined): string | undefined {
  if (!fieldId) return undefined;
  return fields.find((field) => field.fieldId === fieldId)?.name ?? fieldId;
}

function fieldCrop(fields: DiagnosisField[], fieldId: string | undefined): string | undefined {
  if (!fieldId) return undefined;
  return fields.find((field) => field.fieldId === fieldId)?.crop;
}

function diagnosisPriority(value: string | undefined): CommandPriorityItem["priority"] | undefined {
  const normalized = value?.toLowerCase();
  return normalized === "high" || normalized === "critical" ? "HIGH" : normalized === "medium" ? "MEDIUM" : undefined;
}

function operationPriority(value: unknown): CommandPriorityItem["priority"] {
  const normalized = String(value ?? "").toUpperCase();
  return normalized === "HIGH" || normalized === "CRITICAL" ? "HIGH" : normalized === "MEDIUM" ? "MEDIUM" : "LOW";
}

function priorityWeight(value: CommandPriorityItem["priority"]): number {
  return value === "HIGH" ? 3 : value === "MEDIUM" ? 2 : 1;
}

function formatDiagnosisSummary(summary: string | undefined, riskLevel: string | undefined): string {
  if (summary?.trim()) return summary;
  return riskLevel?.toLowerCase() === "high" ? "近期诊断标记为高风险，需要优先复核。" : "近期诊断标记为中风险，建议安排复查。";
}

export async function loadCopilotPriorities(farmId: number): Promise<CommandPrioritiesResult> {
  if (!Number.isFinite(farmId) || farmId <= 0) {
    return { status: "UNAVAILABLE", reason: "当前没有可访问的真实农场上下文。", items: [], sources: { diagnoses: "UNAVAILABLE", operations: "UNAVAILABLE" } };
  }
  const token = permanence.token.useToken();
  const [fieldsResult, agriculturalResult, operationsResult] = await Promise.allSettled([
    listDiagnosisFields(farmId),
    loadAgriculturalContext(farmId, token, { diagnosisLimit: 20 }),
    req.get<ApiEnvelope<{ items?: OperationsTaskRaw[] }>>("/api/operations/tasks", auth()),
  ]);
  const fields = fieldsResult.status === "fulfilled" ? fieldsResult.value : [];
  const items: CommandPriorityItem[] = [];
  const sources = {
    diagnoses: agriculturalResult.status === "fulfilled" ? agriculturalResult.value.recentDiagnoses.status : "UNAVAILABLE",
    operations: operationsResult.status === "fulfilled" && Array.isArray(operationsResult.value.data?.items) ? "READY" : "UNAVAILABLE",
  };

  if (agriculturalResult.status === "fulfilled") {
    for (const diagnosis of agriculturalResult.value.recentDiagnoses.items) {
      const priority = diagnosisPriority(diagnosis.riskLevel);
      if (!priority) continue;
      items.push({
        id: diagnosis.diagnosisId,
        kind: "DIAGNOSIS",
        priority,
        title: `${fieldName(fields, diagnosis.fieldId)}${diagnosis.crop ? ` · ${diagnosis.crop}` : ""}`,
        summary: formatDiagnosisSummary(diagnosis.summary, diagnosis.riskLevel),
        fieldId: diagnosis.fieldId,
        fieldName: fieldName(fields, diagnosis.fieldId),
        crop: diagnosis.crop ?? fieldCrop(fields, diagnosis.fieldId),
        status: diagnosis.status,
        updatedAt: diagnosis.createdAt,
        path: `/diagnosis/${encodeURIComponent(diagnosis.diagnosisId)}`,
      });
    }
  }

  if (operationsResult.status === "fulfilled") {
    for (const [index, task] of (operationsResult.value.data?.items ?? []).entries()) {
      const status = String(task.status ?? "").toUpperCase();
      if (["COMPLETED", "CANCELLED", "CLOSED"].includes(status)) continue;
      const taskId = typeof task.taskId === "string" ? task.taskId : `operation-${index}`;
      const fieldId = typeof task.fieldId === "string" ? task.fieldId : undefined;
      // Operations currently exposes owner scope but not farmId. Only include
      // tasks linked to a field owned by the selected farm; unlinked tasks
      // must not leak into another farm's Command Bar.
      if (!fieldId || !fields.some((field) => field.fieldId === fieldId)) continue;
      items.push({
        id: taskId,
        kind: "OPERATION",
        priority: operationPriority(task.priority),
        title: typeof task.title === "string" && task.title.trim() ? task.title : "待处理农事任务",
        summary: `${status === "IN_PROGRESS" ? "任务进行中" : "任务等待处理"}${fieldId ? ` · ${fieldName(fields, fieldId)}` : ""}`,
        fieldId,
        fieldName: fieldName(fields, fieldId),
        crop: fieldCrop(fields, fieldId),
        status,
        updatedAt: typeof task.updatedAt === "string" ? task.updatedAt : typeof task.createdAt === "string" ? task.createdAt : undefined,
        path: "/operations/tasks",
      });
    }
  }

  items.sort((left, right) => priorityWeight(right.priority) - priorityWeight(left.priority) || (right.updatedAt ?? "").localeCompare(left.updatedAt ?? ""));
  const limited = items.slice(0, 3);
  const hasAnySource = sources.diagnoses !== "UNAVAILABLE" || sources.operations !== "UNAVAILABLE";
  return {
    status: limited.length ? "READY" : hasAnySource ? "EMPTY" : "UNAVAILABLE",
    reason: limited.length ? undefined : hasAnySource ? "当前农场没有可验证的高/中风险诊断或未完成任务。" : "优先事项所需的诊断和 Operations 数据暂不可用。",
    items: limited,
    sources,
  };
}

export async function searchHistoricalCases(imageUrls: string[], topK = 3): Promise<HistoricalCaseSearchResult> {
  const response = await req.post<{ imageUrls: string[]; topK: number }, ApiEnvelope<HistoricalCaseSearchResult>>("/api/historical-cases/search", { imageUrls, topK }, auth());
  if (!response.data) throw new Error(response.msg || "历史病例检索不可用");
  return { ...response.data, items: Array.isArray(response.data.items) ? response.data.items : [] };
}

function weatherEvidence(context: Awaited<ReturnType<typeof loadAgriculturalContext>>): CommandEvidenceItem {
  const weather = context.currentWeather;
  if (weather.status !== "READY") {
    return { label: "当前天气", value: weather.reason || "天气数据不可用", source: weather.source || weather.provider || "Weather" };
  }
  const values = [weather.condition, weather.temperature, weather.humidity ? `湿度 ${weather.humidity}` : undefined].filter(Boolean);
  return { label: "当前天气", value: values.join(" · ") || "已接入但未返回可读天气值", source: weather.source || weather.provider || "Weather" };
}

function diagnosisEvidence(context: Awaited<ReturnType<typeof loadAgriculturalContext>>): CommandEvidenceItem[] {
  return context.recentDiagnoses.items.slice(0, 3).map((item) => ({
    label: item.fieldId ? `诊断 · ${item.fieldId}` : "最近诊断",
    value: [item.riskLevel, item.summary].filter(Boolean).join(" · ") || "已保存诊断，摘要暂未返回",
    source: item.diagnosisId,
  }));
}

function activeTaskCount(tasks: OperationsTaskRaw[]): number {
  return tasks.filter((task) => !["COMPLETED", "CANCELLED", "CLOSED"].includes(String(task.status ?? "").toUpperCase())).length;
}

export async function loadCopilotFarmStatus(farmId: number): Promise<CommandInsightResult> {
  if (!Number.isFinite(farmId) || farmId <= 0) {
    return { status: "UNAVAILABLE", title: "当前农场状态", reason: "当前没有可访问的真实农场上下文。", evidence: [], sources: [] };
  }
  const token = permanence.token.useToken();
  const [fieldsResult, contextResult, operationsResult] = await Promise.allSettled([
    listDiagnosisFields(farmId),
    loadAgriculturalContext(farmId, token, { diagnosisLimit: 5 }),
    req.get<ApiEnvelope<{ items?: OperationsTaskRaw[] }>>("/api/operations/tasks", auth()),
  ]);
  const fields = fieldsResult.status === "fulfilled" ? fieldsResult.value : [];
  const agricultural = contextResult.status === "fulfilled" ? contextResult.value : undefined;
  const tasks = operationsResult.status === "fulfilled" && Array.isArray(operationsResult.value.data?.items) ? operationsResult.value.data.items : [];
  const sources = [
    fieldsResult.status === "fulfilled" ? "Field Context" : undefined,
    agricultural ? "Weather" : undefined,
    agricultural ? "Diagnosis" : undefined,
    operationsResult.status === "fulfilled" ? "Operations" : undefined,
  ].filter((source): source is string => Boolean(source));
  const evidence: CommandEvidenceItem[] = [];
  if (fieldsResult.status === "fulfilled") evidence.push({ label: "地块", value: `当前农场已接入 ${fields.length} 个地块`, source: "Field Context" });
  if (agricultural) {
    evidence.push(weatherEvidence(agricultural));
    evidence.push({ label: "近期诊断", value: `${agricultural.recentDiagnoses.count} 条`, source: agricultural.recentDiagnoses.source || "Diagnosis" });
  }
  if (operationsResult.status === "fulfilled") evidence.push({ label: "未完成任务", value: `${activeTaskCount(tasks)} 项`, source: "Operations" });
  if (!sources.length) return { status: "UNAVAILABLE", title: "当前农场状态", reason: "农场、天气、诊断和 Operations 数据均暂不可用。", evidence: [], sources: [] };
  const hasSignals = fields.length > 0 || Boolean(agricultural?.recentDiagnoses.count) || activeTaskCount(tasks) > 0 || agricultural?.currentWeather.status === "READY";
  return {
    status: hasSignals ? "READY" : "EMPTY",
    title: "当前农场状态",
    summary: hasSignals ? "已读取当前农场的地块、天气、诊断与待处理任务上下文。" : "当前农场上下文已返回，但暂未发现可验证的农情信号。",
    reason: hasSignals ? undefined : "当前没有返回可验证的地块、天气、诊断或未完成任务信号。",
    evidence,
    sources,
    updatedAt: agricultural?.generatedAt,
  };
}

export async function loadCopilotFieldStatus(farmId: number, fieldId: string): Promise<CommandInsightResult> {
  if (!Number.isFinite(farmId) || farmId <= 0 || !fieldId) {
    return { status: "UNAVAILABLE", title: "当前地块状态", reason: "当前没有可访问的真实地块上下文。", evidence: [], sources: [] };
  }
  const token = permanence.token.useToken();
  const [fieldsResult, contextResult] = await Promise.allSettled([
    listDiagnosisFields(farmId),
    loadAgriculturalContext(farmId, token, { fieldId, diagnosisLimit: 5 }),
  ]);
  const fields = fieldsResult.status === "fulfilled" ? fieldsResult.value : [];
  const field = fields.find((item) => item.fieldId === fieldId);
  const agricultural = contextResult.status === "fulfilled" ? contextResult.value : undefined;
  if (!field && !agricultural) return { status: "UNAVAILABLE", title: `地块 ${fieldId} 状态`, reason: "地块和农业上下文均暂不可用。", evidence: [], sources: [] };
  const evidence: CommandEvidenceItem[] = [];
  if (field) {
    evidence.push({ label: "地块", value: [field.name, field.crop, field.growthStage, field.area !== undefined ? `${field.area} ha` : undefined].filter(Boolean).join(" · "), source: "Field Context" });
  }
  if (agricultural) {
    evidence.push(weatherEvidence(agricultural));
    evidence.push(...diagnosisEvidence(agricultural));
  }
  const sources = [field ? "Field Context" : undefined, agricultural ? "Weather" : undefined, agricultural ? "Diagnosis" : undefined].filter((source): source is string => Boolean(source));
  const hasDiagnosis = Boolean(agricultural?.recentDiagnoses.items.length);
  return {
    status: hasDiagnosis || Boolean(field) ? "READY" : "EMPTY",
    title: `${field?.name || fieldId} · 当前地块状态`,
    summary: hasDiagnosis ? (agricultural?.recentDiagnoses.items[0]?.summary || "当前地块已有近期诊断记录，请结合下方 Evidence 查看。") : "地块上下文可用，但当前没有返回近期诊断信号。",
    reason: hasDiagnosis ? undefined : "当前地块没有可验证的近期诊断记录。",
    evidence,
    sources,
    updatedAt: agricultural?.generatedAt,
  };
}

export async function searchCopilotKnowledge(query: string, topK = 4): Promise<CommandKnowledgeResult> {
  try {
    const response = await searchKnowledge(query, topK);
    const items = (response.results ?? []).map((item) => ({
      title: item.documentTitle || item.sourceName || "未命名知识片段",
      source: item.sourceName || item.documentTitle,
      content: item.content,
      score: item.rerankScore ?? item.score ?? item.retrievalScore ?? undefined,
      chunkId: item.chunkId,
    }));
    return {
      status: items.length ? "READY" : "EMPTY",
      reason: items.length ? undefined : "知识库没有返回与当前问题匹配的片段。",
      provider: response.retrievalProvider,
      items,
    };
  } catch (error: unknown) {
    return { status: "UNAVAILABLE", reason: error instanceof Error ? error.message : "知识检索接口暂不可用。", items: [] };
  }
}

export async function loadCopilotDiagnosis(farmId: number, fieldId?: string, diagnosisId?: string): Promise<CommandDiagnosisResult> {
  try {
    let selectedId = diagnosisId;
    let summaryItem: Awaited<ReturnType<typeof loadAgriculturalContext>>["recentDiagnoses"]["items"][number] | undefined;
    if (!selectedId) {
      const context = await loadAgriculturalContext(farmId, permanence.token.useToken(), { fieldId, diagnosisLimit: 5 });
      summaryItem = context.recentDiagnoses.items[0];
      selectedId = summaryItem?.diagnosisId;
    }
    if (!selectedId) return { status: "EMPTY", reason: "当前对象没有已保存的诊断记录。", evidence: [], sources: [] };
    try {
      const diagnosis = await getDiagnosis(selectedId);
      const evidence = (diagnosis.evidence ?? []).slice(0, 5).map((item) => ({
        label: item.title || item.modality,
        value: item.summary || "Evidence 已保存，但摘要暂未返回",
        source: item.source?.name || item.source?.document || item.modality,
      }));
      return {
        status: "READY",
        diagnosisId: selectedId,
        title: diagnosis.diagnosis.title,
        riskLevel: diagnosis.diagnosis.risk_level,
        confidence: diagnosis.diagnosis.confidence,
        summary: diagnosis.diagnosis.summary,
        evidence,
        sources: Array.from(new Set((diagnosis.evidence ?? []).map((item) => item.source?.name || item.modality).filter(Boolean))),
        updatedAt: diagnosis.updatedAt || diagnosis.createdAt,
      };
    } catch {
      if (!summaryItem) throw new Error("诊断详情暂不可用");
      return {
        status: "READY",
        diagnosisId: selectedId,
        title: "最近一次诊断",
        riskLevel: summaryItem.riskLevel,
        confidence: summaryItem.confidence,
        summary: summaryItem.summary || "诊断已保存，但详细 Evidence 暂不可用。",
        evidence: [{ label: "诊断记录", value: "已保存诊断摘要；详细 Evidence 暂不可用", source: "Diagnosis" }],
        sources: ["Diagnosis"],
        updatedAt: summaryItem.createdAt,
      };
    }
  } catch (error: unknown) {
    return { status: "UNAVAILABLE", reason: error instanceof Error ? error.message : "诊断数据暂不可用。", evidence: [], sources: [] };
  }
}

/**
 * Generate a short grounded answer after the read-only command has returned.
 * The command result is rendered first; this call is deliberately best-effort
 * so an unavailable model can never make the Copilot look stuck.
 */
export async function generateCopilotAnswer(input: {
  message: string;
  intent: string;
  conversationId?: string;
  traceId?: string;
  context: Record<string, unknown>;
  evidence: unknown;
}): Promise<CopilotAnswerResult> {
  const response = await req.post<typeof input, ApiEnvelope<CopilotAnswerResult>>("/api/copilot/answer", input, auth());
  if (!response.data?.answer) throw new Error(response.msg || "Copilot 自然语言总结暂不可用");
  return response.data;
}

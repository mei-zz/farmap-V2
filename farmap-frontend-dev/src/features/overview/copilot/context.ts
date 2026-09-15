import type { AIContext } from "@/store/aiContext";
import type { OverviewMode } from "@/types/overview";

export type OverviewCopilotContextType = "FARM" | "DECISION" | "DIAGNOSIS" | "AGENT" | "RISK" | "RECOMMENDATION" | "OPERATION";

export type OverviewCopilotEvidence = {
  labels: string[];
  count?: number;
  historyUnavailable?: boolean;
};

export type OverviewCopilotContext = {
  contextType: OverviewCopilotContextType;
  mode: OverviewMode;
  page: string;
  farm: { id: number; name: string };
  field?: { id: string; name: string; crop?: string; area?: number };
  weather?: {
    summary: string;
    temperature: string;
    precipitation?: string;
    updatedAt: string;
    rainfall14d?: number;
    rainfallForecast24h?: number;
    rainfallProbability24h?: number;
    rainfallForecast?: boolean;
    rainfallForecastDays?: number;
    forecast?: Array<{
      date: string;
      condition?: string;
      temperatureMax?: string;
      temperatureMin?: string;
      precipitationMm?: number;
      precipitationProbability?: number;
      weatherCode?: number;
    }>;
  };
  phenology?: { stage: string; progress: number };
  title: string;
  summary?: string;
  risk?: {
    id?: string;
    title: string;
    priority?: string;
    confidenceLabel?: string;
    affectedArea?: string;
  };
  diagnosis?: {
    id?: string;
    title: string;
    severity?: string;
    confidence?: number;
  };
  recommendation?: {
    id?: string;
    title: string;
    recommendedAction?: string;
    impact?: string;
    decisionState?: string;
    relatedRiskId?: string;
  };
  operation?: {
    taskId?: string;
    title: string;
    status: string;
    source?: string;
  };
  agent?: {
    runId?: string;
    status: string;
    finding?: string;
    proposedAction?: string;
    approvalState?: string;
  };
  evidence: OverviewCopilotEvidence;
  freshness?: { updatedAt?: string; stale?: boolean };
};

const evidenceSourceLabels: Record<string, string> = {
  Camera: "监控图像",
  Weather: "气象",
  GIS: "空间数据",
  Phenology: "物候",
  Knowledge: "农业知识",
  History: "历史案例",
  "Historical Case": "历史案例",
  历史案例: "历史案例",
};

const historicalSources = new Set(["History", "Historical Case", "历史案例"]);

export function normalizeOverviewEvidenceSources(sources: string[] | undefined, mode: OverviewMode): OverviewCopilotEvidence {
  const rawSources = sources ?? [];
  if (mode === "UNAVAILABLE") return { labels: [], historyUnavailable: rawSources.length > 0 };
  const availableSources = mode === "DEMO" ? rawSources : rawSources.filter((source) => !historicalSources.has(source));
  const labels = availableSources.map((source) => historicalSources.has(source)
    ? "历史案例（演示）"
    : evidenceSourceLabels[source] ?? source);

  return {
    labels,
    historyUnavailable: mode !== "DEMO" && availableSources.length !== rawSources.length,
  };
}

const contextTypeLabels: Record<OverviewCopilotContextType, string> = {
  FARM: "农场概览",
  DECISION: "今日决策",
  DIAGNOSIS: "AI 诊断",
  AGENT: "AI 分析",
  RISK: "风险判断",
  RECOMMENDATION: "AI 建议",
  OPERATION: "运营任务",
};

export function getOverviewCopilotContextLabel(type: OverviewCopilotContextType): string {
  return contextTypeLabels[type];
}

function formatEvidenceForDraft(context: OverviewCopilotContext): string {
  if (context.mode === "UNAVAILABLE") return "没有可用证据";
  const evidence = context.evidence.labels.length ? context.evidence.labels.join("、") : "当前可用证据";
  const count = context.evidence.count !== undefined ? `，共 ${context.evidence.count} 条` : "";
  const history = context.evidence.historyUnavailable ? "；历史案例暂不可用" : "";
  return `${evidence}${count}${history}`;
}

function modeTrustNote(mode: OverviewMode): string {
  if (mode === "DEMO") return "当前为演示上下文，请将结论视为产品流程示例。";
  if (mode === "STALE") return "部分数据更新时间较旧，请先核对最新农情。";
  if (mode === "UNAVAILABLE") return "当前没有可用的农情数据。";
  return "请只基于当前可用数据回答，并明确不确定性。";
}

export function buildOverviewCopilotDraft(context: OverviewCopilotContext): string {
  const subject = context.field?.name ? `${context.field.name}${context.field.crop ? `（${context.field.crop}）` : ""}` : context.farm.name;
  const evidence = formatEvidenceForDraft(context);
  const trust = modeTrustNote(context.mode);

  switch (context.contextType) {
    case "DECISION":
      return `为什么“${context.title}”今天需要优先处理？请结合${subject}的风险等级、影响范围和${evidence}说明判断依据，并给出下一步。${trust}`;
    case "RISK":
      return `为什么${subject}当前被判定为“${context.title}”？请结合${evidence}解释风险依据、影响范围和可能变化。${trust}`;
    case "DIAGNOSIS":
      return `请解释“${context.title}”的诊断依据、严重程度和不确定性，并结合${subject}的${evidence}给出需要人工确认的下一步。${trust}`;
    case "AGENT":
      return `请解释当前农业 AI 分析的进度、主要发现和下一步行动。结合${subject}的${evidence}说明为什么提出该行动、还缺少哪些信息，以及${context.agent?.approvalState === "等待人工确认" ? "批准或拒绝建议后会发生什么" : "当前是否需要人工确认"}。${trust}`;
    case "RECOMMENDATION":
      return `为什么建议“${context.recommendation?.recommendedAction ?? context.title}”？请结合${subject}的关联风险、${evidence}说明农业依据、适用范围和注意事项。${trust}`;
    case "OPERATION":
      return `请解释“${context.title}”当前记录的状态、来源和还需要确认的信息。${trust}`;
    case "FARM":
    default:
      return `当前农场有什么值得关注？请结合${evidence}按优先级给出风险、建议和需要确认的下一步。${trust}`;
  }
}

export function buildOverviewAIContextPatch(context: OverviewCopilotContext): Partial<AIContext> {
  const field = context.mode === "UNAVAILABLE" ? undefined : context.field;
  return {
    currentPage: context.page,
    pageKey: context.page,
    agentRunId: context.agent?.runId,
    taskId: context.operation?.taskId,
    currentField: field ? { id: field.id, name: field.name, area: field.area ?? 0, crop: field.crop ?? "" } : undefined,
    currentCrop: field?.crop ? { name: field.crop } : undefined,
    currentLocation: undefined,
    currentWeather: context.mode !== "UNAVAILABLE" && context.weather ? {
      summary: context.weather.summary,
      temperature: context.weather.temperature,
      precipitation: context.weather.precipitation ?? "暂无降雨信息",
      updatedAt: context.weather.updatedAt,
      rainfall14d: context.weather.rainfall14d,
      rainfallForecast24h: context.weather.rainfallForecast24h,
      rainfallProbability24h: context.weather.rainfallProbability24h,
      rainfallForecast: context.weather.rainfallForecast,
      rainfallForecastDays: context.weather.rainfallForecastDays,
      forecast: context.weather.forecast,
    } : undefined,
    currentPhenology: context.phenology,
    selectedImages: [],
    selectedDevice: undefined,
    selectedDiagnosis: context.diagnosis ? {
      id: context.diagnosis.id ?? context.title,
      title: context.diagnosis.title,
      severity: context.diagnosis.severity === "high" ? "high" : context.diagnosis.severity === "low" ? "low" : "medium",
      confidence: context.diagnosis.confidence,
      summary: context.summary,
    } : undefined,
  };
}

export function openOverviewCopilot(
  context: OverviewCopilotContext,
  actions: {
    setAIContext: (patch: Partial<AIContext>) => void;
    setCopilotContext: (context: OverviewCopilotContext | undefined) => void;
    setDraft: (draft: string) => void;
    open: () => void;
  },
) {
  actions.setAIContext(buildOverviewAIContextPatch(context));
  actions.setCopilotContext(context);
  actions.setDraft(buildOverviewCopilotDraft(context));
  actions.open();
}

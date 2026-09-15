import {
  demoAgentSteps,
  demoAgentTools,
  demoDiagnosis,
  demoEvidence,
  demoFarm,
  demoFarmLocations,
  demoMapCrops,
  demoPrimaryField,
  demoRecommendations,
  demoLeafAssets,
  demoWeather,
} from "@/demo";
import type { AgentEvidence, AgentRun, AgentStepStatus, AgentToolCall } from "@/features/agent/contract";
import { cropDisplayName } from "@/features/farm/labels";
import type {
  AgentProgressStatus,
  AgentWorkspaceAction,
  AgentWorkspaceEvidence,
  AgentWorkspaceFarm,
  AgentWorkspaceFallback,
  AgentWorkspaceFinding,
  AgentWorkspaceRuntimeState,
  AgentWorkspaceStep,
  AgentSourceStatus,
  AgentWorkspaceStatus,
  AgentWorkspaceViewModel,
} from "@/types/agent-workspace";

const toolLabels: Record<string, { title: string; modality?: string }> = {
  field: { title: "读取地块上下文", modality: "地块信息" },
  "field-context": { title: "读取地块上下文", modality: "地块信息" },
  gis: { title: "读取空间信息", modality: "空间信息" },
  "gis-tool": { title: "读取空间信息", modality: "空间信息" },
  camera: { title: "检查监控图像", modality: "监控图像" },
  "camera-tool": { title: "检查监控图像", modality: "监控图像" },
  weather: { title: "检查天气信息", modality: "气象数据" },
  "weather-tool": { title: "检查天气信息", modality: "气象数据" },
  knowledge: { title: "检索农业知识", modality: "农业知识" },
  "knowledge-retriever": { title: "检索农业知识", modality: "农业知识" },
  history: { title: "检查历史案例", modality: "历史案例" },
  "historical-case": { title: "检查历史案例", modality: "历史案例" },
  rag: { title: "检索农业证据", modality: "多模态证据" },
  "multimodal-rag": { title: "检索农业证据", modality: "多模态证据" },
  analysis: { title: "综合分析诊断", modality: "诊断分析" },
  "analysis-tool": { title: "综合分析诊断", modality: "诊断分析" },
  task: { title: "准备农事任务", modality: "农事任务" },
  "task-tool": { title: "准备农事任务", modality: "农事任务" },
};

function toolPresentation(toolName?: string) {
  const key = (toolName ?? "").toLowerCase().replaceAll("_", "-");
  return toolLabels[key] ?? { title: "处理分析信息", modality: "分析信息" };
}

function statusLabel(status: AgentWorkspaceStatus): string {
  return {
    READY: "准备分析",
    ANALYZING: "分析中",
    WAITING_APPROVAL: "等待人工确认",
    COMPLETED: "分析完成",
    FAILED: "分析失败",
    CANCELLED: "已取消",
  }[status];
}

function statusDetail(status: AgentWorkspaceStatus): string {
  return {
    READY: "上下文已准备，可以开始一次农业问题分析。",
    ANALYZING: "FarMap 正在读取农业数据并形成证据支持的判断。",
    WAITING_APPROVAL: "AI 已提出需要写入 Operations 的行动，等待你确认。",
    COMPLETED: "分析结果已形成；后续写操作仍以真实审批结果为准。",
    FAILED: "分析未完成，请检查失败信息后重试或返回诊断页面。",
    CANCELLED: "本次分析已取消，没有继续执行后续操作。",
  }[status];
}

function stepStatusForDemo(index: number, runtime: AgentWorkspaceRuntimeState): AgentProgressStatus {
  if (index >= 6) {
    if (runtime.runStatus === "completed") return "UNAVAILABLE";
    if (runtime.runStatus === "running" && index === runtime.currentStep) return "ACTIVE";
    return "PENDING";
  }
  if (runtime.runStatus === "pending") return "PENDING";
  if (runtime.runStatus === "completed" || index < runtime.currentStep) return "COMPLETED";
  if (runtime.runStatus === "running" && index === runtime.currentStep) return "ACTIVE";
  return "PENDING";
}

function demoSteps(runtime: AgentWorkspaceRuntimeState): AgentWorkspaceStep[] {
  const titles = ["理解分析目标", "读取地块上下文", "检查农情数据", "检索农业证据", "形成证据判断", "准备处理建议", "准备任务审批", "专家复核入口"];
  return demoAgentSteps.map((step, index) => ({
    id: step.id,
    title: titles[index] ?? step.title,
    description: step.description,
    duration: step.duration,
    status: stepStatusForDemo(index, runtime),
    technicalTool: demoAgentTools[index]?.name,
  }));
}

function categoryFromModality(modality: string): AgentWorkspaceEvidence["category"] {
  const key = modality.toLowerCase();
  if (key.includes("camera") || key.includes("visual")) return "监控图像";
  if (key.includes("weather")) return "气象数据";
  if (key.includes("soil")) return "土壤数据";
  if (key.includes("knowledge")) return "农业知识";
  if (key.includes("historical") || key.includes("history") || key.includes("case")) return "历史案例";
  return "空间证据";
}

function evidenceWeight(score?: number): AgentWorkspaceEvidence["weight"] {
  if (score === undefined) return "medium";
  return score >= 0.8 ? "high" : score >= 0.6 ? "medium" : "low";
}

function demoEvidenceView(runtime: AgentWorkspaceRuntimeState): AgentWorkspaceEvidence[] {
  if (runtime.runStatus === "pending" || runtime.currentStep < 2) return [];
  return demoEvidence.map((item) => ({ ...item, modality: item.category, availability: "AVAILABLE" as const, technicalId: item.id }));
}

function demoActions(runtime: AgentWorkspaceRuntimeState): AgentWorkspaceAction[] {
  if (runtime.runStatus === "pending" || runtime.currentStep < 5) return [];
  return demoRecommendations.slice(0, 2).map((item, index) => ({
    id: `demo-action-${index + 1}`,
    type: "operations_task",
    title: item.title,
    detail: item.detail,
    scope: `${demoPrimaryField.name} · ${demoPrimaryField.area} ha`,
    evidenceIds: [],
    approvalRequired: true,
    approvalState: "UNAVAILABLE" as const,
    outcome: "演示模式不执行写操作",
  }));
}

function demoFindings(runtime: AgentWorkspaceRuntimeState): AgentWorkspaceFinding[] {
  if (runtime.runStatus === "pending" || runtime.currentStep < 4) return [];
  return demoEvidence.slice(0, 4).map((item, index) => ({
    id: `demo-finding-${index + 1}`,
    text: item.claim,
    evidenceIds: [item.id],
    severity: index < 2 ? "high" : "medium",
  }));
}

function demoApproval(actions: AgentWorkspaceAction[]): AgentWorkspaceViewModel["approval"] {
  if (!actions.length) return { state: "UNAVAILABLE", stateLabel: "演示模式不执行写操作", canApprove: false, canReject: false };
  return {
    state: "UNAVAILABLE",
    stateLabel: "演示模式不执行写操作",
    actionId: actions[0].id,
    title: actions[0].title,
    detail: "真实 Agent Run 才会在审批后调用 Task Tool；当前演示不会创建任务。",
    canApprove: false,
    canReject: false,
  };
}

function demoExecution(actions: AgentWorkspaceAction[]): AgentWorkspaceViewModel["execution"] {
  return actions.length
    ? { state: "UNAVAILABLE", stateLabel: "演示模式不可执行", detail: "没有创建 Operations 任务，也没有改变真实农事状态。" }
    : { state: "NOT_STARTED", stateLabel: "尚未提出行动", detail: "分析完成后，真实运行可能提出需要人工确认的行动。" };
}

export function getDemoAgentWorkspaceViewModel(runtime: AgentWorkspaceRuntimeState): AgentWorkspaceViewModel {
  const status: AgentWorkspaceStatus = runtime.runStatus === "pending" ? "READY" : runtime.runStatus === "running" ? "ANALYZING" : runtime.runStatus === "completed" ? "COMPLETED" : "FAILED";
  const evidence = demoEvidenceView(runtime);
  const actions = demoActions(runtime);
  const updatedAt = demoDiagnosis.createdAt;
  return {
    mode: "DEMO",
    sourceLabel: "DEMO AGENT",
    farm: { id: demoFarm.id, name: demoFarm.name, address: demoFarm.address },
    field: demoPrimaryField,
    map: { crops: demoMapCrops, locations: demoFarmLocations },
    images: demoLeafAssets,
    weather: { summary: demoWeather.summary, temperature: demoWeather.temperature, updatedAt: demoWeather.updatedAt },
    dataSources: [
      { id: "camera", label: "监控图像", countLabel: "8 张", status: "AVAILABLE" },
      { id: "satellite", label: "卫星影像", countLabel: "2 景", status: "AVAILABLE" },
      { id: "weather", label: "气象数据", countLabel: "14 天", status: "AVAILABLE" },
      { id: "soil", label: "土壤数据", countLabel: "1 组", status: "AVAILABLE" },
      { id: "history", label: "历史诊断", countLabel: "3 条（演示）", status: "AVAILABLE" },
      { id: "knowledge", label: "农业知识库", countLabel: "12 条", status: "AVAILABLE" },
    ],
    goal: "分析 A-12 地块叶片黄化的原因，并生成处理方案",
    diagnosis: { id: demoDiagnosis.id, title: "A-12 地块叶片异常", severity: demoDiagnosis.severity, confidence: demoDiagnosis.confidence, summary: demoDiagnosis.summary },
    status,
    statusLabel: statusLabel(status),
    statusDetail: statusDetail(status),
    runId: runtime.runStatus === "pending" ? undefined : "a12-demo",
    createdAt: updatedAt,
    updatedAt,
    freshness: { updatedAt, stale: false },
    steps: demoSteps(runtime),
    findings: demoFindings(runtime),
    evidence,
    actions,
    approval: demoApproval(actions),
    execution: demoExecution(actions),
    technical: {
      runId: runtime.runStatus === "pending" ? undefined : "a12-demo",
      rawStatus: runtime.runStatus,
      toolCalls: [],
      traces: [],
      metadata: {},
      evidenceIds: [],
      taskIds: [],
      modeNote: "演示计时器仅用于展示产品流程，不代表真实 Agent Run、工具调用或任务执行。",
    },
    canStart: true,
    canCancel: false,
    canRetry: runtime.runStatus === "failed",
  };
}

function mapStepStatus(status: AgentStepStatus): AgentProgressStatus {
  return status === "COMPLETED" ? "COMPLETED" : status === "RUNNING" ? "ACTIVE" : status === "FAILED" ? "FAILED" : status === "SKIPPED" ? "UNAVAILABLE" : "PENDING";
}

function mapRunStatus(run: AgentRun): AgentWorkspaceStatus {
  if (run.status === "COMPLETED") return "COMPLETED";
  if (run.status === "FAILED") return "FAILED";
  if (run.status === "CANCELLED") return "CANCELLED";
  if (run.status === "WAITING_FOR_APPROVAL") return "WAITING_APPROVAL";
  if (run.status === "CREATED") return "READY";
  return "ANALYZING";
}

function objectValue(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function toolCallFor(run: AgentRun, toolName: string): AgentToolCall | undefined {
  return [...run.toolCalls].reverse().find((call) => call.toolName === toolName);
}

function sourceStatusFromValues(...values: unknown[]): AgentSourceStatus | undefined {
  const normalized = values.filter((value): value is string => typeof value === "string").map((value) => value.toUpperCase());
  if (normalized.some((value) => value === "FAILED" || value === "FAILURE")) return "FAILED";
  if (normalized.some((value) => value === "READY_EMPTY" || value === "EMPTY" || value === "NO_HISTORICAL_CASES")) return "READY_EMPTY";
  if (normalized.some((value) => value === "UNAVAILABLE" || value === "NOT_AVAILABLE")) return "UNAVAILABLE";
  if (normalized.some((value) => value === "READY" || value === "COMPLETED" || value === "SUCCESS" || value === "MYSQL" || value === "GAODE_LIVE" || value === "LOCAL" || value === "REAL")) return "READY";
  return undefined;
}

function stepSourceStatus(run: AgentRun, step: AgentRun["steps"][number]): AgentSourceStatus | undefined {
  const call = toolCallFor(run, step.toolName);
  const callOutput = call?.output ?? {};
  const metadata = objectValue(run.metadata[step.toolName]);
  const outputStatus = sourceStatusFromValues(callOutput.status, callOutput.executionStatus, metadata.status, metadata.knowledgeStatus, metadata.historicalStatus, metadata.sourceMode);
  if (outputStatus) return outputStatus;
  if (call?.status === "FAILED" || step.status === "FAILED") return "FAILED";
  if (call?.status === "UNAVAILABLE" || step.status === "SKIPPED") return "UNAVAILABLE";
  if (call?.status === "COMPLETED" || step.status === "COMPLETED") return "READY";
  return undefined;
}

function mapRunSteps(run: AgentRun): AgentWorkspaceStep[] {
  return run.steps.map((step) => {
    const presentation = toolPresentation(step.toolName);
    return {
      id: step.id,
      title: presentation.title,
      description: step.description,
      status: mapStepStatus(step.status),
      sourceStatus: stepSourceStatus(run, step),
      technicalTool: step.toolName,
    };
  });
}

function mapRunEvidence(item: AgentEvidence): AgentWorkspaceEvidence {
  const category = categoryFromModality(item.modality);
  const source = item.source ?? {};
  const sourceLabel = [source.name, source.fieldId, source.cameraId, source.document].filter(Boolean).map(String).join(" · ") || "Agent 运行证据";
  const timestamp = source.timestamp ?? source.observedAt ?? source.section;
  const preview = item.preview ?? {};
  const imageUrl = typeof preview.imageUrl === "string" ? preview.imageUrl : undefined;
  return {
    id: item.id,
    category,
    modality: item.modality,
    title: item.title,
    detail: item.summary,
    source: sourceLabel,
    observedAt: timestamp ? String(timestamp) : "运行期间获取",
    weight: evidenceWeight(item.score),
    claim: "本证据已被 Agent 纳入当前分析。",
    asset: imageUrl,
    availability: "AVAILABLE",
    score: item.score,
    technicalId: item.id,
  };
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function mapFindings(run: AgentRun): AgentWorkspaceFinding[] {
  const rawFindings = run.output.findings;
  if (Array.isArray(rawFindings)) {
    return rawFindings.flatMap((item, index) => {
      if (typeof item === "string") return [{ id: `finding-${index + 1}`, text: item, evidenceIds: asStringArray(run.output.evidenceIds) }];
      if (!item || typeof item !== "object") return [];
      const finding = item as Record<string, unknown>;
      const text = typeof finding.text === "string" ? finding.text : typeof finding.title === "string" ? finding.title : "";
      return text ? [{ id: String(finding.id ?? `finding-${index + 1}`), text, evidenceIds: asStringArray(finding.evidenceIds ?? finding.evidence_ids), severity: finding.severity === "high" || finding.severity === "medium" || finding.severity === "low" ? finding.severity : undefined, uncertainty: typeof finding.uncertainty === "string" ? finding.uncertainty : undefined }] : [];
    });
  }
  const evidenceIds = asStringArray(run.output.evidenceIds);
  if (typeof run.output.conclusion === "string" && run.output.conclusion.trim()) return [{ id: "agent-conclusion", text: run.output.conclusion, evidenceIds }];
  const diagnosis = run.context.diagnosis;
  if (typeof diagnosis.title === "string" && diagnosis.title.trim()) return [{ id: "agent-diagnosis", text: diagnosis.title, evidenceIds }];
  return [];
}

function extractTask(run: AgentRun): { taskId?: string; status?: string } {
  const task = run.output.task;
  if (!task || typeof task !== "object") return {};
  const row = task as Record<string, unknown>;
  return { taskId: typeof row.taskId === "string" ? row.taskId : undefined, status: typeof row.status === "string" ? row.status : undefined };
}

function sourceCountLabel(sourceId: string, status: AgentSourceStatus, run: AgentRun, evidence: AgentWorkspaceEvidence[]): string {
  if (status === "READY_EMPTY") return "READY_EMPTY · 未找到结果";
  if (status === "UNAVAILABLE") return "UNAVAILABLE · 未接入";
  if (status === "FAILED") return "FAILED · 本次失败";
  if (sourceId === "weather") {
    const forecastDays = run.context.weather?.rainfallForecastDays;
    return forecastDays ? `READY · 实时 + ${forecastDays} 天预报` : "READY · 实时天气";
  }
  if (sourceId === "knowledge") return `READY · ${evidence.filter((item) => item.category === "农业知识").length} 条 Evidence`;
  if (sourceId === "camera") return `READY · ${run.context.imageUrls?.length ?? 0} 张图片`;
  if (sourceId === "field") return "READY · 真实地块上下文";
  if (sourceId === "gis") return "READY · 坐标与空间信息";
  return "READY";
}

function sourceStatus(run: AgentRun, sourceId: string, evidence: AgentWorkspaceEvidence[]): AgentSourceStatus {
  const sourceModes = objectValue(run.context.sourceModes);
  const toolNames: Record<string, string[]> = {
    field: ["field", "field-context"],
    weather: ["weather", "weather-tool"],
    gis: ["gis", "gis-tool"],
    knowledge: ["knowledge", "knowledge-retriever", "rag", "multimodal-rag"],
    historical: ["historical-case", "history"],
    camera: ["camera", "camera-tool"],
  };
  const calls = (toolNames[sourceId] ?? []).map((name) => toolCallFor(run, name)).filter((call): call is AgentToolCall => Boolean(call));
  const metadata = (toolNames[sourceId] ?? []).map((name) => objectValue(run.metadata[name]));
  const evidenceStatus = sourceId === "knowledge" && evidence.some((item) => item.category === "农业知识") ? "READY" : undefined;
  const contextStatus = sourceId === "weather" ? run.context.weather?.status : sourceModes[sourceId];
  const callStatus = calls.map((call) => sourceStatusFromValues(call.status, call.output?.status, call.output?.executionStatus));
  const metadataStatus = metadata.map((item) => sourceStatusFromValues(item.status, item.sourceMode, item.knowledgeStatus, item.historicalStatus));
  if (sourceId === "camera" && (run.context.imageUrls?.length ?? 0) > 0) return "READY";
  return sourceStatusFromValues(evidenceStatus, contextStatus, ...callStatus, ...metadataStatus) ?? "UNAVAILABLE";
}

function mapRealDataSources(run: AgentRun, evidence: AgentWorkspaceEvidence[]) {
  const descriptors = [
    ["field", "地块上下文"],
    ["weather", "气象数据"],
    ["gis", "空间信息"],
    ["knowledge", "农业知识"],
    ["historical", "历史案例"],
    ["camera", "现场图像"],
  ] as const;
  return descriptors.map(([id, label]) => {
    const status = sourceStatus(run, id, evidence);
    return { id, label, countLabel: sourceCountLabel(id, status, run, evidence), status: status === "READY" ? "AVAILABLE" as const : status === "READY_EMPTY" ? "EMPTY" as const : "UNAVAILABLE" as const };
  });
}

function realFarm(run: AgentRun, fallback: AgentWorkspaceFallback): AgentWorkspaceFarm {
  const id = Number(run.context.farmId);
  return { id: Number.isFinite(id) && id > 0 ? id : fallback.farm.id, name: run.context.farmName || fallback.farm.name, address: fallback.farm.address };
}

function realField(run: AgentRun, fallback: AgentWorkspaceFallback): AgentWorkspaceFallback["field"] {
  const latitude = typeof run.context.latitude === "number" ? run.context.latitude : fallback.field.latitude;
  const longitude = typeof run.context.longitude === "number" ? run.context.longitude : fallback.field.longitude;
  return {
    ...fallback.field,
    id: run.context.fieldId || fallback.field.id,
    name: run.context.fieldName || fallback.field.name,
    crop: cropDisplayName(run.context.crop || fallback.field.crop),
    variety: run.context.variety || fallback.field.variety,
    growthStage: run.context.phenologyStage || fallback.field.growthStage,
    latitude,
    longitude,
  };
}

function realWeather(run: AgentRun, fallback: AgentWorkspaceFallback["weather"]): AgentWorkspaceFallback["weather"] {
  const weather = run.context.weather ?? {};
  const temperature = weather.temperature === undefined || weather.temperature === null ? fallback.temperature : `${weather.temperature}°C`;
  return {
    summary: typeof weather.summary === "string" ? weather.summary : fallback.summary,
    temperature,
    updatedAt: String(weather.observedAt ?? weather.dataDate ?? fallback.updatedAt),
  };
}

function mapActions(run: AgentRun, fallback: AgentWorkspaceFallback): AgentWorkspaceAction[] {
  const task = extractTask(run);
  const defaultScope = `${fallback.field.name} · ${fallback.field.area} ha`;
  return run.actions.map((action) => {
    const payload = action.payload ?? {};
    const payloadEvidence = asStringArray(payload.evidenceIds ?? payload.evidence_ids);
    const evidenceIds = payloadEvidence.length ? payloadEvidence : asStringArray(run.output.evidenceIds);
    return {
      id: action.id,
      type: action.type,
      title: action.title,
      detail: action.detail,
      scope: defaultScope,
      evidenceIds,
      approvalRequired: action.approvalRequired,
      approvalState: action.approvalState,
      taskId: task.taskId,
      taskStatus: task.status,
      outcome: task.taskId ? "Operations 任务已由 Agent Runtime 返回。" : undefined,
      technicalTool: action.toolName,
    };
  });
}

function mapApproval(run: AgentRun, actions: AgentWorkspaceAction[]): AgentWorkspaceViewModel["approval"] {
  const pending = actions.find((action) => action.approvalState === "REQUIRED");
  const state = run.approvalState;
  const stateLabel = state === "REQUIRED" ? "等待人工确认" : state === "APPROVED" ? "已批准" : state === "REJECTED" ? "已拒绝" : "无需审批";
  return {
    state,
    stateLabel,
    actionId: pending?.id ?? actions.find((action) => action.approvalState !== "NONE")?.id,
    title: pending?.title,
    detail: pending?.detail,
    canApprove: Boolean(pending && run.status === "WAITING_FOR_APPROVAL"),
    canReject: Boolean(pending && run.status === "WAITING_FOR_APPROVAL"),
  };
}

function mapExecution(run: AgentRun, actions: AgentWorkspaceAction[]): AgentWorkspaceViewModel["execution"] {
  const task = extractTask(run);
  if (task.taskId) return { state: "CREATED", stateLabel: "任务已创建", detail: "真实 Operations 任务已由批准后的 Task Tool 返回。", taskId: task.taskId };
  if (run.status === "WAITING_FOR_APPROVAL") return { state: "PENDING_APPROVAL", stateLabel: "等待批准后创建", detail: "批准后才会调用写入工具并返回 taskId。" };
  if (run.status === "FAILED") return { state: "FAILED", stateLabel: "未创建任务", detail: run.failureMessage || "运行失败，Operations 没有收到任务。" };
  if (run.status === "CANCELLED") return { state: "UNAVAILABLE", stateLabel: "已取消", detail: "取消后没有继续创建任务。" };
  if (actions.length) return { state: "APPROVED", stateLabel: "已形成行动建议", detail: "当前结果尚未返回 Operations taskId。" };
  return { state: "NOT_STARTED", stateLabel: "尚未创建任务", detail: "当前运行没有返回可执行的 Operations 任务。" };
}

export function toAgentWorkspaceViewModel(run: AgentRun, fallback: AgentWorkspaceFallback): AgentWorkspaceViewModel {
  const status = mapRunStatus(run);
  const actions = mapActions(run, fallback);
  const updatedAt = run.updatedAt || fallback.updatedAt || fallback.createdAt;
  const evidence = run.evidence.map(mapRunEvidence);
  const diagnosis = run.context.diagnosis;
  const farm = realFarm(run, fallback);
  const field = realField(run, fallback);
  const weather = realWeather(run, fallback.weather);
  const evidenceIds = asStringArray(run.output.evidenceIds).length
    ? asStringArray(run.output.evidenceIds)
    : evidence.map((item) => item.technicalId ?? item.id);
  const task = extractTask(run);
  const taskIds = task.taskId ? [task.taskId] : [];
  const model = typeof run.metadata.plannerModel === "string"
    ? run.metadata.plannerModel
    : run.plan?.plannerModel;
  return {
    mode: run.mode === "real" ? "REAL" : "DEMO",
    sourceLabel: run.mode === "real" ? "真实 Agent 运行" : "流程演示",
    farm,
    field,
    map: fallback.map,
    images: evidence.map((item) => item.asset).filter((asset): asset is string => Boolean(asset)).length
      ? evidence.map((item) => item.asset).filter((asset): asset is string => Boolean(asset))
      : (run.context.imageUrls ?? []),
    weather,
    dataSources: mapRealDataSources(run, evidence),
    goal: run.goal,
    diagnosis: typeof diagnosis.title === "string" ? { id: typeof diagnosis.id === "string" ? diagnosis.id : undefined, title: diagnosis.title, severity: diagnosis.severity === "high" || diagnosis.severity === "medium" || diagnosis.severity === "low" ? diagnosis.severity : undefined, confidence: typeof diagnosis.confidence === "number" ? diagnosis.confidence : undefined, summary: typeof diagnosis.summary === "string" ? diagnosis.summary : undefined } : fallback.diagnosis,
    status,
    statusLabel: statusLabel(status),
    statusDetail: statusDetail(status),
    runId: run.runId,
    createdAt: run.createdAt || fallback.createdAt,
    updatedAt,
    freshness: { updatedAt, stale: false },
    steps: mapRunSteps(run),
    findings: mapFindings(run),
    evidence,
    actions,
    approval: mapApproval(run, actions),
    execution: mapExecution(run, actions),
    technical: { runId: run.runId, rawStatus: run.status, plan: run.plan, toolCalls: run.toolCalls, traces: run.traces, metadata: run.metadata, model, diagnosisId: run.context.diagnosisId ?? (typeof diagnosis.id === "string" ? diagnosis.id : undefined), evidenceIds, taskIds },
    failure: run.failureCode || run.failureMessage ? { code: run.failureCode, message: run.failureMessage } : undefined,
    canStart: false,
    canCancel: run.status === "CREATED" || run.status === "PLANNING" || run.status === "RUNNING",
    canRetry: run.status === "FAILED" || run.status === "CANCELLED",
  };
}

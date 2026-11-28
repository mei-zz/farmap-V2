import type { PhenologyAccumulation, PhenologyStage, PhenologyTimelineData } from "./blocks/PlumPhenologyTimeline";
import type { SprayWeatherData, WorkDayAssessment, WorkSuitability, WorkSuitabilityLevel, WorkTimeSlot } from "./blocks/SprayWeatherWindowCard";
import type { AtlasFigure, PestComparisonData } from "./blocks/PestComparisonCard";
import type { MemoryProposalData } from "./blocks/MemoryProposalCard";
import type { ComparisonMatrixData } from "./blocks/ComparisonMatrixCard";
import type { ActionChecklistData } from "./blocks/ActionChecklistCard";

export const componentTypes = [
  "FarmDirectoryCard", "ParcelOverviewCard", "WeatherCard", "EnvironmentCard", "CropInfoCard", "FarmHistoryCard",
  "CropGrowthChart", "WeatherTrendChart", "SoilTrendChart", "ParcelCompareChart", "FarmAnalyticsCard",
  "DiagnosisCard", "PestDiagnosisCard", "RiskAlertCard", "DiagnosisEvidenceCard",
  "FarmPlanCard", "FertilizerPlanCard", "IrrigationPlanCard", "FarmCalendarCard", "FarmTaskList",
  "ClarificationForm", "ConfirmationCard", "ApprovalCard", "TaskProgressCard", "FeedbackCard",
  "EvidenceCard", "KnowledgeSourceCard", "ExpertReviewCard", "FarmMapCard", "FarmMaturityCard",
  "FarmInspectionProgress", "FarmRecommendationCard", "FarmAdviceCard", "OperationTaskCard", "OperationsQueryCard",
  "PlumPhenologyTimeline", "SprayWeatherWindowCard", "PestComparisonCard",
  "EChartsAnalyticsCard", "ComparisonMatrixCard", "ActionChecklistCard",
  "MemoryProposalCard", "WorkspaceNavigationCard", "MemoryPreferenceCard",
  "FarmOperationChecklist",
] as const;

const statuses = ["ready", "loading", "unavailable", "needs-input", "completed", "failed"] as const;
const actionTypes = ["expand", "prompt", "select-field", "open-detail", "draft-task", "open-evidence-drawer", "confirm-memory"] as const;

export const workspaceNavigationTitles: Readonly<Record<string, string>> = {
  "/overview": "农场总览",
  "/operations": "农事任务",
  "/diagnosis": "农情诊断",
  "/analysis": "农场分析",
  "/knowledge": "农业知识",
  "/farm/map": "地块空间",
};

function workspaceNavigationRoute(value: unknown): string | undefined {
  return typeof value === "string" && Object.prototype.hasOwnProperty.call(workspaceNavigationTitles, value) ? value : undefined;
}

export type BlockComponentType = typeof componentTypes[number];
export type AgentUiActionType = typeof actionTypes[number];
export type DraftTaskPayload = { fieldId: string; title: string; detail: string; scheduledFor?: string };
export type MemoryActionDecision = "confirm" | "ignore";
export type AgentUiAction = {
  type: AgentUiActionType;
  label: string;
  value?: string;
  draftTask?: DraftTaskPayload;
  evidenceId?: string;
  proposalId?: string;
  decision?: MemoryActionDecision;
};
export type BlockItem = { label: string; value?: string | number; unit?: string; status?: string };
export type ChartPoint = { label: string; value: number };
export type ChartSeries = {
  label: string;
  points: ChartPoint[];
  type?: "line" | "bar";
  axis?: "left" | "right";
  unit?: string;
  stack?: string;
  threshold?: number;
  color?: string;
};
export type ChartThreshold = { label: string; value: number; unit?: string; axis?: "left" | "right"; seriesLabel?: string };
export type ClarificationField = { id: string; label: string; required?: boolean; options?: { label: string; value: string }[] };
export type MaturityStage = "UNRIPE" | "TURNING" | "RIPE" | "OVER_RIPE" | "MIXED" | "UNKNOWN";
export type MaturityObservation = { cameraId?: string; cameraName: string; stage: MaturityStage; reason?: string; capturedAt?: string; imageUrl?: string; source?: string; quality?: string; status?: string; label?: string };
export type MaturityParcel = { fieldId?: string; name: string; latitude?: number; longitude?: number; stage: MaturityStage; reason?: string; observations: MaturityObservation[] };
export type AgentWeather = {
  farmName?: string; observedAt?: string; condition?: string; temperature?: string; humidity?: string;
  windDirection?: string; windPower?: string; source?: string;
  forecast?: Array<{ date: string; condition?: string; temperatureMin?: number; temperatureMax?: number; precipitationProbability?: number; precipitationMm?: number }>;
};
export type BlockData = {
  farmName?: string;
  farmId?: number;
  fieldId?: string;
  weather?: AgentWeather;
  farms?: Array<{ id: number; name: string }>;
  mode?: "FARM" | "CAMERA" | "FIELD" | "IMAGE";
  kind?: "MATURITY" | "PHENOLOGY" | "GROWTH";
  observedAt?: string;
  parcels?: MaturityParcel[];
  summary?: string;
  items?: BlockItem[];
  metrics?: BlockItem[];
  series?: ChartSeries[];
  thresholds?: ChartThreshold[];
  fields?: ClarificationField[];
  locations?: { id: string; label: string; latitude: number; longitude: number }[];
  runId?: string;
  analysisRunId?: string;
  taskId?: string;
  taskView?: "tasks" | "calendar" | "records";
  taskFilter?: "active" | "pending" | "in-progress" | "completed";
  from?: string;
  to?: string;
  inspection?: { total: number; processed: number; captured: number; valid: number };
  diagnosisId?: string;
  question?: string;
  originalRequest?: string;
  phenologyTimeline?: PhenologyTimelineData;
  sprayWeather?: SprayWeatherData;
  atlasFigures?: AtlasFigure[];
  pestComparison?: PestComparisonData;
  comparisonMatrix?: ComparisonMatrixData;
  actionChecklist?: ActionChecklistData;
  memoryProposal?: MemoryProposalData;
  route?: string;
};

export type AgentUiBlock = {
  blockId: string;
  runId: string;
  componentType: BlockComponentType;
  schemaVersion: 1;
  title: string;
  summary?: string;
  status: typeof statuses[number];
  data?: BlockData;
  dataRef?: string;
  actions: AgentUiAction[];
  evidenceIds: string[];
  provenance: { label: string; reference?: string }[];
  createdAt: string;
  updatedAt: string;
};

function record(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

function string(value: unknown, limit = 300): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, limit) : undefined;
}

export function uiId(value: unknown): string | undefined {
  return typeof value === "string" && /^[\w.-]{1,160}$/u.test(value) ? value : undefined;
}

function date(value: unknown): string | undefined {
  const text = string(value, 40);
  return text && Number.isFinite(Date.parse(text)) ? text : undefined;
}

function list<T>(value: unknown, limit: number, parse: (entry: unknown, index: number) => T | undefined): T[] {
  return Array.isArray(value) ? value.slice(0, limit).flatMap((entry, index) => {
    const result = parse(entry, index);
    return result === undefined ? [] : [result];
  }) : [];
}

function parseItem(input: unknown): BlockItem | undefined {
  const item = record(input);
  const label = string(item?.label ?? item?.name ?? item?.title);
  if (!item || !label) return undefined;
  const value = typeof item.value === "number" && Number.isFinite(item.value)
    ? item.value : string(item.value ?? item.summary ?? item.detail, 2000);
  return { label, value, unit: string(item.unit, 30), status: string(item.status, 40) };
}

function numberInRange(value: unknown, min: number, max: number): number | undefined {
  const parsed = typeof value === "string" && /^-?\d+(?:\.\d+)?$/u.test(value.trim()) ? Number(value) : value;
  return typeof parsed === "number" && Number.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : undefined;
}

function integerInRange(value: unknown, min: number, max: number): number | undefined {
  const parsed = numberInRange(value, min, max);
  return parsed !== undefined && Number.isSafeInteger(parsed) ? parsed : undefined;
}

function scheduledFor(value: unknown): string | undefined {
  const text = string(value, 40);
  if (!text) return undefined;
  if (/^\d{4}-\d{2}-\d{2}$/u.test(text) && Number.isFinite(Date.parse(text))) return text;
  return Number.isFinite(Date.parse(text)) ? text : undefined;
}

function evidenceRef(value: unknown): string | undefined {
  const text = string(value, 160);
  return text && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/u.test(text) ? text : undefined;
}

function parseDraftTask(value: unknown, fallbackFieldId?: string): DraftTaskPayload | undefined {
  const input = record(value);
  if (!input) return undefined;
  const nested = record(input.payload) ?? record(input.draftTask) ?? record(input.taskDraft)
    ?? record(input.task) ?? record(input.value);
  const source = nested ?? input;
  const fieldId = uiId(source.fieldId ?? source.fieldID) ?? fallbackFieldId;
  const title = string(source.title, 120);
  const detail = string(source.detail ?? source.description, 880);
  if (!fieldId || !title || !detail) return undefined;
  const date = scheduledFor(source.scheduledFor ?? source.date);
  return date ? { fieldId, title, detail, scheduledFor: date } : { fieldId, title, detail };
}

function parseChartSeries(value: unknown): ChartSeries[] {
  return list(value, 6, (entry) => {
    const series = record(entry);
    const label = string(series?.label, 80);
    const points = list(series?.points, 90, (candidate) => {
      const point = record(candidate);
      const pointLabel = string(point?.label, 80);
      const pointValue = numberInRange(point?.value, -1_000_000_000, 1_000_000_000);
      return pointLabel && pointValue !== undefined ? { label: pointLabel, value: pointValue } : undefined;
    });
    if (!label || !points.length) return undefined;
    const type = (["line", "bar"] as const).find((candidate) => candidate === series?.type || candidate === series?.chartType);
    const axis = series?.axis === "right" || series?.yAxisIndex === 1 || series?.yAxisIndex === "right"
      ? "right" : series?.axis === "left" || series?.yAxisIndex === 0 || series?.yAxisIndex === "left" ? "left" : undefined;
    const color = string(series?.color, 20);
    const stack = string(series?.stack, 40);
    return {
      label, points, type, axis,
      unit: string(series?.unit, 24),
      stack,
      threshold: numberInRange(series?.threshold ?? series?.markLine, -1_000_000_000, 1_000_000_000),
      color: color && /^#[0-9a-f]{3,8}$/iu.test(color) ? color : undefined,
    };
  });
}

function parseThresholds(value: unknown): ChartThreshold[] {
  return list(value, 8, (entry) => {
    const threshold = record(entry);
    const label = string(threshold?.label, 60);
    const thresholdValue = numberInRange(threshold?.value ?? threshold?.y, -1_000_000_000, 1_000_000_000);
    if (!label || thresholdValue === undefined) return undefined;
    return {
      label, value: thresholdValue,
      unit: string(threshold?.unit, 20),
      axis: threshold?.axis === "right" || threshold?.yAxisIndex === 1 ? "right" : undefined,
      seriesLabel: string(threshold?.seriesLabel, 80),
    };
  });
}

function imageReference(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length > 1200) return undefined;
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password ? url.href : undefined;
  } catch {
    return /^\/uploads\/[\w./-]+$/u.test(value) && !value.split("/").some((part) => part === ".." || part === ".") ? value : undefined;
  }
}

function textCell(value: unknown): string | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "string") return string(value, 320);
  const cell = record(value);
  return string(cell?.value ?? cell?.label ?? cell?.text, 320);
}

function cellValues(value: unknown): Record<string, string> {
  const values = Object.create(null) as Record<string, string>;
  const input = record(value);
  if (!input) return values;
  for (const [key, candidate] of Object.entries(input).slice(0, 24)) {
    const cell = textCell(candidate);
    if (cell) values[key] = cell;
  }
  return values;
}

function parseComparisonMatrix(value: unknown, fallbackFieldId?: string): ComparisonMatrixData | undefined {
  const matrix = record(value);
  if (!matrix) return undefined;
  const rawOptions = matrix.options ?? matrix.columns ?? matrix.solutions ?? matrix.plans;
  const rawRows = matrix.rows ?? matrix.dimensions ?? matrix.attributes ?? matrix.criteria;
  if (!Array.isArray(rawOptions) || !Array.isArray(rawRows) || rawOptions.length < 2 || rawRows.length < 1) return undefined;

  const options = rawOptions.slice(0, 6).flatMap((entry, index) => {
    if (typeof entry === "string") {
      const label = string(entry, 80);
      return label ? [{ id: `option-${index + 1}`, label, values: cellValues(undefined) }] : [];
    }
    const option = record(entry);
    const label = string(option?.label ?? option?.name ?? option?.title, 80);
    if (!option || !label) return [];
    const id = uiId(option.id ?? option.optionId) ?? `option-${index + 1}`;
    const cells = cellValues(option.values ?? option.attributes ?? option.metrics);
    const optionCells = option.values ?? option.cells;
    if (Array.isArray(optionCells)) {
      for (const [cellIndex, cell] of optionCells.entries()) {
        const text = textCell(cell);
        if (text) cells[`row-${cellIndex + 1}`] = text;
      }
    }
    return [{
      id, label,
      summary: string(option.summary, 180),
      recommended: option.recommended === true,
      values: cells,
      draftTask: parseDraftTask(option.draftTask ?? option.taskDraft ?? option.task, fallbackFieldId),
    }];
  });
  if (options.length < 2) return undefined;

  const rows = rawRows.slice(0, 16).flatMap((entry, index) => {
    if (typeof entry === "string") {
      const label = string(entry, 80);
      return label ? [{ id: `row-${index + 1}`, label, values: cellValues(undefined) }] : [];
    }
    const row = record(entry);
    const label = string(row?.label ?? row?.name ?? row?.title ?? row?.dimension, 80);
    if (!row || !label) return [];
    const id = uiId(row.id ?? row.key) ?? `row-${index + 1}`;
    const values = cellValues(row.values ?? row.attributes ?? row.cells);
    const cells = row.values ?? row.cells;
    if (Array.isArray(cells)) {
      for (const [cellIndex, cell] of cells.entries()) {
        const option = options[cellIndex];
        const text = textCell(cell);
        if (option && text) values[option.id] = text;
      }
    }
    return [{ id, label, values }];
  });
  if (!rows.length) return undefined;

  for (const option of options) {
    rows.forEach((row, rowIndex) => {
      const value = option.values[row.id] ?? option.values[row.label] ?? option.values[`row-${rowIndex + 1}`]
        ?? row.values[option.id] ?? row.values[option.label];
      if (value) option.values[row.id] = value;
    });
  }
  return { options, rows, note: string(matrix.note ?? matrix.summary, 400) };
}

function parseChecklistPriority(value: unknown): ChecklistPriorityValue {
  const text = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (["urgent", "high", "critical", "紧急", "立即"].includes(text)) return "urgent";
  if (["preventive", "prevention", "low", "预防"].includes(text)) return "preventive";
  return "routine";
}

type ChecklistPriorityValue = "urgent" | "routine" | "preventive";

function parseActionChecklist(value: unknown, fallbackFieldId?: string): ActionChecklistData | undefined {
  const root = record(value);
  const raw = Array.isArray(value) ? value : root?.items ?? root?.steps ?? root?.tasks;
  if (!Array.isArray(raw)) return undefined;
  const items = raw.slice(0, 12).flatMap((entry, index) => {
    const item = record(entry);
    const title = string(item?.title ?? item?.label ?? item?.name, 100);
    if (!item || !title) return [];
    const prerequisiteSource = item.prerequisites ?? item.preconditions ?? item.requires;
    const prerequisites = Array.isArray(prerequisiteSource)
      ? prerequisiteSource.flatMap((candidate) => { const text = string(candidate, 100); return text ? [text] : []; })
      : string(prerequisiteSource, 240) ? [string(prerequisiteSource, 240)!] : [];
    return [{
      id: uiId(item.id ?? item.taskKey) ?? `item-${index + 1}`,
      title,
      detail: string(item.detail ?? item.description ?? item.summary, 880),
      priority: parseChecklistPriority(item.priority ?? item.level ?? item.urgency),
      prerequisites,
      scheduledFor: scheduledFor(item.scheduledFor ?? item.date),
      draftTask: parseDraftTask(item.draftTask ?? item.taskDraft ?? item.task, fallbackFieldId),
    }];
  });
  return items.length ? { items, note: string(root?.note ?? root?.summary, 400) } : undefined;
}

function parseAtlasFigures(value: unknown): AtlasFigure[] {
  return list(value, 8, (entry) => {
    const figure = record(entry);
    if (!figure) return undefined;
    const id = uiId(figure?.refId ?? figure?.id);
    const label = string(figure.label ?? figure.name, 120);
    const url = imageReference(figure.imageUrl ?? figure.url);
    if (!id || !label || !url) return undefined;
    return {
      id, label, url,
      match: string(figure.match, 80),
      stageCode: string(figure.stageCode, 60),
      diseaseKey: string(figure.diseaseKey, 80),
      vigor: string(figure.vigor, 40),
    };
  });
}

function parsePestComparison(value: unknown): PestComparisonData | undefined {
  const input = record(value);
  if (!input) return undefined;
  const siteImages: NonNullable<PestComparisonData["siteImages"]> = list(input.siteImages ?? input.observedImages ?? input.fieldImages ?? input.uploadedImages, 6, (entry, index) => {
    const image = record(entry);
    const url = imageReference(image?.url ?? image?.imageUrl);
    if (!url) return undefined;
    return {
      id: uiId(image?.id ?? image?.refId) ?? `site-${index + 1}`,
      label: string(image?.label ?? image?.caption ?? image?.name, 120) ?? "现场观测",
      url,
      capturedAt: date(image?.capturedAt),
      source: string(image?.source, 80),
    };
  });
  if (!siteImages.length && imageReference(input.siteImageUrl)) {
    siteImages.push({
      id: "site-1",
      label: string(input.siteImageLabel, 120) ?? "现场观测",
      url: imageReference(input.siteImageUrl)!,
      capturedAt: date(input.siteImageCapturedAt),
      source: string(input.siteImageSource, 80),
    });
  }
  const differentialFeatures = list(input.differentialFeatures ?? input.features ?? input.identificationFeatures, 12, (entry) => {
    if (typeof entry === "string") {
      const label = string(entry, 140);
      return label ? { label } : undefined;
    }
    const feature = record(entry);
    const label = string(feature?.label ?? feature?.name ?? feature?.text, 140);
    return label ? {
      label,
      detail: string(feature?.detail ?? feature?.description, 320),
      present: feature?.present === true || feature?.matched === true || feature?.status === "PRESENT",
    } : undefined;
  });
  const rawRecommendation = input.recommendation ?? input.recommendedPlan ?? input.plan;
  const recommendationRecord = record(rawRecommendation);
  const recommendationText = string(rawRecommendation, 880) ?? string(recommendationRecord?.detail ?? recommendationRecord?.description, 880);
  const recommendation = recommendationText ? {
    title: string(recommendationRecord?.title, 120) ?? "推荐处置方案",
    detail: recommendationText,
  } : undefined;
  const phiRecord = record(input.phi);
  const phiDays = integerInRange(phiRecord?.days ?? input.phiDays ?? input.safetyIntervalDays, 0, 3650);
  const phiWarning = string(phiRecord?.warning ?? input.phiWarning, 320);
  const phiBlocked = phiRecord?.blocked === true || input.blockedByPhi === true
    || input.phiStatus === "BLOCKED_BY_PHI" || phiRecord?.status === "BLOCKED_BY_PHI";
  const phiLabel = string(phiRecord?.label, 180);
  const phi = phiDays !== undefined || phiWarning || phiBlocked || phiLabel
    ? { days: phiDays, warning: phiWarning, blocked: phiBlocked, label: phiLabel }
    : undefined;
  const diagnosisLabel = string(input.diagnosisLabel ?? input.diseaseName ?? input.targetDisease, 120);
  if (!siteImages.length && !differentialFeatures.length && !recommendation && !phi && !diagnosisLabel) return undefined;
  return {
    diagnosisLabel,
    siteImages,
    differentialFeatures,
    recommendation,
    phi,
  };
}

function parseData(raw: unknown): BlockData | undefined {
  const input = record(raw);
  if (!input) return undefined;
  const fieldId = uiId(input.fieldId);
  const analytics = record(input.analytics);
  return {
    farmName: string(input.farmName), observedAt: date(input.observedAt), summary: string(input.summary, 2400),
    farmId: typeof input.farmId === "number" && Number.isSafeInteger(input.farmId) && input.farmId > 0 ? input.farmId : undefined,
    fieldId,
    weather: parseWeather(input.weather),
    farms: list(input.farms, 150, (entry) => {
      const farm = record(entry);
      const name = string(farm?.name, 150);
      const id = farm?.id;
      return typeof id === "number" && Number.isSafeInteger(id) && id > 0 && name ? { id, name } : undefined;
    }),
    mode: (["FARM", "CAMERA", "FIELD", "IMAGE"] as const).find((mode) => mode === input.mode),
    kind: (["MATURITY", "PHENOLOGY", "GROWTH"] as const).find((kind) => kind === input.kind),
    parcels: list(input.parcels, 500, (entry) => {
      const parcel = record(entry);
      const name = string(parcel?.name);
      if (!parcel || !name) return undefined;
      const stages: MaturityStage[] = ["UNRIPE", "TURNING", "RIPE", "OVER_RIPE", "MIXED", "UNKNOWN"];
      const stage = stages.find((value) => value === parcel.stage) ?? "UNKNOWN";
      const latitude = typeof parcel.latitude === "number" && Number.isFinite(parcel.latitude) && Math.abs(parcel.latitude) <= 90 ? parcel.latitude : undefined;
      const longitude = typeof parcel.longitude === "number" && Number.isFinite(parcel.longitude) && Math.abs(parcel.longitude) <= 180 ? parcel.longitude : undefined;
      const observations = list(parcel.observations, 100, (entry) => {
        const observation = record(entry);
        const cameraName = string(observation?.cameraName);
        if (!observation || !cameraName) return undefined;
        let imageUrl: string | undefined;
        try { const url = new URL(String(observation.imageUrl)); if (["http:", "https:"].includes(url.protocol) && !url.username && !url.password) imageUrl = url.href; } catch { /* ignore invalid image URLs */ }
        return { cameraId: uiId(observation.cameraId), cameraName, stage: stages.find((value) => value === observation.stage) ?? (observation.stage === "OVERRIPE" ? "OVER_RIPE" : "UNKNOWN"), reason: string(observation.reason, 2000), capturedAt: date(observation.capturedAt), imageUrl, source: uiId(observation.source), quality: string(observation.quality, 60), status: string(observation.status, 60), label: string(observation.label ?? observation.phenologyStage ?? observation.growthStage, 120) };
      });
      return { fieldId: uiId(parcel.fieldId), name, latitude, longitude, stage, reason: string(parcel.reason, 2000), observations };
    }),
    items: list(input.items, 30, parseItem),
    metrics: list(input.metrics, 12, parseItem),
    series: parseChartSeries(analytics?.series ?? input.series),
    thresholds: parseThresholds(analytics?.thresholds ?? input.thresholds),
    fields: list(input.fields, 12, (entry) => {
      const field = record(entry);
      const id = uiId(field?.id);
      const label = string(field?.label);
      if (!id || !label) return undefined;
      const options = list(field?.options, 40, (option) => {
        const item = record(option);
        const label = string(item?.label);
        const value = string(item?.value);
        return label && value ? { label, value } : undefined;
      });
      return { id, label, required: field?.required !== false, options };
    }),
    locations: list(input.locations, 50, (entry) => {
      const point = record(entry);
      const id = uiId(point?.id);
      const label = string(point?.label);
      const latitude = point?.latitude;
      const longitude = point?.longitude;
      if (!id || !label || typeof latitude !== "number" || typeof longitude !== "number"
        || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return undefined;
      return { id, label, latitude, longitude };
    }),
    runId: uiId(input.runId), diagnosisId: uiId(input.diagnosisId),
    analysisRunId: uiId(input.analysisRunId), taskId: uiId(input.taskId),
    taskView: (["tasks", "calendar", "records"] as const).find((value) => value === input.taskView),
    taskFilter: (["active", "pending", "in-progress", "completed"] as const).find((value) => value === input.taskFilter),
    from: localDay(input.from), to: localDay(input.to),
    inspection: parseInspection(input),
    question: string(input.question, 600), originalRequest: string(input.originalRequest, 1200),
    phenologyTimeline: parsePhenologyTimeline(input.phenologyTimeline),
    sprayWeather: parseSprayWeather(input),
    pestComparison: parsePestComparison(input.pestComparison ?? input),
    comparisonMatrix: parseComparisonMatrix(input.comparisonMatrix ?? input.matrix, fieldId),
    actionChecklist: parseActionChecklist(input.actionChecklist ?? input.checklist, fieldId),
    memoryProposal: parseMemoryProposal(input.memoryProposal),
    route: workspaceNavigationRoute(input.route),
    atlasFigures: parseAtlasFigures(input.atlasFigures),
  };
}

function parseMemoryProposal(value: unknown): MemoryProposalData | undefined {
  const proposal = record(value);
  const proposalId = uiId(proposal?.proposalId);
  const displayText = string(proposal?.displayText, 300);
  const scope = proposal?.scope;
  return proposalId && /^proposal-[A-Za-z0-9-]{8,84}$/.test(proposalId) && displayText
      && (scope === "FIELD" || scope === "FARM")
    ? { proposalId, displayText, scope, confirmed: proposal?.status === "CONFIRMED" } : undefined;
}

function parsePhenologyTimeline(value: unknown): PhenologyTimelineData | undefined {
  const phase = record(value);
  if (!phase) return undefined;
  const stages = list(phase.stages ?? phase.phaseStages ?? phase.timeline, 12, (entry) => {
    if (typeof entry === "string") {
      const label = string(entry, 80);
      return label ? { label } : undefined;
    }
    const stage = record(entry);
    const label = string(stage?.label ?? stage?.name ?? stage?.title, 80);
    if (!label) return undefined;
    return {
      code: uiId(stage?.code ?? stage?.stageCode),
      label,
      from: string(stage?.from ?? stage?.startDate, 40),
      to: string(stage?.to ?? stage?.endDate, 40),
      threshold: numberInRange(stage?.threshold ?? stage?.gdd, -1_000_000, 1_000_000),
    } satisfies PhenologyStage;
  });
  const rawAccumulation = record(phase.accumulation)
    ?? record(phase.thermalAccumulation)
    ?? record(phase.heatAccumulation);
  const currentAccumulation = numberInRange(
    rawAccumulation?.current ?? rawAccumulation?.accumulated ?? rawAccumulation?.value
      ?? phase.accumulatedTemperature ?? phase.gdd ?? phase.growingDegreeDays,
    -1_000_000, 1_000_000,
  );
  const targetAccumulation = numberInRange(
    rawAccumulation?.target ?? rawAccumulation?.required ?? rawAccumulation?.total ?? phase.thermalTarget,
    0, 1_000_000,
  );
  const accumulation = currentAccumulation !== undefined ? {
    current: currentAccumulation,
    target: targetAccumulation,
    progress: numberInRange(rawAccumulation?.progress, 0, 100),
    unit: string(rawAccumulation?.unit, 20) ?? "℃·d",
  } satisfies PhenologyAccumulation : undefined;
  return {
    fieldId: uiId(phase.fieldId),
    fieldName: string(phase.fieldName, 120), crop: string(phase.crop, 64), date: string(phase.date, 90),
    current: string(phase.current, 80), previous: string(phase.previous, 80), next: string(phase.next, 80),
    currentIndex: integerInRange(phase.currentIndex ?? phase.currentStageIndex, 0, 11),
    stages: stages.length ? stages : undefined,
    daysToNext: integerInRange(phase.daysToNext ?? phase.daysUntilNext ?? phase.countdownDays ?? phase.nextInDays, 0, 366),
    registered: string(phase.registered, 80), seasonal: string(phase.seasonal, 80),
    expectedRegisteredStage: string(phase.expectedRegisteredStage, 80),
    calibrationTarget: uiId(phase.calibrationTarget),
    basis: string(phase.basis, 460),
    review: phase.review === true || phase.mismatch === true || phase.stageMismatch === true,
    accumulation,
  };
}

function workLevel(value: unknown): WorkSuitabilityLevel | undefined {
  if (typeof value !== "string") return undefined;
  const level = value.trim().toLowerCase();
  if (["suitable", "good", "green", "适宜", "可作业"].includes(level)) return "suitable";
  if (["caution", "warning", "yellow", "谨慎", "注意"].includes(level)) return "caution";
  if (["unsuitable", "poor", "bad", "red", "不宜", "禁止", "不可作业"].includes(level)) return "unsuitable";
  if (["unknown", "unavailable", "pending", "待核验", "缺测", "未知"].includes(level)) return "unknown";
  return undefined;
}

function scoreWorkLevel(score: number): WorkSuitabilityLevel {
  if (score >= 75) return "suitable";
  if (score >= 45) return "caution";
  return "unsuitable";
}

function parseWorkSuitability(value: unknown): WorkSuitability | undefined {
  if (typeof value === "number") {
    const score = numberInRange(value, 0, 100);
    return score === undefined ? undefined : { level: scoreWorkLevel(score), score };
  }
  if (typeof value === "string") {
    const level = workLevel(value);
    return level ? { level } : undefined;
  }
  const assessment = record(value);
  if (!assessment) return undefined;
  const score = numberInRange(assessment.score, 0, 100);
  const level = workLevel(assessment.level ?? assessment.status ?? assessment.suitability ?? assessment.rating)
    ?? (score !== undefined ? scoreWorkLevel(score) : undefined);
  if (!level) return undefined;
  return { level, score, reason: string(assessment.reason ?? assessment.detail ?? assessment.summary, 180) };
}

function parseWorkDay(value: unknown): WorkDayAssessment | undefined {
  const day = record(value);
  if (!day) return undefined;
  const date = localDay(day.date ?? day.day)
    ?? localDay(typeof day.startsAt === "string" ? day.startsAt.slice(0, 10) : undefined);
  if (!date) return undefined;
  const operations = record(day.operations);
  const pick = (...keys: string[]) => parseWorkSuitability(keys.map((key) => operations?.[key] ?? day[key]).find((candidate) => candidate !== undefined));
  const spray = pick("spray", "spraying", "pesticide");
  const irrigation = pick("irrigation", "fertigation", "fertilization", "fertilize", "watering", "water");
  const harvest = pick("harvest", "harvesting");
  return spray || irrigation || harvest ? { date, spray, irrigation, harvest } : undefined;
}

function operationKey(value: unknown): "spray" | "irrigation" | "harvest" | undefined {
  if (typeof value !== "string") return undefined;
  const key = value.trim().toLowerCase();
  if (["spray", "pesticide", "打药", "施药"].includes(key)) return "spray";
  if (["irrigation", "fertigation", "fertilization", "fertilize", "watering", "water", "施肥", "灌水", "施肥灌水"].includes(key)) return "irrigation";
  if (["harvest", "harvesting", "采收", "采摘"].includes(key)) return "harvest";
  return undefined;
}

function parseOperationWindows(input: Record<string, unknown>): WorkDayAssessment[] {
  const raw = input.operationWindows ?? input.agriculturalWindows ?? input.workSuitability;
  if (!Array.isArray(raw)) return [];
  const grouped = new Map<string, WorkDayAssessment>();
  for (const entry of raw.slice(0, 36)) {
    const item = record(entry);
    const key = operationKey(item?.operation ?? item?.kind ?? item?.type);
    const date = localDay(item?.date ?? item?.day)
      ?? localDay(typeof item?.startsAt === "string" ? item.startsAt.slice(0, 10) : undefined);
    const assessment = parseWorkSuitability(item?.suitability ?? item?.assessment ?? item);
    if (!key || !date || !assessment) continue;
    const day = grouped.get(date) ?? { date };
    day[key] = assessment;
    grouped.set(date, day);
  }
  return [...grouped.values()].sort((left, right) => left.date.localeCompare(right.date)).slice(0, 3);
}

function parseWorkDays(input: Record<string, unknown>): WorkDayAssessment[] {
  const direct = input.workDays ?? input.workWindows;
  const days = new Map<string, WorkDayAssessment>();
  for (const entry of Array.isArray(direct) ? direct.slice(0, 12) : []) {
    const parsed = parseWorkDay(entry);
    if (parsed) days.set(parsed.date, parsed);
  }
  for (const parsed of parseOperationWindows(input)) {
    const current = days.get(parsed.date);
    days.set(parsed.date, {
      date: parsed.date,
      spray: parsed.spray ?? current?.spray,
      irrigation: parsed.irrigation ?? current?.irrigation,
      harvest: parsed.harvest ?? current?.harvest,
    });
  }
  return [...days.values()].sort((left, right) => left.date.localeCompare(right.date)).slice(0, 3);
}

function parseSprayWeather(input: Record<string, unknown>): SprayWeatherData | undefined {
  const windows = list(input.weatherWindows, 24, (value) => {
    const window = record(value);
    const startsAt = date(window?.startsAt);
    const score = weatherNumber(window?.score, 0, 100);
    if (!startsAt || score === undefined) return undefined;
    return {
      id: startsAt,
      startsAt,
      endsAt: date(window?.endsAt),
      score,
      wind: weatherNumber(window?.windSpeedKph ?? window?.wind, 0, 500),
      rain: weatherNumber(window?.precipitationMm ?? window?.rain, 0, 2500),
      rainProbability: weatherNumber(window?.rainProbability ?? window?.precipitationProbability, 0, 100),
      temperature: weatherNumber(window?.temperatureC ?? window?.temperature, -100, 100),
      humidity: weatherNumber(window?.humidityPct ?? window?.humidity, 0, 100),
    };
  });
  const operationWindows = list(input.operationWindows, 24, (value): WorkTimeSlot | undefined => {
    const startsAt = date(record(value)?.startsAt);
    const operations = parseWorkDay(value);
    return startsAt && operations ? { ...operations, startsAt } : undefined;
  });
  const workDays = parseWorkDays(input);
  const rank = { unknown: 0, unsuitable: 1, caution: 2, suitable: 3 };
  const summaries = new Map(workDays.map((day) => [day.date, day]));
  for (const window of operationWindows) {
    const summary = summaries.get(window.date) ?? { date: window.date };
    for (const key of ["spray", "irrigation", "harvest"] as const) {
      const assessment = window[key];
      const previous = summary[key];
      if (assessment && (!previous || rank[assessment.level] > rank[previous.level])) summary[key] = assessment;
    }
    summaries.set(window.date, summary);
  }
  const operationDecision = string(input.operationDecision, 80);
  const phiDays = integerInRange(input.phiDays, 0, 3650);
  const verifiedProductName = string(input.verifiedProductName, 120);
  if (!windows.length && !summaries.size && !operationDecision && phiDays === undefined) return undefined;
  return {
    source: string(input.source, 80),
    rainHorizonHours: weatherNumber(input.rainHorizonHours, 1, 72),
    restrictionsVerified: input.useRestrictionsVerified === true || input.restrictionsVerified === true,
    windows,
    workDays: [...summaries.values()].sort((left, right) => left.date.localeCompare(right.date)).slice(0, 3),
    operationWindows,
    operationDecision,
    phiDays,
    verifiedProductName,
  };
}

function localDay(value: unknown): string | undefined {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) ? value : undefined;
}

function weatherNumber(value: unknown, min: number, max: number): number | undefined {
  const parsed = typeof value === "string" && /^-?\d+(?:\.\d+)?\s*(?:°C|℃|°|%|mm)?$/iu.test(value.trim()) ? Number.parseFloat(value) : value;
  return typeof parsed === "number" && Number.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : undefined;
}

function parseWeather(value: unknown): AgentWeather | undefined {
  const w = record(value);
  if (!w) return undefined;
  const temperature = weatherNumber(w.temperature, -100, 100);
  const humidity = weatherNumber(w.humidity, 0, 100);
  return {
    farmName: string(w.farmName, 120), observedAt: string(w.observedAt, 60), condition: string(w.condition, 60),
    temperature: temperature === undefined ? undefined : String(temperature), humidity: humidity === undefined ? undefined : String(humidity),
    windDirection: string(w.windDirection, 40), windPower: string(w.windPower, 40), source: string(w.source, 80),
    forecast: list(w.forecast, 7, (entry) => {
      const day = record(entry);
      const observedDay = localDay(day?.date);
      if (!day || !observedDay) return undefined;
      return { date: observedDay, condition: string(day.condition, 60), temperatureMin: weatherNumber(day.temperatureMin, -100, 100),
        temperatureMax: weatherNumber(day.temperatureMax, -100, 100), precipitationProbability: weatherNumber(day.precipitationProbability, 0, 100),
        precipitationMm: weatherNumber(day.precipitationMm, 0, 2500) };
    }),
  };
}

function parseInspection(input: Record<string, unknown>): BlockData["inspection"] {
  const { total, processed, captured, valid } = input;
  if (typeof total !== "number" || typeof processed !== "number" || typeof captured !== "number" || typeof valid !== "number"
    || ![total, processed, captured, valid].every(Number.isSafeInteger) || valid < 0
    || valid > captured || captured > processed || processed > total) return undefined;
  return { total, processed, captured, valid };
}

/** Detail hrefs are app routes, never arbitrary URLs from an event. */
export function detailRoute(value: unknown): string | undefined {
  const path = typeof value === "string" ? value : "";
  return /^\/(?:overview|fields|monitoring|planning|farm\/(?:map|fields|devices)|weather|monitor|operations(?:\/[\w.-]+)?|diagnosis(?:\/[\w.-]+)?|analysis(?:\/[\w.-]+)?|knowledge(?:\/(?:library|expert-review|cases))?)(?:\?[\w=&.%-]+)?$/u.test(path)
    && !path.split(/[/?]/u).some((segment) => segment === ".." || segment === ".") ? path : undefined;
}

function parseAction(input: unknown, fallbackFieldId?: string): AgentUiAction | undefined {
  const action = record(input);
  const type = actionTypes.find((candidate) => candidate === action?.type);
  const label = string(action?.label, 60);
  if (!action || !type || !label) return undefined;
  if (type === "expand") return { type, label };
  if (type === "prompt") {
    const value = string(action.value, 1200);
    return value ? { type, label, value } : undefined;
  }
  if (type === "select-field") {
    const value = uiId(action.value);
    return value ? { type, label, value } : undefined;
  }
  if (type === "open-detail") {
    const value = detailRoute(action.value);
    return value ? { type, label, value } : undefined;
  }
  if (type === "draft-task") {
    const draftTask = parseDraftTask(action, fallbackFieldId);
    return draftTask ? { type, label, draftTask } : undefined;
  }
  if (type === "open-evidence-drawer") {
    const payload = record(action.payload);
    const evidenceId = evidenceRef(action.evidenceId ?? action.value ?? payload?.evidenceId ?? action.payload);
    return evidenceId ? { type, label, evidenceId } : undefined;
  }
  const payload = record(action.payload);
  const proposalId = uiId(action.proposalId ?? action.value ?? payload?.proposalId);
  if (!proposalId || !/^proposal-[A-Za-z0-9-]{8,84}$/u.test(proposalId)) return undefined;
  const rawDecision = action.decision ?? payload?.decision;
  const decision: MemoryActionDecision = typeof rawDecision === "string" && rawDecision.toLowerCase() === "ignore" ? "ignore" : "confirm";
  return { type, label, proposalId, decision };
}

export function parseUiBlock(input: unknown): AgentUiBlock | undefined {
  const raw = record(input);
  const block = record(raw?.block) ?? raw;
  if (!block || block.schemaVersion !== 1) return undefined;
  const blockId = uiId(block.blockId);
  const runId = uiId(block.runId);
  const title = string(block.title);
  const componentType = componentTypes.find((name) => name === block.componentType);
  const status = statuses.find((name) => name === block.status);
  const createdAt = date(block.createdAt);
  const updatedAt = date(block.updatedAt);
  if (!blockId || !runId || !title || !componentType || !status || !createdAt || !updatedAt) return undefined;
  const data = parseData(block.data);
  const dataRef = string(block.dataRef, 260);
  if (!data && !dataRef) return undefined;
  const provenance = list(block.provenance, 20, (entry) => {
    const source = record(entry);
    const label = string(source?.label);
    return label ? { label, reference: string(source?.reference, 600) } : undefined;
  });
  const evidenceIds = [...new Set([
    ...list(block.evidenceIds, 24, evidenceRef),
    ...provenance.flatMap((source) => source.reference && /^E[1-9][0-9]{0,3}$/u.test(source.reference) ? [source.reference] : []),
  ])];
  const parsedActions = list(block.actions, 12, (entry) => parseAction(entry, data?.fieldId));
  const actions = componentType === "WorkspaceNavigationCard"
    ? parsedActions.filter((action) => action.type === "open-detail" && Boolean(data?.route) && action.value === data?.route).slice(0, 1)
    : parsedActions;
  return {
    blockId, runId, title, componentType, schemaVersion: 1, status, createdAt, updatedAt, data, dataRef,
    summary: string(block.summary, 2400),
    provenance,
    evidenceIds,
    actions,
  };
}

export function parseUiBlocks(input: unknown): AgentUiBlock[] {
  return list(input, 40, parseUiBlock);
}

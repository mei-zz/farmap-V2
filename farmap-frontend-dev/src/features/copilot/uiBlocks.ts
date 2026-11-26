export const componentTypes = [
  "FarmDirectoryCard", "ParcelOverviewCard", "WeatherCard", "EnvironmentCard", "CropInfoCard", "FarmHistoryCard",
  "CropGrowthChart", "WeatherTrendChart", "SoilTrendChart", "ParcelCompareChart", "FarmAnalyticsCard",
  "DiagnosisCard", "PestDiagnosisCard", "RiskAlertCard", "DiagnosisEvidenceCard",
  "FarmPlanCard", "FertilizerPlanCard", "IrrigationPlanCard", "FarmCalendarCard", "FarmTaskList",
  "ClarificationForm", "ConfirmationCard", "ApprovalCard", "TaskProgressCard", "FeedbackCard",
  "EvidenceCard", "KnowledgeSourceCard", "ExpertReviewCard", "FarmMapCard", "FarmMaturityCard",
  "FarmInspectionProgress", "FarmRecommendationCard", "FarmAdviceCard", "OperationTaskCard", "OperationsQueryCard",
] as const;

const statuses = ["ready", "loading", "unavailable", "needs-input", "completed", "failed"] as const;
const actionTypes = ["expand", "prompt", "select-field", "open-detail"] as const;

export type BlockComponentType = typeof componentTypes[number];
export type AgentUiAction = { type: typeof actionTypes[number]; label: string; value?: string };
export type BlockItem = { label: string; value?: string | number; unit?: string; status?: string };
export type ChartPoint = { label: string; value: number };
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
  series?: { label: string; points: ChartPoint[] }[];
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

function list<T>(value: unknown, limit: number, parse: (entry: unknown) => T | undefined): T[] {
  return Array.isArray(value) ? value.slice(0, limit).flatMap((entry) => {
    const result = parse(entry);
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

function parseData(raw: unknown): BlockData | undefined {
  const input = record(raw);
  if (!input) return undefined;
  return {
    farmName: string(input.farmName), observedAt: date(input.observedAt), summary: string(input.summary, 2400),
    farmId: typeof input.farmId === "number" && Number.isSafeInteger(input.farmId) && input.farmId > 0 ? input.farmId : undefined,
    fieldId: uiId(input.fieldId),
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
    series: list(input.series, 6, (item) => {
      const series = record(item);
      const label = string(series?.label);
      const points = list(series?.points, 90, (entry) => {
        const point = record(entry);
        const label = string(point?.label, 80);
        return label && typeof point?.value === "number" && Number.isFinite(point.value) ? { label, value: point.value } : undefined;
      });
      return label && points.length > 0 ? { label, points } : undefined;
    }),
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
  return /^\/(?:fields|monitoring|planning|farm\/(?:map|fields|devices)|weather|monitor|operations(?:\/[\w.-]+)?|diagnosis\/[\w.-]+|analysis\/[\w.-]+|knowledge(?:\/(?:library|expert-review|cases))?)(?:\?[\w=&.%-]+)?$/u.test(path)
    && !path.split(/[/?]/u).some((segment) => segment === ".." || segment === ".") ? path : undefined;
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
  return {
    blockId, runId, title, componentType, schemaVersion: 1, status, createdAt, updatedAt, data, dataRef,
    summary: string(block.summary, 2400),
    provenance: list(block.provenance, 20, (entry) => {
      const source = record(entry);
      const label = string(source?.label);
      return label ? { label, reference: string(source?.reference, 600) } : undefined;
    }),
    actions: list(block.actions, 12, (entry) => {
      const action = record(entry);
      const type = actionTypes.find((name) => name === action?.type);
      const label = string(action?.label, 60);
      if (!type || !label) return undefined;
      const value = type === "open-detail" ? detailRoute(action?.value)
        : type === "select-field" ? uiId(action?.value) : string(action?.value, 1200);
      return type === "expand" || value ? { type, label, value } : undefined;
    }),
  };
}

export function parseUiBlocks(input: unknown): AgentUiBlock[] {
  return list(input, 40, parseUiBlock);
}

import { demoDiagnosis, demoFarm, demoFarmLocations, demoFields, demoInsights, demoMapCrops, demoPrimaryField, demoRecommendations, demoWeather } from "@/demo";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import { getDemoSession } from "@/utils/demoSession";
import { toFarmStore, type CanonicalFarmMapData } from "@/features/farm/mapData";
import { loadAgriculturalContext, type CurrentWeatherContext } from "@/features/agricultural/context";
import { demoFarmCameras, loadFarmCameras } from "@/features/farm/cameras";
import { cropDisplayName } from "@/features/farm/labels";
import type { OperationsTaskRaw } from "@/types/operations";
import type {
  OverviewDecisionItem,
  OverviewFreshness,
  OverviewHealthStatus,
  OverviewOperationTask,
  OverviewRecommendation,
  OverviewRiskPriority,
  OverviewOperations,
  OverviewViewModel,
} from "@/types/overview";

const ndviPoints = [32, 43, 47, 54, 61, 56, 68, 58, 72, 64, 55];

const demoTimestamp = new Date().toISOString();
const demoTasks: OverviewOperationTask[] = [
  { id: "task-a12-drainage", name: "A-12 排水沟检查", time: "09:30", status: "PENDING", priority: "CRITICAL", fieldId: "A-12", fieldName: "A-12 地块", fieldArea: 2.4, source: "UNLINKED", sourceLabel: "当前演示任务 · 未关联建议", updatedAt: demoTimestamp },
  { id: "task-b03-scouting", name: "B-03 病虫害巡查", time: "10:00", status: "PENDING", priority: "HIGH", fieldId: "B-03", fieldName: "B-03 地块", fieldArea: 2.1, source: "UNLINKED", sourceLabel: "当前演示任务 · 未关联建议", updatedAt: demoTimestamp },
  { id: "task-b04-harvest", name: "B-04 采收安排", time: "14:00", status: "PENDING", priority: "MEDIUM", fieldId: "B-04", fieldName: "B-04 地块", fieldArea: 3.5, source: "UNLINKED", sourceLabel: "当前演示任务 · 未关联建议", updatedAt: demoTimestamp },
  { id: "task-device-check", name: "设备例行检查", time: "16:30", status: "PENDING", priority: "LOW", source: "UNLINKED", sourceLabel: "当前演示任务 · 未关联建议", updatedAt: demoTimestamp },
]; 

function createRiskPriorities(): OverviewRiskPriority[] {
  const [leafRisk, rainRisk, harvestRisk] = demoInsights;
  const harvestField = demoFields.find((field) => field.id === "B-04");
  return [
    {
      id: leafRisk.id,
      kind: "RISK",
      priority: "CRITICAL",
      title: leafRisk.title,
      summary: "检测到叶片黄化，可能与近期持续降雨导致的根区积水有关。",
      fieldId: "A-12",
      fieldName: demoPrimaryField.name,
      crop: demoPrimaryField.crop,
      fieldArea: demoPrimaryField.area,
      fieldGrowthStage: demoPrimaryField.growthStage,
      fieldPhenologyProgress: demoPrimaryField.phenologyProgress,
      mapReference: { center: [demoPrimaryField.longitude, demoPrimaryField.latitude], zoom: 15 },
      typeLabel: "病害风险",
      impact: { label: "影响面积", value: `${demoPrimaryField.area} ha` },
      evidenceCount: leafRisk.evidenceCount,
      evidenceSources: leafRisk.basis.split(" + "),
      confidenceLabel: leafRisk.confidence,
      recommendedAction: demoRecommendations[0].detail,
      basis: leafRisk.basis,
      status: "OPEN",
      nextAction: { label: "查看诊断", path: "/diagnosis/a12-demo" },
      secondaryActions: [{ type: "ASK_AI", label: "Ask AI" }],
      freshness: { updatedAt: demoDiagnosis.createdAt },
      tone: "danger",
      signalType: "RISK",
    },
    {
      id: rainRisk.id,
      kind: "RISK",
      priority: "HIGH",
      title: rainRisk.title,
      summary: "预计降雨 58–76 mm，4 个地块可能受影响。",
      typeLabel: "天气风险",
      evidenceCount: rainRisk.evidenceCount,
      evidenceSources: rainRisk.basis.split(" + "),
      confidenceLabel: rainRisk.confidence,
      basis: rainRisk.basis,
      status: "OPEN",
      nextAction: { label: "查看地图", path: "/farm/map" },
      secondaryActions: [{ type: "ASK_AI", label: "Ask AI" }],
      freshness: { updatedAt: demoWeather.updatedAt },
      tone: "blue",
      signalType: "RISK",
    },
    {
      id: harvestRisk.id,
      kind: "OPERATION",
      priority: "MEDIUM",
      title: harvestRisk.title,
      summary: "果实成熟度达到采收标准，建议 7 天内完成采收。",
      fieldId: "B-04",
      fieldName: harvestField?.name,
      crop: harvestField?.crop,
      fieldArea: harvestField?.area,
      fieldGrowthStage: harvestField?.growthStage,
      fieldPhenologyProgress: harvestField?.phenologyProgress,
      mapReference: harvestField ? { center: [harvestField.longitude, harvestField.latitude], zoom: 15 } : undefined,
      typeLabel: "采收窗口",
      impact: harvestField ? { label: "影响范围", value: `${harvestField.area} ha` } : undefined,
      evidenceCount: harvestRisk.evidenceCount,
      evidenceSources: harvestRisk.basis.split(" + "),
      confidenceLabel: harvestRisk.confidence,
      basis: harvestRisk.basis,
      status: "OPEN",
      nextAction: { label: "查看任务", path: "/operations/tasks" },
      secondaryActions: [{ type: "ASK_AI", label: "Ask AI" }],
      freshness: { updatedAt: demoFarm.lastUpdated },
      tone: "green",
      signalType: "OPERATIONAL",
    },
  ];
}

function createDecisionQueue(risks: OverviewRiskPriority[]): OverviewDecisionItem[] {
  const nextStepLabels: Record<string, string> = {
    "a12-leaf-anomaly": "24 小时内巡查排水能力",
    "harvest-window": "7 天内安排采收",
  };
  return risks.map((risk) => ({
    ...risk,
    recommendedAction: nextStepLabels[risk.id] ?? risk.recommendedAction,
  }));
}

function createRecommendations(risks: OverviewRiskPriority[]): OverviewRecommendation[] {
  const leafRisk = risks.find((risk) => risk.id === "a12-leaf-anomaly");
  const harvestSignal = risks.find((risk) => risk.id === "harvest-window");
  const a12RecommendationContext = {
    fieldId: leafRisk?.fieldId,
    fieldName: leafRisk?.fieldName,
    crop: leafRisk?.crop,
    fieldArea: leafRisk?.fieldArea,
    relatedRiskId: leafRisk?.id,
    relatedRiskTitle: leafRisk?.title,
    relatedSignalType: "RISK" as const,
    evidenceCount: leafRisk?.evidenceCount,
    evidenceSources: leafRisk?.evidenceSources,
    relatedRiskConfidenceLabel: leafRisk?.confidenceLabel,
    impact: leafRisk?.impact,
    freshness: { updatedAt: demoDiagnosis.createdAt },
    primaryAction: { label: "查看诊断", path: "/diagnosis/a12-demo" },
    secondaryActions: [{ type: "ASK_AI" as const, label: "Ask AI" }],
  };

  const diagnosisRecommendations = demoRecommendations.map((recommendation, index) => ({
    id: `recommendation-a12-${index + 1}`,
    title: recommendation.title,
    priority: recommendation.priority,
    tone: recommendation.tone,
    detail: recommendation.detail,
    ...a12RecommendationContext,
    evidenceIds: [`a12-evidence-${index + 1}`],
    decisionState: "PROPOSED" as const,
  }));

  const operationalRecommendation: OverviewRecommendation | undefined = harvestSignal
    ? {
        id: "recommendation-b04-harvest",
        title: "安排 B-04 采收",
        priority: "中优先级",
        tone: "medium",
        detail: "根据当前成熟度窗口，在未来 7 天内安排采收批次并确认现场人员。",
        fieldId: harvestSignal.fieldId,
        fieldName: harvestSignal.fieldName,
        crop: harvestSignal.crop,
        fieldArea: harvestSignal.fieldArea,
        relatedSignalId: harvestSignal.id,
        relatedSignalTitle: harvestSignal.title,
        relatedSignalType: "OPERATIONAL",
        evidenceCount: harvestSignal.evidenceCount,
        evidenceSources: harvestSignal.evidenceSources,
        impact: harvestSignal.impact,
        freshness: { updatedAt: demoFarm.lastUpdated },
        evidenceIds: ["b04-evidence-1"],
        decisionState: "PROPOSED",
        primaryAction: { label: "查看任务", path: "/operations/tasks" },
        secondaryActions: [{ type: "ASK_AI", label: "Ask AI" }],
      }
    : undefined;

  return operationalRecommendation
    ? [diagnosisRecommendations[0], operationalRecommendation, ...diagnosisRecommendations.slice(1)]
    : diagnosisRecommendations;
}

function createFreshness(): OverviewFreshness[] {
  return [
    { source: "farm", updatedAt: demoFarm.lastUpdated, mode: "DEMO" },
    { source: "weather", updatedAt: demoWeather.updatedAt, mode: "DEMO" },
    { source: "diagnosis", updatedAt: demoDiagnosis.createdAt, mode: "DEMO" },
    { source: "operations", updatedAt: demoFarm.lastUpdated, mode: "DEMO" },
    { source: "monitor", updatedAt: demoTimestamp, mode: "DEMO" },
  ];
}

function getDemoHealthStatus(score: number, riskFieldCount: number, hasHighRiskField: boolean): OverviewHealthStatus {
  if (hasHighRiskField || score < 60) return "RISK";
  if (riskFieldCount > 0 || score < 80) return "WATCH";
  return "HEALTHY";
}

export function getDemoOverviewViewModel(): OverviewViewModel {
  const riskPriorities = createRiskPriorities();
  const riskSignals = riskPriorities.filter((item) => item.signalType === "RISK");
  const riskFieldIds = new Set(riskSignals.map((item) => item.fieldId).filter(Boolean));
  const riskFieldCount = riskFieldIds.size;
  const affectedArea = riskSignals.reduce((area, item) => area + (item.fieldArea ?? 0), 0);
  const healthStatus = getDemoHealthStatus(demoFarm.health, riskFieldCount, demoFields.some((field) => field.status === "risk"));
  const healthStatusLabels: Record<OverviewHealthStatus, string> = {
    HEALTHY: "整体健康",
    WATCH: "需要关注",
    RISK: "存在高风险",
    EMPTY: "暂无数据",
    UNAVAILABLE: "数据不可用",
  };
  return {
    mode: "DEMO",
    farm: { ...demoFarm },
    primaryField: { ...demoPrimaryField },
    health: {
      score: demoFarm.health,
      status: healthStatus,
      statusLabel: healthStatusLabels[healthStatus],
      summary: `整体健康度 ${demoFarm.health}，当前有 ${riskFieldCount} 个风险地块影响 ${affectedArea.toFixed(1)} ha。`,
      trend: 6,
      fieldCount: demoFarm.fieldCount,
      riskFieldCount,
      affectedArea,
      totalArea: demoFarm.acreage,
      devicesOnline: demoFarm.devicesOnline,
      devicesTotal: demoFarm.devicesTotal,
    },
    decisionQueue: createDecisionQueue(riskPriorities),
    riskPriorities,
    recommendations: createRecommendations(riskPriorities),
    operations: {
      status: "READY",
      pendingApproval: 0,
      pendingExecution: demoTasks.length,
      inProgress: 0,
      overdue: 0,
      tasks: demoTasks,
    },
    trends: [{ id: "ndvi", label: "NDVI 长势趋势", unit: "NDVI", currentValue: "0.72", changeLabel: "↓ -8%", points: ndviPoints.map((value, index) => ({ label: ["7/1", "7/15", "8/1", "8/15", "9/1"][Math.min(Math.floor(index / 2.75), 4)], value })) }],
    weather: { ...demoWeather, status: "READY" as const, days: [...demoWeather.days] },
    map: { center: demoFarm.coordinates, zoom: 13, crops: demoMapCrops, locations: demoFarmLocations },
    cameras: demoFarmCameras,
    monitor: { deviceId: "CAM-A12-03", deviceName: "Camera-03", fieldId: "A-12", status: "online", statusLabel: "在线", observedAt: demoTimestamp },
    recentDiagnoses: demoFields.slice(0, 3).map((field) => ({
      id: field.id === "A-12" ? demoDiagnosis.id : `demo-diagnosis-${field.id}`,
      fieldId: field.id,
      fieldName: field.name,
      status: field.status,
      statusLabel: field.statusLabel,
              confidence: field.health,
      observedAt: field.id === "A-12" ? "2026-09-03" : "2026-09-01",
    })),
    diagnosis: { id: demoDiagnosis.id, title: demoDiagnosis.title, severity: demoDiagnosis.severity, confidence: demoDiagnosis.confidence, summary: demoDiagnosis.summary },
    platform: { agent: "READY", operations: "READY", expertReview: "READY" },
    freshness: createFreshness(),
  };
}

/** Honest placeholder used while the real farm contract is loading or unavailable. */
export function createUnavailableOverviewViewModel(): OverviewViewModel {
  const now = new Date().toISOString();
  const freshness = ["farm", "weather", "diagnosis", "operations", "monitor"].map((source) => ({ source: source as OverviewFreshness["source"], updatedAt: now, mode: "UNAVAILABLE" as const }));
  const farm = { id: -1, name: "未选择农场", location: "—", address: "—", fieldCount: 0, acreage: 0, devicesOnline: 0, devicesTotal: 0, coordinates: [0, 0] as [number, number], lastUpdated: now };
  const primaryField = { id: "UNASSIGNED", name: "未选择地块", crop: "—", variety: "—", area: 0, growthStage: "—", phenologyProgress: 0, health: 0, ndvi: 0, soilMoisture: 0, rainfall14d: 0, elevation: 0, slope: 0, soilType: "不可用", status: "attention" as const, statusLabel: "数据不可用", latitude: 0, longitude: 0 };
  return { mode: "UNAVAILABLE", farm, primaryField, health: { score: 0, status: "UNAVAILABLE", statusLabel: "数据不可用", reason: "真实农场上下文尚未加载。", summary: "真实农场上下文尚未加载，系统不会以演示数据替代。", trend: 0, fieldCount: 0, riskFieldCount: 0, affectedArea: 0, totalArea: 0, devicesOnline: 0, devicesTotal: 0 }, decisionQueue: [], riskPriorities: [], recommendations: [], operations: { status: "UNAVAILABLE", reason: "真实 Operations 上下文尚未加载。", pendingApproval: 0, pendingExecution: 0, inProgress: 0, overdue: 0, tasks: [] }, trends: [{ id: "ndvi", label: "NDVI 长势趋势", unit: "NDVI", currentValue: "—", changeLabel: "数据不可用", points: [] }], weather: { summary: "天气数据不可用", temperature: "—", location: "—", rainfallForecast: "—", updatedAt: now, status: "UNAVAILABLE" as const, reason: "真实天气上下文尚未加载。", days: [] }, map: { center: [0, 0], zoom: 12, crops: [], locations: [] }, cameras: [], monitor: { deviceId: "UNAVAILABLE", deviceName: "未配置设备", fieldId: "UNASSIGNED", status: "offline", statusLabel: "不可用", observedAt: now }, recentDiagnoses: [], diagnosis: { id: "UNAVAILABLE", title: "暂无诊断", severity: "low", confidence: 0, summary: "暂无可验证诊断快照。" }, platform: { agent: "UNAVAILABLE", operations: "UNAVAILABLE", expertReview: "UNAVAILABLE" }, freshness };
}

type RealField = { fieldId: string; name: string; crop?: string; area?: number; growthStage?: string; latitude?: number; longitude?: number; status?: string };
type ApiEnvelope<T> = { data?: T; msg?: string };
type RealFieldContext = { farm?: Record<string, unknown>; field?: Record<string, unknown>; crop?: Record<string, unknown>; location?: Record<string, unknown>; weather?: Record<string, unknown>; status?: string; sourceMode?: string; freshness?: string };
type RealOperationsResponse = { data?: { items?: OperationsTaskRaw[]; count?: number }; msg?: string };

function parseCenter(center?: string): [number, number] { if (!center) return [0, 0]; const parts = center.split(",").map(Number); return parts.length >= 2 && parts.every(Number.isFinite) ? [parts[0], parts[1]] : [0, 0]; }
function numberValue(value: unknown, fallback = 0): number { return typeof value === "number" && Number.isFinite(value) ? value : fallback; }
function optionalNumber(value: unknown): number | undefined { return typeof value === "number" && Number.isFinite(value) ? value : undefined; }
function stringValue(value: unknown, fallback = "—"): string { return typeof value === "string" && value.trim() ? value : fallback; }

function weatherDayIcon(condition?: string, code?: number): "cloud" | "sun" | "rain" {
  if (code === 0 || condition === "晴") return "sun";
  if ((code !== undefined && (code >= 51 || code === 45 || code === 48)) || condition?.includes("雨")) return "rain";
  return "cloud";
}

function weatherDayLabel(date: string | undefined, index: number): string {
  if (index === 0) return "今天";
  if (!date) return `第${index + 1}天`;
  const parsed = new Date(`${date}T00:00:00`);
  return Number.isNaN(parsed.getTime()) ? `第${index + 1}天` : new Intl.DateTimeFormat("zh-CN", { weekday: "short" }).format(parsed);
}

function mapRealOperations(rawTasks: OperationsTaskRaw[], fields: RealField[]) {
  const fieldById = new Map(fields.map((field) => [field.fieldId, field]));
  const tasks = rawTasks.flatMap((raw, index) => {
    const fieldId = typeof raw.fieldId === "string" ? raw.fieldId : undefined;
    const field = fieldId ? fieldById.get(fieldId) : undefined;
    const normalizedStatus = stringValue(raw.status, "").toUpperCase();
    const status: OverviewOperationTask["status"] | undefined = normalizedStatus === "OPEN" || normalizedStatus === "ASSIGNED"
      ? "PENDING"
      : normalizedStatus === "IN_PROGRESS"
        ? "IN_PROGRESS"
        : normalizedStatus === "COMPLETED"
          ? "COMPLETED"
          : undefined;
    const normalizedPriority = stringValue(raw.priority, "").toUpperCase();
    const priority: OverviewOperationTask["priority"] | undefined = normalizedPriority === "CRITICAL" || normalizedPriority === "HIGH" || normalizedPriority === "MEDIUM" || normalizedPriority === "LOW" ? normalizedPriority as OverviewOperationTask["priority"] : undefined;
    if (!field || !status || !priority) return [];
    const source = stringValue(raw.source, "").toLowerCase();
    const sourceLabel = source === "agent" || source === "ai" || source === "task_tool" ? "AI 建议" : source === "expert" || source === "expert_review" ? "专家复核" : source === "seasonal" || source === "guidance" ? "季节指导" : source === "monitoring" || source === "monitor" || source === "alert" ? "地块监测" : "来源建议未关联";
    const updatedAt = typeof raw.updatedAt === "string" && raw.updatedAt.trim() ? raw.updatedAt : typeof raw.createdAt === "string" && raw.createdAt.trim() ? raw.createdAt : undefined;
    return [{ id: stringValue(raw.taskId, `real-task-${index}`), name: stringValue(raw.title, "未命名农事任务"), time: updatedAt ?? "—", status, priority, fieldId, fieldName: field.name, fieldArea: numberValue(field.area), source: source === "agent" || source === "ai" || source === "task_tool" ? "AI_RECOMMENDATION" as const : source === "expert" || source === "expert_review" ? "FIELD_MONITORING" as const : source === "seasonal" || source === "guidance" ? "SEASONAL_GUIDANCE" as const : "UNLINKED" as const, sourceLabel, updatedAt }];
  });
  return {
    status: tasks.length ? "READY" as const : "EMPTY" as const,
    pendingApproval: 0,
    pendingExecution: tasks.filter((task) => task.status === "PENDING").length,
    inProgress: tasks.filter((task) => task.status === "IN_PROGRESS").length,
    overdue: 0,
    tasks,
    // Avoid Array.prototype.at here: the application is also used in older
    // embedded browser/WebView environments where the method is unavailable.
    updatedAt: (() => {
      const timestamps = tasks.map((task) => task.updatedAt).filter((value): value is string => Boolean(value)).sort();
      return timestamps.length ? timestamps[timestamps.length - 1] : undefined;
    })(),
  };
}

export async function getRealOverviewViewModel(requestedFarmId?: number): Promise<OverviewViewModel> {
  const user = permanence.user.useUserStore();
  const stored = permanence.farm.useFarmStore();
  const farmId = requestedFarmId && requestedFarmId > 0
    ? requestedFarmId
    : stored?.id && stored.id > 0
      ? stored.id
      : user?.farms?.[0]?.id;
  if (!farmId) throw new Error("没有可访问的真实农场");
  const token = permanence.token.useToken();
  const headers = { Authorization: `Bearer ${token}` };
  const mapResponse = await req.get<ApiEnvelope<CanonicalFarmMapData>>(`/api/farms/${farmId}/map-data`, headers);
  if (!mapResponse.data) throw new Error(mapResponse.msg || "农场地图数据不可用");
  const fieldsResponse = await req.get<ApiEnvelope<RealField[]>>(`/api/farms/${farmId}/fields`, headers);
  if (!fieldsResponse.data) throw new Error(fieldsResponse.msg || "地块数据不可用");
  const mapData = mapResponse.data;
  const cameras = await loadFarmCameras(farmId, token).catch(() => []);
  const farmMap = toFarmStore(mapData, { username: user?.username });
  const farm = { id: mapData.farmId, name: mapData.farmName, type: mapData.type, address: mapData.address, center: mapData.center, zoom: mapData.zoom };
  const fields = fieldsResponse.data;
  const fieldById = new Map(fields.map((item) => [item.fieldId, item]));
  const operationsResponse = await req.get<RealOperationsResponse>("/api/operations/tasks", headers).catch(() => undefined);
  const operations: OverviewOperations & { updatedAt?: string } = operationsResponse?.data ? mapRealOperations(Array.isArray(operationsResponse.data.items) ? operationsResponse.data.items : [], fields) : { status: "UNAVAILABLE", reason: "Operations API 不可用。", pendingApproval: 0, pendingExecution: 0, inProgress: 0, overdue: 0, tasks: [], updatedAt: undefined };
  const center = parseCenter(farm.center);
  const primary = fields[0];
  const contextResponse = primary ? await req.get<ApiEnvelope<RealFieldContext>>(`/api/fields/${encodeURIComponent(primary.fieldId)}/context`, headers) : undefined;
  const context = contextResponse?.data;
  const agricultural = await loadAgriculturalContext(farmId, token, { temperatureLimit: 90, diagnosisLimit: 5, fieldId: primary?.fieldId }).catch(() => undefined);
  const now = new Date().toISOString();
  const fieldArea = fields.reduce((sum, item) => sum + numberValue(item.area), 0);
  const field = primary ? { id: primary.fieldId, name: primary.name, crop: cropDisplayName(stringValue(primary.crop, stringValue(context?.crop?.name, stringValue(farm.type)))), variety: "—", area: numberValue(primary.area), growthStage: stringValue(primary.growthStage), phenologyProgress: 0, health: 0, ndvi: 0, soilMoisture: 0, rainfall14d: numberValue(context?.weather?.rainfall14d), elevation: 0, slope: 0, soilType: "不可用", status: "attention" as const, statusLabel: "待诊断", latitude: numberValue(primary.latitude, numberValue(context?.location?.latitude, center[1])), longitude: numberValue(primary.longitude, numberValue(context?.location?.longitude, center[0])) } : { id: "UNASSIGNED", name: "未分配地块", crop: cropDisplayName(stringValue(farm.type)), variety: "—", area: 0, growthStage: "—", phenologyProgress: 0, health: 0, ndvi: 0, soilMoisture: 0, rainfall14d: 0, elevation: 0, slope: 0, soilType: "不可用", status: "attention" as const, statusLabel: "暂无地块", latitude: center[1], longitude: center[0] };
  const weather = context?.weather;
  const currentWeather: CurrentWeatherContext | undefined = agricultural?.currentWeather;
  const weatherStatus = currentWeather?.status ?? (weather?.status === "READY" ? "READY" : weather?.status === "READY_EMPTY" ? "READY_EMPTY" : "UNAVAILABLE");
  const weatherReady = weatherStatus === "READY";
  const weatherReason = stringValue(currentWeather?.reason, weatherStatus === "READY" ? `来源：${currentWeather?.provider ?? "实时天气服务"} · 已返回当前与降雨预测` : weatherStatus === "READY_EMPTY" ? "当前仅有非观测型季节说明，不能作为实时天气序列。" : weatherStatus === "NOT_CONFIGURED" ? "天气服务尚未配置。" : weatherStatus === "CONFIGURED_UNAVAILABLE" ? "天气服务已配置，但当前没有可用观测。" : "真实天气数据不可用。");
  const weatherTemperature = currentWeather?.temperature ? `${currentWeather.temperature}°C` : "—";
  const weatherSummary = currentWeather?.condition ?? (weatherReady ? stringValue(weather?.summary) : weatherStatus === "READY_EMPTY" ? "暂无可信观测天气数据" : "天气数据不可用");
  const weatherUpdatedAt = currentWeather?.observedAt ?? currentWeather?.retrievedAt ?? now;
  const rainfallForecast24h = optionalNumber(currentWeather?.rainfallForecast24h);
  const rainfall14d = optionalNumber(currentWeather?.rainfall14d);
  const rainfallProbability24h = optionalNumber(currentWeather?.rainfallProbability24h);
  const rainfallForecastDays = optionalNumber(currentWeather?.rainfallForecastDays);
  const rainfallForecast = rainfallForecast24h !== undefined
    ? `${rainfallForecast24h.toFixed(1)} mm${rainfallProbability24h !== undefined ? ` · ${rainfallProbability24h}%概率` : ""}`
    : rainfall14d !== undefined
      ? `未来 ${rainfallForecastDays ?? 14} 天 ${rainfall14d.toFixed(1)} mm`
      : "—";
  const forecastDays = (currentWeather?.forecast ?? []).slice(0, 5).map((day, index) => ({
    day: weatherDayLabel(day.date, index),
    icon: weatherDayIcon(day.condition, day.weatherCode),
    high: Number(day.temperatureMax),
    low: Number(day.temperatureMin),
  })).filter((day) => Number.isFinite(day.high) && Number.isFinite(day.low));
  const freshness = ["farm", "weather", "diagnosis", "operations", "monitor"].map((source) => ({ source: source as OverviewFreshness["source"], updatedAt: source === "operations" ? stringValue(operations.updatedAt, now) : stringValue(context?.freshness, now), mode: "REAL" as const }));
  const recentDiagnoses = agricultural?.recentDiagnoses.items.map((item) => ({
    id: item.diagnosisId,
    fieldId: item.fieldId ?? "UNASSIGNED",
    fieldName: fieldById.get(item.fieldId ?? "")?.name ?? item.fieldId ?? "当前农场",
    status: item.riskLevel === "high" ? "risk" as const : item.riskLevel === "medium" ? "attention" as const : "healthy" as const,
    statusLabel: item.status === "COMPLETED" ? "已完成" : stringValue(item.status, "已保存"),
    confidence: typeof item.confidence === "number" ? Math.round(item.confidence <= 1 ? item.confidence * 100 : item.confidence) : undefined,
    observedAt: stringValue(item.createdAt, now),
  })) ?? [];
  const configuredMonitor = cameras[0];
  return { mode: "REAL", farm: { id: farm.id, name: farm.name, location: farm.address || "—", address: farm.address || "—", fieldCount: fields.length, acreage: fieldArea, devicesOnline: 0, devicesTotal: cameras.length, coordinates: center, lastUpdated: now }, primaryField: field, health: { score: 0, status: fields.length ? "UNAVAILABLE" : "EMPTY", statusLabel: fields.length ? "健康评分不可用" : "暂无地块", reason: fields.length ? "当前没有可验证的健康评分或风险信号；Overview 健康 aggregate 尚未接入。" : "该农场尚未建立稳定地块主数据。", summary: fields.length ? "当前没有可验证的健康评分或风险信号。" : "该农场尚未建立稳定地块主数据。", trend: 0, fieldCount: fields.length, riskFieldCount: 0, affectedArea: 0, totalArea: fieldArea, devicesOnline: 0, devicesTotal: cameras.length }, decisionQueue: [], riskPriorities: [], recommendations: [], operations, trends: [{ id: "ndvi", label: "NDVI 长势趋势", unit: "NDVI", currentValue: "—", changeLabel: "暂无趋势", points: [] }], weather: { summary: weatherSummary, temperature: weatherReady ? weatherTemperature : "—", location: farm.address || "—", rainfallForecast, updatedAt: weatherUpdatedAt, status: weatherStatus, reason: weatherReason, days: forecastDays }, map: { center, zoom: farm.zoom || 12, crops: farmMap.crops, locations: farmMap.locations }, cameras, monitor: { deviceId: configuredMonitor?.serial ?? "UNAVAILABLE", deviceName: configuredMonitor?.name ?? "实时监控未配置", fieldId: configuredMonitor?.fieldId, pointId: configuredMonitor?.pointId, status: "offline", statusLabel: configuredMonitor ? "已配置" : "未配置", observedAt: now }, recentDiagnoses, diagnosis: { id: "UNAVAILABLE", title: "暂无诊断", severity: "low", confidence: 0, summary: "暂无可验证诊断快照。" }, platform: { agent: "READY", operations: operations.status === "UNAVAILABLE" ? "UNAVAILABLE" : "READY", expertReview: "READY" }, freshness, agricultural, currentWeather };
}

export async function loadOverviewViewModel(requestedFarmId?: number): Promise<OverviewViewModel> {
  if (getDemoSession()) return getDemoOverviewViewModel();
  return getRealOverviewViewModel(requestedFarmId);
}

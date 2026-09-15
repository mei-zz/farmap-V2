import type { FarmStoreState } from "@/store/farm";
import type { AgriculturalContext, CurrentWeatherContext } from "@/features/agricultural/context";
import type { FarmCamera } from "@/features/farm/cameras";

export type OverviewMode = "REAL" | "DEMO" | "STALE" | "UNAVAILABLE";

export type OverviewPriority = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";

export type OverviewFarm = {
  id: number;
  name: string;
  location: string;
  address: string;
  fieldCount: number;
  acreage: number;
  devicesOnline: number;
  devicesTotal: number;
  coordinates: [number, number];
  lastUpdated: string;
};

export type OverviewField = {
  id: string;
  name: string;
  crop: string;
  variety: string;
  area: number;
  growthStage: string;
  phenologyProgress: number;
  health: number;
  ndvi: number;
  soilMoisture: number;
  rainfall14d: number;
  elevation: number;
  slope: number;
  soilType: string;
  status: "healthy" | "attention" | "risk";
  statusLabel: string;
  latitude: number;
  longitude: number;
};

export type OverviewHealthStatus = "HEALTHY" | "WATCH" | "RISK" | "EMPTY" | "UNAVAILABLE";

export type OverviewHealth = {
  score: number;
  status: OverviewHealthStatus;
  statusLabel: string;
  reason?: string;
  summary: string;
  trend: number;
  fieldCount: number;
  riskFieldCount: number;
  affectedArea: number;
  totalArea: number;
  devicesOnline: number;
  devicesTotal: number;
};

export type OverviewDecisionItem = {
  id: string;
  kind: "RISK" | "APPROVAL" | "OPERATION" | "REVIEW" | "DATA_QUALITY";
  priority: OverviewPriority;
  title: string;
  summary: string;
  fieldId?: string;
  fieldName?: string;
  crop?: string;
  fieldArea?: number;
  fieldGrowthStage?: string;
  fieldPhenologyProgress?: number;
  mapReference?: { center: [number, number]; zoom?: number };
  typeLabel?: string;
  impact?: { label?: string; value?: string };
  evidenceCount?: number;
  evidenceSources?: string[];
  confidenceLabel?: string;
  recommendedAction?: string;
  status: string;
  nextAction: { label: string; path: string };
  secondaryActions?: Array<{ type: "ASK_AI"; label: string }>;
  freshness?: { updatedAt?: string; stale?: boolean };
};

export type OverviewRiskPriority = OverviewDecisionItem & {
  basis: string;
  signalType: "RISK" | "OPERATIONAL" | "INFORMATION";
  tone: "danger" | "blue" | "green";
};

export type OverviewRecommendationState = "PROPOSED" | "NEEDS_REVIEW" | "PENDING_APPROVAL" | "APPROVED" | "REJECTED";

export type OverviewOperationSource = "AI_RECOMMENDATION" | "FIELD_MONITORING" | "SEASONAL_GUIDANCE" | "UNLINKED";

export type OverviewRecommendation = {
  id: string;
  title: string;
  priority: string;
  tone: "high" | "medium";
  detail: string;
  fieldId?: string;
  fieldName?: string;
  crop?: string;
  fieldArea?: number;
  relatedRiskId?: string;
  relatedRiskTitle?: string;
  relatedSignalId?: string;
  relatedSignalTitle?: string;
  relatedSignalType?: "RISK" | "OPERATIONAL" | "INFORMATION";
  evidenceIds: string[];
  evidenceCount?: number;
  evidenceSources?: string[];
  relatedRiskConfidenceLabel?: string;
  impact?: { label?: string; value?: string };
  decisionState: OverviewRecommendationState;
  freshness?: { updatedAt: string; stale?: boolean };
  primaryAction: { label: string; path: string };
  secondaryActions?: Array<{ type: "ASK_AI"; label: string }>;
};

export type OverviewOperationTask = {
  id: string;
  name: string;
  time: string;
  status: "PENDING" | "IN_PROGRESS" | "COMPLETED" | "OVERDUE";
  priority: OverviewPriority;
  fieldId?: string;
  /** Optional presentation metadata. These fields do not imply a live execution record. */
  source?: OverviewOperationSource;
  sourceLabel?: string;
  fieldName?: string;
  fieldArea?: number;
  updatedAt?: string;
};

export type OverviewOperations = {
  status: "READY" | "EMPTY" | "UNAVAILABLE";
  reason?: string;
  pendingApproval: number;
  pendingExecution: number;
  inProgress: number;
  overdue: number;
  tasks: OverviewOperationTask[];
};

export type OverviewTrend = {
  id: string;
  label: string;
  unit: string;
  currentValue: string;
  changeLabel: string;
  points: Array<{ label: string; value: number }>;
};

export type OverviewWeather = {
  summary: string;
  temperature: string;
  location: string;
  rainfallForecast: string;
  updatedAt: string;
  status: "READY" | "READY_EMPTY" | "UNAVAILABLE" | "CONFIGURED_UNAVAILABLE" | "NOT_CONFIGURED";
  reason?: string;
  days: Array<{ day: string; icon: "cloud" | "sun" | "rain"; high: number; low: number }>;
};

export type OverviewMap = {
  center: [number, number];
  zoom: number;
  crops: FarmStoreState["crops"];
  locations: FarmStoreState["locations"];
};

export type OverviewMonitor = {
  deviceId: string;
  deviceName: string;
  fieldId?: string;
  pointId?: string;
  status: "online" | "offline" | "warning";
  statusLabel: string;
  observedAt: string;
};

export type OverviewRecentDiagnosis = {
  id: string;
  fieldId: string;
  fieldName: string;
  status: "healthy" | "attention" | "risk";
  statusLabel: string;
  confidence?: number;
  observedAt: string;
};

export type OverviewPlatform = {
  agent: "READY" | "DEGRADED" | "UNAVAILABLE";
  operations: "READY" | "DEGRADED" | "UNAVAILABLE";
  expertReview: "READY" | "DEGRADED" | "UNAVAILABLE";
};

export type OverviewFreshness = {
  source: "farm" | "weather" | "diagnosis" | "operations" | "monitor";
  updatedAt: string;
  mode: OverviewMode;
};

export type OverviewDiagnosis = {
  id: string;
  title: string;
  severity: "low" | "medium" | "high";
  confidence: number;
  summary: string;
};

export type OverviewViewModel = {
  mode: OverviewMode;
  farm: OverviewFarm;
  primaryField: OverviewField;
  health: OverviewHealth;
  decisionQueue: OverviewDecisionItem[];
  riskPriorities: OverviewRiskPriority[];
  recommendations: OverviewRecommendation[];
  operations: OverviewOperations;
  trends: OverviewTrend[];
  weather: OverviewWeather;
  map: OverviewMap;
  cameras: FarmCamera[];
  monitor: OverviewMonitor;
  recentDiagnoses: OverviewRecentDiagnosis[];
  diagnosis: OverviewDiagnosis;
  platform: OverviewPlatform;
  freshness: OverviewFreshness[];
  agricultural?: AgriculturalContext;
  currentWeather?: CurrentWeatherContext;
};

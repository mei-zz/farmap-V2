import { req } from "@/utils/reqeust";

export type AgriculturalStatus = "READY" | "EMPTY" | "UNAVAILABLE" | "CONFIGURED_UNAVAILABLE" | "NOT_IMPLEMENTED" | "NOT_CONFIGURED";

export type CurrentWeatherStatus = "READY" | "CONFIGURED_UNAVAILABLE" | "NOT_CONFIGURED";

export type CurrentWeatherContext = {
  farmId: number;
  farmName?: string;
  status: CurrentWeatherStatus;
  temperature?: string;
  condition?: string;
  humidity?: string;
  windDirection?: string;
  windPower?: string;
  observedAt?: string;
  retrievedAt?: string;
  provider?: string;
  source?: string;
  freshness?: string;
  reason?: string;
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

export type SeasonalAgriculturalContext = {
  farmId: number;
  month: number;
  farmType?: string;
  summary?: string;
  source: string;
  status: AgriculturalStatus;
  provenance: string;
  entries: Array<{ id?: number; month?: number; text: string }>;
};

export type HistoricalTemperatureSeries = {
  farmId: number;
  status: AgriculturalStatus;
  startDate?: string;
  endDate?: string;
  latestDate?: string;
  latestTemperature?: number;
  latestAccumulatedTemperature?: number;
  observationCount: number;
  returnedCount: number;
  limit: number;
  source: string;
  provenance: string;
  series: Array<{ date?: string; temperature?: number; accumulatedTemperature?: number }>;
};

export type FarmGuidanceContext = {
  farmId: number;
  month: number;
  status: AgriculturalStatus;
  recordCount: number;
  source: string;
  provenance: string;
  entries: Array<{ id?: number; month?: number; guidanceType?: string; text: string; formula?: boolean }>;
};

export type CropObservationSummary = {
  farmId: number;
  status: AgriculturalStatus;
  totalObservations: number;
  startDate?: string;
  endDate?: string;
  latestObservationDate?: string;
  cropTypes: string[];
  phenologyDistribution: Record<string, number>;
  validYieldCount: number;
  validNutrientCount: number;
  diseaseSignalCount: number;
  imageCount: number;
  source: string;
  provenance: string;
};

export type AgriculturalDiagnosisSummary = {
  diagnosisId: string;
  sourceRunId?: string;
  farmId: number;
  fieldId?: string;
  crop?: string;
  status?: string;
  riskLevel?: string;
  confidence?: number;
  summary?: string;
  mode?: string;
  createdAt?: string;
};

export type AgriculturalDiagnosisList = {
  farmId: number;
  fieldId?: string;
  status: AgriculturalStatus;
  count: number;
  limit: number;
  source: string;
  provenance: string;
  items: AgriculturalDiagnosisSummary[];
};

export type AgriculturalAvailability = {
  map: AgriculturalStatus;
  field: AgriculturalStatus;
  seasonalContext: AgriculturalStatus;
  historicalTemperature: AgriculturalStatus;
  guidance: AgriculturalStatus;
  cropObservations: AgriculturalStatus;
  diagnosis: AgriculturalStatus;
  operations: AgriculturalStatus;
  realtimeCamera: AgriculturalStatus;
  vision: AgriculturalStatus;
  realtimeWeather: AgriculturalStatus;
  sensor: AgriculturalStatus;
  soil: AgriculturalStatus;
};

export type HealthPrerequisiteStatus = {
  status: "NOT_IMPLEMENTED" | "READY" | "UNAVAILABLE";
  availableSignals: string[];
  missingSignals: string[];
  provenance: string;
};

export type AgriculturalContext = {
  farmId: number;
  seasonalContext: SeasonalAgriculturalContext;
  historicalTemperature: HistoricalTemperatureSeries;
  guidance: FarmGuidanceContext;
  cropObservations: CropObservationSummary;
  recentDiagnoses: AgriculturalDiagnosisList;
  currentWeather: CurrentWeatherContext;
  availability: AgriculturalAvailability;
  healthPrerequisites: HealthPrerequisiteStatus;
  generatedAt?: string;
};

type ApiEnvelope<T> = { data?: T; msg?: string };

function finiteNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function normalizeCurrentWeather(value: CurrentWeatherContext | null | undefined, farmId: number): CurrentWeatherContext {
  const source = value ?? {
    farmId,
    status: "CONFIGURED_UNAVAILABLE" as const,
    reason: "天气服务暂未返回有效数据。",
  };
  return {
    ...source,
    farmId: source.farmId ?? farmId,
    rainfall14d: finiteNumber(source.rainfall14d),
    rainfallForecast24h: finiteNumber(source.rainfallForecast24h),
    rainfallProbability24h: finiteNumber(source.rainfallProbability24h),
    rainfallForecastDays: finiteNumber(source.rainfallForecastDays),
    forecast: Array.isArray(source.forecast)
      ? source.forecast.map((day) => ({
        ...day,
        precipitationMm: finiteNumber(day.precipitationMm),
        precipitationProbability: finiteNumber(day.precipitationProbability),
        weatherCode: finiteNumber(day.weatherCode),
      }))
      : [],
  };
}

export async function loadAgriculturalContext(farmId: number, token?: string, options?: { temperatureLimit?: number; diagnosisLimit?: number; fieldId?: string }): Promise<AgriculturalContext> {
  const params = new URLSearchParams();
  params.set("temperatureLimit", String(options?.temperatureLimit ?? 90));
  params.set("diagnosisLimit", String(options?.diagnosisLimit ?? 5));
  if (options?.fieldId) params.set("fieldId", options.fieldId);
  const response = await req.get<ApiEnvelope<AgriculturalContext>>(`/api/farms/${farmId}/agricultural-context?${params.toString()}`, { Authorization: `Bearer ${token ?? ""}` });
  if (!response.data) throw new Error(response.msg || "农业上下文不可用");
  return { ...response.data, currentWeather: normalizeCurrentWeather(response.data.currentWeather, farmId) };
}

export async function loadCurrentWeather(farmId: number, token?: string): Promise<CurrentWeatherContext> {
  const response = await req.get<ApiEnvelope<CurrentWeatherContext>>(`/api/farms/${farmId}/current-weather`, { Authorization: `Bearer ${token ?? ""}` });
  if (!response.data) throw new Error(response.msg || "实时天气不可用");
  return normalizeCurrentWeather(response.data, farmId);
}

export async function loadGuidanceContext(farmId: number, token?: string, month = new Date().getMonth()): Promise<FarmGuidanceContext> {
  const response = await req.get<ApiEnvelope<FarmGuidanceContext>>(`/api/farms/${farmId}/guidance-context?month=${month}`, { Authorization: `Bearer ${token ?? ""}` });
  if (!response.data) throw new Error(response.msg || "农业指导不可用");
  return response.data;
}

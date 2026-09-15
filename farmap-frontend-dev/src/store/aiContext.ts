import { create } from "zustand";

export type AIFieldContext = {
  id: string;
  name: string;
  area: number;
  crop: string;
};

export type AICropContext = {
  name: string;
  variety?: string;
  growthStage?: string;
};

export type AILocationContext = {
  label: string;
  latitude: number;
  longitude: number;
};

export type AIWeatherContext = {
  summary: string;
  temperature: string;
  precipitation: string;
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

export type AIPhenologyContext = {
  stage: string;
  progress: number;
  accumulatedTemperature?: string;
};

export type AIImageContext = {
  id: string;
  name: string;
  url?: string;
};

export type AIDeviceContext = {
  id: string;
  name: string;
  status: "online" | "offline" | "warning";
  location?: string;
  farmId?: number;
  fieldId?: string;
  fieldName?: string;
  pointId?: string;
  longitude?: number;
  latitude?: number;
};

export type AIDiagnosisContext = {
  id: string;
  title: string;
  severity: "low" | "medium" | "high";
  confidence?: number;
  summary?: string;
  sourceRecommendationId?: string;
};

/**
 * Canonical context consumed by Copilot, Agent and page-level quick actions.
 * It is derived from the global AIContext; pages must update that store instead
 * of keeping a second Farm/Field selection inside Copilot.
 */
export type CopilotContext = {
  farmId: number;
  farmName: string;
  fieldId?: string;
  fieldName?: string;
  pointId?: string;
  crop?: string;
  growthStage?: string;
  weather?: AIWeatherContext;
  pageKey: string;
  diagnosisId?: string;
  agentRunId?: string;
  taskId?: string;
};

export type AIContext = {
  currentFarm: {
    id: number;
    name: string;
    address: string;
  };
  currentField?: AIFieldContext;
  currentCrop?: AICropContext;
  currentLocation?: AILocationContext;
  currentWeather?: AIWeatherContext;
  currentPhenology?: AIPhenologyContext;
  selectedImages: AIImageContext[];
  selectedDevice?: AIDeviceContext;
  selectedDiagnosis?: AIDiagnosisContext;
  currentPage: string;
  pageKey?: string;
  agentRunId?: string;
  taskId?: string;
  /** Provenance labels copied from a server-assembled FieldContext. */
  sourceModes?: Record<string, string>;
};

export const mockAIContext: AIContext = {
  currentFarm: {
    id: -1,
    name: "未选择农场",
    address: "—",
  },
  currentField: undefined,
  currentCrop: undefined,
  currentLocation: undefined,
  currentWeather: undefined,
  currentPhenology: undefined,
  selectedImages: [],
  selectedDevice: undefined,
  selectedDiagnosis: undefined,
  currentPage: "/overview",
  pageKey: "/overview",
  agentRunId: undefined,
  taskId: undefined,
  sourceModes: { field: "UNAVAILABLE", weather: "UNAVAILABLE", gis: "UNAVAILABLE", camera: "UNAVAILABLE", knowledge: "UNAVAILABLE", historical: "UNAVAILABLE" },
};

export function toCopilotContext(context: AIContext): CopilotContext {
  return {
    farmId: context.currentFarm.id,
    farmName: context.currentFarm.name,
    fieldId: context.currentField?.id || context.selectedDevice?.fieldId,
    fieldName: context.currentField?.name || context.selectedDevice?.fieldName,
    crop: context.currentCrop?.name || context.currentField?.crop || undefined,
    growthStage: context.currentPhenology?.stage || context.currentCrop?.growthStage || undefined,
    pointId: context.selectedDevice?.pointId,
    weather: context.currentWeather,
    pageKey: context.pageKey || context.currentPage,
    diagnosisId: context.selectedDiagnosis?.id,
    agentRunId: context.agentRunId,
    taskId: context.taskId,
  };
}

type AIContextStore = {
  context: AIContext;
  setContext: (patch: Partial<AIContext>) => void;
  resetContext: () => void;
};

export const useAIContextStore = create<AIContextStore>((set) => ({
  context: mockAIContext,
  setContext: (patch) =>
    set((state) => ({
      context: {
        ...state.context,
        ...patch,
      },
    })),
  resetContext: () => set({ context: mockAIContext }),
}));

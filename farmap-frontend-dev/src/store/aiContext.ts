import { create } from "zustand";
import { demoFarm, demoPrimaryField, demoWeather } from "@/demo";

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
};

export type AIDiagnosisContext = {
  id: string;
  title: string;
  severity: "low" | "medium" | "high";
  confidence?: number;
  summary?: string;
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
};

export const mockAIContext: AIContext = {
  currentFarm: {
    id: demoFarm.id,
    name: demoFarm.name,
    address: demoFarm.address,
  },
  currentField: {
    id: demoPrimaryField.id,
    name: demoPrimaryField.name,
    area: demoPrimaryField.area,
    crop: demoPrimaryField.crop,
  },
  currentCrop: {
    name: demoPrimaryField.crop,
    variety: demoPrimaryField.variety,
    growthStage: demoPrimaryField.growthStage,
  },
  currentLocation: {
    label: "A-12 地块中心",
    latitude: 30.051,
    longitude: 103.832,
  },
  currentWeather: {
    summary: demoWeather.summary,
    temperature: demoWeather.temperature,
    precipitation: `未来 24 小时 ${demoWeather.rainfallForecast}`,
    updatedAt: demoWeather.updatedAt,
  },
  currentPhenology: {
    stage: demoPrimaryField.growthStage,
    progress: demoPrimaryField.phenologyProgress,
    accumulatedTemperature: "1,842°C·d",
  },
  selectedImages: [],
  selectedDevice: {
    id: "CAM-A12-01",
    name: "A-12 东侧摄像头",
    status: "online",
    location: "A-12 地块东侧",
  },
  selectedDiagnosis: undefined,
  currentPage: "/overview",
};

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

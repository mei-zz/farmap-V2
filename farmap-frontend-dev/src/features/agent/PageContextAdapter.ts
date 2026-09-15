import { toCopilotContext, type AIContext } from "@/store/aiContext";
import type { AgentContext } from "./contract";

/** One adapter for every page: Agent requests always start from the global AIContext. */
export function PageContextAdapter(context: AIContext): AgentContext {
  const canonical = toCopilotContext(context);
  return {
    farmId: canonical.farmId > 0 ? String(canonical.farmId) : undefined,
    farmName: canonical.farmName,
    fieldId: canonical.fieldId || context.selectedDevice?.fieldId,
    fieldName: canonical.fieldName || context.selectedDevice?.fieldName,
    crop: canonical.crop,
    variety: context.currentCrop?.variety,
    phenologyStage: canonical.growthStage,
    location: context.currentLocation?.label || context.currentFarm?.address,
    latitude: context.currentLocation?.latitude,
    longitude: context.currentLocation?.longitude,
    cameraId: context.selectedDevice?.id,
    pointId: context.selectedDevice?.pointId,
    diagnosisId: canonical.diagnosisId,
    sourceRecommendationId: context.selectedDiagnosis?.sourceRecommendationId,
    imageUrls: (context.selectedImages || []).map((image) => image.url).filter((url): url is string => Boolean(url)),
    weather: context.currentWeather ? { ...context.currentWeather } : {},
    diagnosis: context.selectedDiagnosis ? { ...context.selectedDiagnosis } : {},
    sourceModes: context.sourceModes ?? { field: "UNAVAILABLE", weather: "UNAVAILABLE", gis: "UNAVAILABLE", camera: "UNAVAILABLE", knowledge: "UNAVAILABLE", historical: "UNAVAILABLE" },
    page: canonical.pageKey,
  };
}

export const toAgentContext = PageContextAdapter;

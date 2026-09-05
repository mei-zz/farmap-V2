import type { AIContext } from "@/store/aiContext";
import type { AgentContext } from "./contract";

/** One adapter for every page: Agent requests always start from the global AIContext. */
export function PageContextAdapter(context: AIContext): AgentContext {
  return {
    farmId: context.currentFarm?.id == null ? undefined : String(context.currentFarm.id),
    farmName: context.currentFarm?.name,
    fieldId: context.currentField?.id,
    fieldName: context.currentField?.name,
    crop: context.currentCrop?.name || context.currentField?.crop,
    variety: context.currentCrop?.variety,
    phenologyStage: context.currentPhenology?.stage || context.currentCrop?.growthStage,
    location: context.currentLocation?.label || context.currentFarm?.address,
    latitude: context.currentLocation?.latitude,
    longitude: context.currentLocation?.longitude,
    cameraId: context.selectedDevice?.id,
    imageUrls: (context.selectedImages || []).map((image) => image.url).filter((url): url is string => Boolean(url)),
    weather: context.currentWeather ? { ...context.currentWeather } : {},
    diagnosis: context.selectedDiagnosis ? { ...context.selectedDiagnosis } : {},
    sourceModes: { field: "LOCAL", weather: "LOCAL", gis: "LOCAL", camera: "LOCAL", knowledge: "LOCAL", historical: "LOCAL" },
    page: context.currentPage,
  };
}

export const toAgentContext = PageContextAdapter;

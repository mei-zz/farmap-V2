import type { FarmStoreState } from "@/store/farm";

export type CanonicalFarmMapData = {
  farmId: number;
  farmName: string;
  type?: string;
  address?: string;
  zoom?: number;
  center?: string;
  ownerUserId?: number;
  sourceMode: string;
  coordinateReferenceSystem: string;
  status: string;
  locations: Array<{
    pointId: string;
    id?: number;
    sequence?: number;
    longitude?: number;
    latitude?: number;
  }>;
  crops: Array<{
    pointId: string;
    id?: number;
    longitude?: number;
    latitude?: number;
    observedDate?: string;
    crop?: string;
    info?: Record<string, unknown>;
  }>;
};

type FarmFallback = Partial<Pick<FarmStoreState, "userId" | "username">>;

function numberValue(value: unknown, fallback = 0): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim() && Number.isFinite(Number(value))) return Number(value);
  return fallback;
}

function stringValue(value: unknown, fallback = ""): string {
  return typeof value === "string" && value.trim() ? value : fallback;
}

function cropInfo(source: Record<string, unknown> | undefined): FarmStoreState["crops"][number]["info"] {
  return {
    diseases: stringValue(source?.diseases),
    rate: numberValue(source?.rate),
    size: numberValue(source?.size),
    yield: numberValue(source?.yield),
    phenology: numberValue(source?.phenology),
    growth: numberValue(source?.growth),
    impact: numberValue(source?.impact),
    disease: numberValue(source?.disease),
    potassium: numberValue(source?.potassium),
    phosphorus: numberValue(source?.phosphorus),
    nitrogen: numberValue(source?.nitrogen),
  };
}

export function toFarmStore(data: CanonicalFarmMapData, fallback: FarmFallback = {}): FarmStoreState {
  return {
    id: data.farmId,
    name: data.farmName,
    type: stringValue(data.type, "unknown"),
    address: stringValue(data.address),
    center: stringValue(data.center),
    zoom: numberValue(data.zoom, 12),
    userId: data.ownerUserId ?? fallback.userId ?? -1,
    username: fallback.username ?? "",
    locations: (data.locations ?? []).map((location) => ({
      pointId: location.pointId,
      id: numberValue(location.id),
      latitude: numberValue(location.latitude),
      longitude: numberValue(location.longitude),
    })),
    crops: (data.crops ?? []).map((crop) => ({
      pointId: crop.pointId,
      id: numberValue(crop.id),
      date: stringValue(crop.observedDate),
      latitude: numberValue(crop.latitude),
      longitude: numberValue(crop.longitude),
      url: stringValue(crop.info?.imageUrl),
      info: cropInfo(crop.info),
    })),
  };
}

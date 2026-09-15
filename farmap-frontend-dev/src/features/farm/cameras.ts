import { getDemoSession } from "@/utils/demoSession";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";

export type FarmCamera = {
  cameraId: string;
  serial: string;
  name: string;
  farmId: number;
  farmName: string;
  fieldId?: string;
  fieldName?: string;
  crop?: string;
  growthStage?: string;
  fieldArea?: number;
  fieldSourceMode?: string;
  pointId?: string;
  longitude: number;
  latitude: number;
  sourceLongitude?: number;
  sourceLatitude?: number;
  bindingDistanceMeters?: number;
  bindingStatus: "BOUND" | "UNBOUND" | string;
  bindingMethod?: string;
  status: string;
};

type ApiEnvelope<T> = { data?: T; msg?: string };

export type FarmCameraCapture = {
  cameraId: string;
  serial: string;
  cameraName: string;
  farmId: number;
  fieldId?: string;
  fieldName?: string;
  pointId?: string;
  source: string;
  capturedAt: string;
  imageUrl: string;
};

/** Demo-only cameras keep the read-only showcase coherent with the real contract. */
export const demoFarmCameras: FarmCamera[] = [
  {
    cameraId: "CAM-A12-01",
    serial: "CAM-A12-01",
    name: "A-12 东侧摄像头",
    farmId: 1,
    farmName: "云阳智慧果园",
    fieldId: "A-12",
    fieldName: "A-12 地块",
    pointId: "demo-point-a12-east",
    longitude: 108.1307,
    latitude: 31.1709,
    bindingStatus: "BOUND",
    bindingMethod: "DEMO_CURATED",
    status: "CONFIGURED",
  },
  {
    cameraId: "CAM-A12-02",
    serial: "CAM-A12-02",
    name: "A-12 西侧摄像头",
    farmId: 1,
    farmName: "云阳智慧果园",
    fieldId: "A-12",
    fieldName: "A-12 地块",
    pointId: "demo-point-a12-west",
    longitude: 108.1299,
    latitude: 31.1704,
    bindingStatus: "BOUND",
    bindingMethod: "DEMO_CURATED",
    status: "CONFIGURED",
  },
];

export async function loadFarmCameras(farmId: number, token?: string): Promise<FarmCamera[]> {
  if (getDemoSession()) return demoFarmCameras.filter((camera) => camera.farmId === farmId);
  if (!farmId || farmId < 1) return [];
  const headers = token ? { Authorization: `Bearer ${token}` } : {};
  const response = await req.get<ApiEnvelope<FarmCamera[]>>(`/api/farms/${farmId}/cameras`, headers);
  if (!response.data) throw new Error(response.msg || "农场摄像头数据不可用");
  return response.data;
}

/**
 * Ask the backend to capture the currently selected, owner-authorized camera.
 * The backend persists the image into the same upload boundary consumed by
 * the REAL multimodal Diagnosis endpoint.
 */
export async function captureFarmCamera(
  cameraId: string,
  channelNo = 1,
  quality = 3,
): Promise<FarmCameraCapture> {
  if (!cameraId) throw new Error("请先选择关联摄像头");
  const token = permanence.token.useToken();
  const response = await req.post<{ cameraId: string; channelNo: number; quality: number }, ApiEnvelope<FarmCameraCapture>>(
    "/monitor/capture",
    { cameraId, channelNo, quality },
    { Authorization: `Bearer ${token}` },
  );
  if (!response.data?.imageUrl) throw new Error(response.msg || "摄像头抓拍失败");
  return response.data;
}

export function selectedCameraContext(camera: FarmCamera, online = false) {
  return {
    id: camera.cameraId || camera.serial,
    name: camera.name,
    status: online ? "online" as const : "warning" as const,
    location: [camera.farmName, camera.fieldName || "未绑定地块", camera.pointId || "未绑定地图点"].join(" · "),
    farmId: camera.farmId,
    fieldId: camera.fieldId,
    fieldName: camera.fieldName,
    pointId: camera.pointId,
    longitude: camera.longitude,
    latitude: camera.latitude,
  };
}

export function cameraBindingLabel(camera: FarmCamera): string {
  if (camera.fieldName && camera.pointId) return `${camera.fieldName} · ${camera.pointId}`;
  if (camera.pointId) return `地图点 ${camera.pointId}`;
  return "待绑定地图点";
}

export function cameraSourceLabel(camera: FarmCamera): string {
  return camera.bindingDistanceMeters !== undefined
    ? `关联点距摄像头约 ${Math.round(camera.bindingDistanceMeters)} m`
    : "来源坐标已保留";
}

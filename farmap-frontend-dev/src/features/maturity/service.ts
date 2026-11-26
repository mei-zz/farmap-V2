import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";

export type MaturityObservation = { cameraId?: string; cameraName?: string; stage?: string; reason?: string; capturedAt?: string; imageUrl?: string; source?: string; quality?: string; status?: string; label?: string };
export type MaturityParcel = { fieldId?: string; name?: string; latitude?: number; longitude?: number; stage?: string; reason?: string; scope?: string; observations?: MaturityObservation[]; path?: [number, number][]; polygon?: [number, number][] };
export type MaturityReport = { runId: string; farmId?: number; farmName?: string; mode?: "FARM" | "CAMERA" | "FIELD" | "IMAGE"; kind?: string; topology?: string; observedAt?: string; parcels?: MaturityParcel[]; summary?: string; status?: string; recommendations?: Array<string | { title?: string; detail?: string }> };
const headers = () => ({ Authorization: `Bearer ${permanence.token.useToken()}` });
async function get<T>(url: string): Promise<T> {
  const response = await req.get<{ data?: T; msg?: string }>(url, headers());
  if (response.data == null) throw new Error(response.msg || "成熟度记录暂不可用");
  return response.data;
}
export const getMaturityRun = (runId: string) => get<MaturityReport>(`/api/maturity/runs/${encodeURIComponent(runId)}`);
export const loadMaturityRun = getMaturityRun;
export const getLatestMaturity = (farmId: number) => get<MaturityReport>(`/api/maturity/farms/${farmId}/latest`);
export const getMaturityRuns = (farmId: number) => get<MaturityReport[]>(`/api/maturity/farms/${farmId}/runs`);
export const stageLabel = (stage?: string) => ({ RIPE: "外观成熟", OVER_RIPE: "过熟", OVERRIPE: "过熟", TURNING: "转色中", UNRIPE: "未成熟", MIXED: "阶段混合", UNKNOWN: "无法判断" }[stage || "UNKNOWN"] || stage || "无法判断");


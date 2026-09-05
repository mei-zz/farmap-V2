import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import type { DiagnosisAnalysisRequest, DiagnosisAnalysisResponse } from "./contract";

export type RagMode = "mock" | "real";

export const ragMode: RagMode = import.meta.env.VITE_RAG_MODE === "real" ? "real" : "mock";

type ApiResponse<T> = {
  code?: number;
  msg?: string;
  data?: T;
};

export class DiagnosisApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DiagnosisApiError";
  }
}

export async function analyzeDiagnosis(
  input: DiagnosisAnalysisRequest,
): Promise<DiagnosisAnalysisResponse> {
  const token = permanence.token.useToken();
  const response = await req.post<DiagnosisAnalysisRequest, ApiResponse<DiagnosisAnalysisResponse>>(
    "/diagnosis/analyze",
    input,
    { Authorization: `Bearer ${token}` },
  );

  if (!response.data) {
    throw new DiagnosisApiError(response.msg || "实时分析暂不可用");
  }

  return response.data;
}

export async function submitExpertReview(diagnosisId: string, input: Record<string, unknown>): Promise<Record<string, unknown>> {
  const token = permanence.token.useToken();
  const response = await req.post<Record<string, unknown>, ApiResponse<Record<string, unknown>>>(`/api/expert-review/${encodeURIComponent(diagnosisId)}`, input, { Authorization: `Bearer ${token}` });
  if (!response.data) throw new DiagnosisApiError(response.msg || "专家复核暂不可用");
  return response.data;
}

export async function getExpertReview(diagnosisId: string): Promise<Record<string, unknown> | undefined> {
  const token = permanence.token.useToken();
  const response = await req.get<ApiResponse<Record<string, unknown>>>(`/api/expert-review/${encodeURIComponent(diagnosisId)}`, { Authorization: `Bearer ${token}` });
  return response.data && Object.keys(response.data).length ? response.data : undefined;
}


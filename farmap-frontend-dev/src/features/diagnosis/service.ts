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


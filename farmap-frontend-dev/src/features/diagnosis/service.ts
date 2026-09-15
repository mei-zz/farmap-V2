import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import type { DiagnosisAnalysisRequest, DiagnosisAnalysisResponse } from "./contract";

export type RagMode = "mock" | "real";

// Formal pages are production-first. Set VITE_RAG_MODE=mock only when an
// intentional local/demo fallback is required.
export const ragMode: RagMode = import.meta.env.VITE_RAG_MODE === "mock" ? "mock" : "real";

type ApiResponse<T> = {
  code?: number;
  msg?: string;
  data?: T;
};

export type DiagnosisField = {
  fieldId: string;
  farmId: number;
  name: string;
  crop?: string;
  area?: number;
  growthStage?: string;
  latitude?: number;
  longitude?: number;
  status?: string;
  sourceMode?: string;
};

export async function listDiagnosisFields(farmId: number): Promise<DiagnosisField[]> {
  const token = permanence.token.useToken();
  const response = await req.get<ApiResponse<DiagnosisField[]>>(`/api/farms/${farmId}/fields`, { Authorization: `Bearer ${token}` });
  if (!Array.isArray(response.data)) throw new DiagnosisApiError(response.msg || "当前农场地块数据不可用");
  return response.data;
}

export type DiagnosisImageUploadContext = {
  farmId?: number;
  fieldId?: string;
  pointId?: string;
  cameraId?: string;
};

export async function uploadDiagnosisImages(files: File[], context: DiagnosisImageUploadContext = {}): Promise<string[]> {
  const token = permanence.token.useToken();
  const formData = new FormData();
  files.forEach((file) => formData.append("files", file));
  if (context.farmId !== undefined) formData.append("farmId", String(context.farmId));
  if (context.fieldId) formData.append("fieldId", context.fieldId);
  if (context.pointId) formData.append("pointId", context.pointId);
  if (context.cameraId) formData.append("cameraId", context.cameraId);
  const response = await req.postForm<ApiResponse<string[]>>("/ai-model/upload", formData, { Authorization: `Bearer ${token}` });
  if (!Array.isArray(response.data) || response.data.length === 0) throw new DiagnosisApiError(response.msg || "现场图片上传失败");
  return response.data;
}

export class DiagnosisApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DiagnosisApiError";
  }
}

export type DiagnosisJobStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
export type DiagnosisJob = {
  jobId: string;
  status: DiagnosisJobStatus;
  stage: string;
  message?: string;
  diagnosisId?: string;
  sourceRunId?: string;
  result?: DiagnosisAnalysisResponse;
  error?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type PollingOptions = {
  /** Maximum time the browser will keep a job attached to this page. */
  maxDurationMs?: number;
  /** Number of consecutive network failures tolerated before surfacing an error. */
  maxConsecutiveErrors?: number;
  /** Maximum delay between retries after a transient network failure. */
  maxIntervalMs?: number;
  onError?: (error: unknown, consecutiveErrors: number) => void;
};

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

/** Starts a real Diagnosis without holding the browser request open. */
export async function createDiagnosisJob(input: DiagnosisAnalysisRequest): Promise<DiagnosisJob> {
  const token = permanence.token.useToken();
  const response = await req.post<DiagnosisAnalysisRequest, ApiResponse<DiagnosisJob>>(
    "/diagnosis/jobs",
    input,
    { Authorization: `Bearer ${token}` },
  );
  if (!response.data?.jobId) throw new DiagnosisApiError(response.msg || "诊断任务创建失败");
  return response.data;
}

export async function getDiagnosisJob(jobId: string): Promise<DiagnosisJob> {
  const token = permanence.token.useToken();
  const response = await req.get<ApiResponse<DiagnosisJob>>(
    `/diagnosis/jobs/${encodeURIComponent(jobId)}`,
    { Authorization: `Bearer ${token}` },
  );
  if (!response.data?.jobId) throw new DiagnosisApiError(response.msg || "诊断任务不存在");
  return response.data;
}

export async function pollDiagnosisJob(
  jobId: string,
  onJob: (job: DiagnosisJob) => void,
  intervalMs = 1500,
  options: PollingOptions = {},
): Promise<DiagnosisJob> {
  const startedAt = Date.now();
  const maxDurationMs = options.maxDurationMs ?? 15 * 60 * 1000;
  const maxConsecutiveErrors = options.maxConsecutiveErrors ?? 5;
  const maxIntervalMs = options.maxIntervalMs ?? 10 * 1000;
  let delayMs = intervalMs;
  let consecutiveErrors = 0;
  let job: DiagnosisJob;

  const read = async (): Promise<DiagnosisJob> => {
    try {
      const next = await getDiagnosisJob(jobId);
      consecutiveErrors = 0;
      delayMs = intervalMs;
      onJob(next);
      return next;
    } catch (error: unknown) {
      consecutiveErrors += 1;
      options.onError?.(error, consecutiveErrors);
      if (consecutiveErrors >= maxConsecutiveErrors) throw error;
      delayMs = Math.min(maxIntervalMs, Math.max(intervalMs, delayMs * 2));
      throw new RetryablePollingError();
    }
  };

  while (true) {
    if (Date.now() - startedAt > maxDurationMs) {
      throw new DiagnosisApiError("诊断任务等待时间过长，任务仍保留在后台；请稍后返回查看或重试。 ");
    }
    try {
      job = await read();
      break;
    } catch (error: unknown) {
      if (!(error instanceof RetryablePollingError)) throw error;
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }

  while (job.status !== "COMPLETED" && job.status !== "FAILED") {
    if (Date.now() - startedAt > maxDurationMs) {
      throw new DiagnosisApiError("诊断任务等待时间过长，任务仍保留在后台；请稍后返回查看或重试。 ");
    }
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    try {
      job = await read();
    } catch (error: unknown) {
      if (!(error instanceof RetryablePollingError)) throw error;
      continue;
    }
  }
  return job;
}

class RetryablePollingError extends Error {
  constructor() {
    super("retryable polling failure");
  }
}

export async function getDiagnosis(diagnosisId: string): Promise<DiagnosisAnalysisResponse> {
  const token = permanence.token.useToken();
  const response = await req.get<ApiResponse<DiagnosisAnalysisResponse>>(
    `/diagnoses/${encodeURIComponent(diagnosisId)}`,
    { Authorization: `Bearer ${token}` },
  );
  if (!response.data) throw new DiagnosisApiError(response.msg || "诊断不存在");
  return response.data;
}

export async function getExpertReview(diagnosisId: string): Promise<Record<string, unknown> | undefined> {
  const token = permanence.token.useToken();
  const response = await req.get<ApiResponse<Record<string, unknown>>>(`/api/expert-review/${encodeURIComponent(diagnosisId)}`, { Authorization: `Bearer ${token}` });
  return response.data && Object.keys(response.data).length ? response.data : undefined;
}


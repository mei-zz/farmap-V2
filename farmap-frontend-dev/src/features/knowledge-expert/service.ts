import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import type { KnowledgeGraphResponse } from "@/types/knowledge-graph";

type ApiResponse<T> = { code?: number; msg?: string; data?: T };

export type KnowledgeSource = {
  sourceId: string;
  name: string;
  sourceType?: string;
  originUri?: string;
  status?: string;
};

export type ExpertReviewV2Input = Record<string, unknown>;

function authHeaders() {
  return { Authorization: `Bearer ${permanence.token.useToken()}` };
}

export async function listKnowledgeSources(): Promise<KnowledgeSource[]> {
  const response = await req.get<ApiResponse<KnowledgeSource[]>>("/api/knowledge/sources", authHeaders());
  if (!Array.isArray(response.data)) throw new Error(response.msg || "Knowledge Source 接口暂不可用");
  return response.data;
}

export async function loadKnowledgeGraph(options: {
  nodeTypes?: string[];
  sourceId?: string;
  documentId?: string;
  diagnosisId?: string;
  limit?: number;
  includeEvidence?: boolean;
} = {}): Promise<KnowledgeGraphResponse> {
  const params = new URLSearchParams();
  if (options.nodeTypes?.length) params.set("nodeTypes", options.nodeTypes.join(","));
  if (options.sourceId) params.set("sourceId", options.sourceId);
  if (options.documentId) params.set("documentId", options.documentId);
  if (options.diagnosisId) params.set("diagnosisId", options.diagnosisId);
  params.set("limit", String(options.limit ?? 150));
  params.set("includeEvidence", String(options.includeEvidence ?? true));
  const query = params.toString();
  const response = await req.get<ApiResponse<KnowledgeGraphResponse>>(`/api/knowledge/graph?${query}`, authHeaders());
  if (!response.data) throw new Error(response.msg || "Knowledge Graph 接口暂不可用");
  return response.data;
}

export type KnowledgeSearchResponse = {
  status: string;
  results: Array<{
    chunkId?: string;
    sourceId?: string;
    sourceName?: string;
    documentId?: string;
    documentTitle?: string;
    versionId?: string;
    score?: number | null;
    vectorScore?: number | null;
    rerankScore?: number | null;
    retrievalScore?: number | null;
    content?: string;
    embeddingModel?: string | null;
    rerankerModel?: string | null;
  }>;
  topK?: number;
  retrievalProvider?: string;
  embeddingModel?: string;
  rerankerModel?: string | null;
  rerankerStatus?: string;
};

export async function searchKnowledge(query: string, topK = 5): Promise<KnowledgeSearchResponse> {
  const params = new URLSearchParams({ q: query, topK: String(topK), rerank: "true" });
  const response = await req.get<ApiResponse<KnowledgeSearchResponse>>(`/api/knowledge/search?${params.toString()}`, authHeaders());
  if (!response.data) throw new Error(response.msg || "知识检索接口暂不可用");
  return response.data;
}

export async function listExpertReviews(): Promise<unknown[]> {
  const response = await req.get<ApiResponse<unknown[]>>("/api/expert-review", authHeaders());
  if (!Array.isArray(response.data)) throw new Error(response.msg || "Expert Review 接口暂不可用");
  return response.data;
}

export async function getExpertReviewRecord(diagnosisId: string): Promise<unknown | undefined> {
  const response = await req.get<ApiResponse<Record<string, unknown>>>(`/api/expert-review/${encodeURIComponent(diagnosisId)}`, authHeaders());
  return response.data && Object.keys(response.data).length ? response.data : undefined;
}

export async function saveExpertReviewRecord(diagnosisId: string, input: Record<string, unknown>): Promise<Record<string, unknown>> {
  const response = await req.post<Record<string, unknown>, ApiResponse<Record<string, unknown>>>(`/api/expert-review/${encodeURIComponent(diagnosisId)}`, input, authHeaders());
  if (!response.data) throw new Error(response.msg || "Expert Review 写入失败");
  return response.data;
}

/** Structured V2 contract. The legacy /api/expert-review helpers above remain for old records. */
export async function listExpertReviewsV2(diagnosisId?: string): Promise<unknown[]> {
  const suffix = diagnosisId ? `?diagnosisId=${encodeURIComponent(diagnosisId)}` : "";
  const response = await req.get<ApiResponse<unknown[]>>(`/api/expert-reviews${suffix}`, authHeaders());
  if (!Array.isArray(response.data)) throw new Error(response.msg || "Expert Review V2 接口暂不可用");
  return response.data;
}

export async function updateExpertReviewV2(reviewId: string, input: ExpertReviewV2Input): Promise<Record<string, unknown>> {
  const response = await req.put<ExpertReviewV2Input, ApiResponse<Record<string, unknown>>>(`/api/expert-reviews/${encodeURIComponent(reviewId)}`, input, authHeaders());
  if (!response.data) throw new Error(response.msg || "Expert Review V2 更新失败");
  return response.data;
}

export async function evaluateHistoricalCaseCandidate(reviewId: string): Promise<Record<string, unknown>> {
  const response = await req.post<Record<string, never>, ApiResponse<Record<string, unknown>>>(`/api/expert-reviews/${encodeURIComponent(reviewId)}/candidate`, {}, authHeaders());
  if (!response.data) throw new Error(response.msg || "历史案例候选评估失败");
  return response.data;
}

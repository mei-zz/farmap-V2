import type { ExpertReviewRecord, ExpertReviewStatus, KnowledgeCenterMode, KnowledgeExpertViewModel } from "@/types/knowledge-expert";

function text(value: unknown): string | undefined {
  return value === undefined || value === null || value === "" ? undefined : String(value);
}

function parseValue(value: unknown): unknown {
  if (typeof value !== "string") return value;
  try { return JSON.parse(value); } catch { return value; }
}

function status(value: unknown): ExpertReviewStatus {
  const normalized = String(value ?? "UNKNOWN").toUpperCase();
  if (["PENDING", "IN_REVIEW", "CONFIRMED", "CORRECTED"].includes(normalized)) return normalized as ExpertReviewStatus;
  return "UNKNOWN";
}

function v2Status(raw: Record<string, unknown>): ExpertReviewStatus {
  const judgment = String(raw.diagnosisJudgment ?? "").toUpperCase();
  if (judgment === "CORRECT") return "CONFIRMED";
  if (["INCORRECT", "PARTIALLY_CORRECT"].includes(judgment)) return "CORRECTED";
  if (judgment || raw.outcomeJudgment || raw.recommendationJudgment) return "IN_REVIEW";
  return status(raw.status);
}

export function normalizeExpertReview(value: unknown): ExpertReviewRecord | undefined {
  if (!value || typeof value !== "object") return undefined;
  const raw = value as Record<string, unknown>;
  const diagnosisId = text(raw.diagnosisId ?? raw.diagnosis_id);
  if (!diagnosisId) return undefined;
  return {
    diagnosisId,
    reviewId: text(raw.reviewId ?? raw.review_id),
    scope: text(raw.scope),
    taskId: text(raw.taskId ?? raw.task_id),
    outcomeId: text(raw.outcomeId ?? raw.outcome_id),
    recommendationId: text(raw.recommendationId ?? raw.recommendation_id),
    status: v2Status(raw),
    reviewer: text(raw.reviewer ?? raw.reviewerUserId ?? raw.reviewer_user_id),
    reviewedAt: text(raw.reviewedAt ?? raw.reviewed_at ?? raw.updatedAt ?? raw.updated_at),
    diagnosisJudgment: text(raw.diagnosisJudgment ?? raw.diagnosis_judgment),
    recommendationJudgment: text(raw.recommendationJudgment ?? raw.recommendation_judgment),
    outcomeJudgment: text(raw.outcomeJudgment ?? raw.outcome_judgment),
    expertConfidence: typeof raw.expertConfidence === "number" ? raw.expertConfidence : typeof raw.expert_confidence === "number" ? raw.expert_confidence : undefined,
    notes: text(raw.notes),
    version: typeof raw.version === "number" ? raw.version : undefined,
    candidateStatus: text(raw.candidateStatus ?? raw.candidate_status),
    aiDiagnosis: parseValue(raw.aiDiagnosis ?? raw.ai_diagnosis),
    expertDiagnosis: parseValue(raw.expertDiagnosis ?? raw.expert_diagnosis),
    evidence: parseValue(raw.evidence ?? raw.evidence_json),
    recommendations: parseValue(raw.recommendations ?? raw.recommendations_json),
    before: parseValue(raw.before ?? raw.before_json),
    after: parseValue(raw.after ?? raw.after_json),
  };
}

export function normalizeExpertReviews(value: unknown): ExpertReviewRecord[] {
  const list = Array.isArray(value) ? value : [];
  return list.map(normalizeExpertReview).filter((item): item is ExpertReviewRecord => Boolean(item));
}

export function buildKnowledgeExpertViewModel(input: {
  mode: KnowledgeCenterMode;
  reviews?: ExpertReviewRecord[];
  reviewUnavailable?: boolean;
}): KnowledgeExpertViewModel {
  const reviews = input.reviews ?? [];
  const reviewStatus = input.reviewUnavailable ? "UNAVAILABLE" : reviews.length ? "AVAILABLE" : "EMPTY";
  const mode = input.mode === "REAL" && input.reviewUnavailable ? "UNAVAILABLE" : input.mode;
  return {
    mode,
    freshness: {
      label: input.mode === "DEMO" ? "演示边界" : input.reviewUnavailable ? "接口不可用" : "按接口返回",
      stale: false,
    },
    reviewQueue: {
      status: reviewStatus,
      items: reviews,
      detail: input.mode === "DEMO"
        ? "演示模式不读取或写入真实专家复核。"
        : input.reviewUnavailable
          ? "Expert Review 接口暂不可用，未生成本地替代数据。"
          : reviews.length ? "来自 /api/expert-review 的真实复核记录。" : "接口返回 0 条真实复核记录。",
    },
    knowledge: {
      status: "LIMITED",
      detail: input.mode === "DEMO"
        ? "知识检索能力作为演示边界展示；正式知识库由平台管理员维护。"
        : "农业知识由后端 RAG retriever 供诊断与 Agent 调用；知识库写入仅开放给平台管理员。",
      capabilities: [
        { label: "诊断知识检索", status: "AVAILABLE", detail: "可作为 Diagnosis/Agent 的 Evidence 来源。", source: "KnowledgeEvidenceRetriever / KnowledgeTool" },
        { label: "知识库浏览", status: "AVAILABLE", detail: "可通过只读 Knowledge API 查询管理员维护的当前版本。", source: "/api/knowledge/sources" },
        { label: "文档上传与索引", status: "UNAVAILABLE", detail: "页面不提供上传；管理员通过受保护 CLI/API 导入 PDF、TXT 或 Markdown。", source: "/api/admin/knowledge/import" },
      ],
    },
    historicalCases: {
      status: input.mode === "DEMO" || input.mode === "REAL" ? "READY_EMPTY" : "UNAVAILABLE",
      rowCount: 0,
      detail: input.mode === "DEMO"
        ? "历史案例（演示）：不代表真实已验证案例。"
        : "案例库已就绪，暂无真实已验证历史案例；当前基线 rowCount=0。",
    },
    traceability: {
      diagnosisToReview: "AI Diagnosis 通过 diagnosisId 进入 Expert Review；复核状态由服务端保存。",
      reviewToCandidate: "完整 Outcome Review、稳定 provenance、明确判断和足够专家置信度的记录，才满足历史案例候选条件；失败案例也保留学习价值。",
      retrievalToEvidence: "Knowledge/Historical Retriever 的结果会进入 Diagnosis Evidence；本页面不把内部检索伪装成文档库列表。",
    },
  };
}

export type KnowledgeCenterMode = "DEMO" | "REAL" | "EMPTY" | "UNAVAILABLE" | "STALE";

export type ExpertReviewStatus = "PENDING" | "IN_REVIEW" | "CONFIRMED" | "CORRECTED" | "UNKNOWN";

export type ExpertReviewRecord = {
  diagnosisId: string;
  reviewId?: string;
  scope?: "DIAGNOSIS_ONLY" | "OUTCOME_REVIEW" | string;
  taskId?: string;
  outcomeId?: string;
  recommendationId?: string;
  status: ExpertReviewStatus;
  reviewer?: string;
  reviewedAt?: string;
  diagnosisJudgment?: string;
  recommendationJudgment?: string;
  outcomeJudgment?: string;
  expertConfidence?: number;
  notes?: string;
  version?: number;
  candidateStatus?: "ELIGIBLE" | "NOT_ELIGIBLE" | string;
  aiDiagnosis?: unknown;
  expertDiagnosis?: unknown;
  evidence?: unknown;
  recommendations?: unknown;
  before?: unknown;
  after?: unknown;
};

export type KnowledgeCapability = {
  label: string;
  status: "AVAILABLE" | "LIMITED" | "UNAVAILABLE" | "READY_EMPTY";
  detail: string;
  source: string;
};

export type KnowledgeExpertViewModel = {
  mode: KnowledgeCenterMode;
  freshness: { label: string; updatedAt?: string; stale: boolean };
  reviewQueue: {
    status: "AVAILABLE" | "EMPTY" | "UNAVAILABLE";
    items: ExpertReviewRecord[];
    detail: string;
  };
  knowledge: {
    status: "LIMITED" | "UNAVAILABLE";
    capabilities: KnowledgeCapability[];
    detail: string;
  };
  historicalCases: {
    status: "READY_EMPTY" | "UNAVAILABLE";
    rowCount?: number;
    detail: string;
  };
  traceability: {
    diagnosisToReview: string;
    reviewToCandidate: string;
    retrievalToEvidence: string;
  };
};

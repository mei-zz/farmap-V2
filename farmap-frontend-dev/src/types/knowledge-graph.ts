export type KnowledgeGraphNodeType =
  | "KNOWLEDGE_SOURCE"
  | "KNOWLEDGE_DOCUMENT"
  | "KNOWLEDGE_VERSION"
  | "EVIDENCE"
  | "DIAGNOSIS"
  | "EXPERT_REVIEW"
  | "HISTORICAL_CASE_CANDIDATE";

export type KnowledgeGraphNode = {
  id: string;
  type: KnowledgeGraphNodeType;
  label: string;
  status?: string | null;
  metadata: Record<string, unknown>;
};

export type KnowledgeGraphEdge = {
  id: string;
  source: string;
  target: string;
  type: string;
};

export type KnowledgeGraphResponse = {
  status: "READY" | "DEGRADED" | "EMPTY" | "UNAVAILABLE" | string;
  generatedAt: string;
  nodes: KnowledgeGraphNode[];
  edges: KnowledgeGraphEdge[];
  summary: {
    sourceCount: number | null;
    documentCount: number | null;
    versionCount: number | null;
    chunkCount: number | null;
    productionChunkCount: number | null;
    productionVectorCount: number | null;
    evidenceCount: number | null;
    diagnosisLinkedCount: number | null;
    expertReviewCount: number | null;
    historicalCandidateCount: number | null;
  };
  coverage: {
    totalSourceFiles: number | null;
    readyDocuments: number | null;
    unsupportedFiles: number | null;
    corruptNoTextFiles: number | null;
    registeredFiles: number | null;
    status: string;
    note?: string | null;
  };
  collections: {
    productionKnowledge: KnowledgeVectorCollection;
    historicalImage: KnowledgeVectorCollection;
  };
  pipeline: {
    status: string;
    stages: KnowledgePipelineStage[];
  };
  retrieval: {
    status: string;
    candidateTopN: number | null;
    finalTopK: number | null;
    embeddingProvider?: string | null;
    embeddingModel?: string | null;
    rerankerModel?: string | null;
    rerankerStatus?: string | null;
    note?: string | null;
  };
};

export type KnowledgeVectorCollection = {
  collection: string;
  status: string;
  rowCount: number | null;
  embeddingModel?: string | null;
  dimension?: number | null;
  metric?: string | null;
  note?: string | null;
};

export type KnowledgePipelineStage = {
  key: string;
  label: string;
  status: string;
  detail?: string | null;
};

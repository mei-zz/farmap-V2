export type EvidenceModality =
  | "camera"
  | "satellite"
  | "weather"
  | "soil"
  | "knowledge"
  | "historical_case"
  | "spatial";

export type EvidenceSource = {
  type: string;
  name: string;
  timestamp?: string;
  document?: string;
  section?: string;
  fieldId?: string;
  cameraId?: string;
};

export type EvidencePreview = {
  imageUrl?: string;
  chartData?: unknown;
  textSnippet?: string;
};

export type DiagnosisEvidence = {
  id: string;
  modality: EvidenceModality;
  title: string;
  summary: string;
  score?: number;
  source: EvidenceSource;
  preview?: EvidencePreview;
  metadata?: Record<string, unknown>;
};

export type DiagnosisClaim = {
  id: string;
  text: string;
  evidenceIds: string[];
};

export type DiagnosisRecommendation = {
  id: string;
  title: string;
  priority: "high" | "medium" | "low";
  detail: string;
  evidenceIds: string[];
};

export type DiagnosisAnalysisRequest = {
  fieldId: string;
  farmId: number;
  crop?: string;
  growthStage?: string;
  imageUrls: string[];
  weather?: {
    rainfall14d?: number;
    summary?: string;
    temperature?: string;
    observedAt?: string;
  };
  query?: string;
  topK?: number;
};

export type DiagnosisResult = {
  title: string;
  riskLevel: "high" | "medium" | "low";
  confidence: number;
  summary: string;
  alternatives: Array<{
    name: string;
    probability: number;
  }>;
};

export type DiagnosisAnalysisResponse = {
  runId: string;
  diagnosis: DiagnosisResult;
  claims: DiagnosisClaim[];
  evidence: DiagnosisEvidence[];
  recommendations: DiagnosisRecommendation[];
  retrievalSummary: Record<string, unknown>;
  metadata: {
    mode: "real";
    generationMode?: string;
    model?: string;
    embeddingModel?: string;
    retrievalLatencyMs?: number;
    generationLatencyMs?: number;
    totalLatencyMs?: number;
    [key: string]: unknown;
  };
};


import type { DemoDiagnosis, DemoEvidence, DemoField, DemoRecommendation } from "@/demo";
import type { FarmStoreState } from "@/store/farm";

export type DiagnosisMode = "REAL" | "DEMO" | "STALE" | "UNAVAILABLE" | "EMPTY";

export type DiagnosisFarmContext = {
  id: number;
  name: string;
  address: string;
  coordinates: [number, number];
};

export type DiagnosisInputSource = {
  id: string;
  label: string;
  countLabel: string;
  modality: string;
  available: boolean;
};

export type DiagnosisFinding = {
  id: string;
  text: string;
  evidenceIds: string[];
};

export type DiagnosisEvidenceView = DemoEvidence & {
  modality: string;
  evidenceIds: string[];
  score?: number;
  technical?: {
    evidenceId: string;
    metadata?: Record<string, unknown>;
  };
};

export type DiagnosisRecommendationView = DemoRecommendation & {
  id: string;
  evidenceIds: string[];
  evidenceStatus: "LINKED" | "UNAVAILABLE";
  decisionState: "PROPOSED" | "PENDING_REVIEW" | "APPROVED" | "UNAVAILABLE";
};

export type DiagnosisUncertainty = {
  id: string;
  label: string;
  detail: string;
};

export type DiagnosisTechnicalDetails = {
    runId?: string;
    diagnosisId?: string;
    sourceRunId?: string;
  model?: string;
  route?: string;
  retrievedEvidenceCount?: number;
  toolNames: string[];
  metadata?: Record<string, unknown>;
};

export type DiagnosisViewModel = {
  mode: DiagnosisMode;
  farm: DiagnosisFarmContext;
  field: DemoField;
  weather: {
    summary: string;
    temperature: string;
    rainfallForecast: string;
    updatedAt: string;
  };
  map: {
    crops: FarmStoreState["crops"];
    locations: FarmStoreState["locations"];
  };
  images: string[];
  inputSources: DiagnosisInputSource[];
  diagnosis: DemoDiagnosis;
  citedEvidenceCount: number;
  retrievedEvidenceCount?: number;
  findings: DiagnosisFinding[];
  evidence: DiagnosisEvidenceView[];
  recommendations: DiagnosisRecommendationView[];
  uncertainty: DiagnosisUncertainty[];
  freshness: { updatedAt: string; mode: DiagnosisMode };
  technical: DiagnosisTechnicalDetails;
};

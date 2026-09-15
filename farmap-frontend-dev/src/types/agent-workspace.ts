import type { DemoEvidence, DemoField, DemoRecommendation } from "@/demo";
import type { AgentAction, AgentEvent, AgentPlan, AgentRun, AgentToolCall } from "@/features/agent/contract";
import type { FarmStoreState } from "@/store/farm";

export type AgentWorkspaceMode = "REAL" | "DEMO" | "STALE" | "UNAVAILABLE" | "EMPTY";

export type AgentWorkspaceStatus = "READY" | "ANALYZING" | "WAITING_APPROVAL" | "COMPLETED" | "FAILED" | "CANCELLED";

export type AgentProgressStatus = "PENDING" | "ACTIVE" | "COMPLETED" | "FAILED" | "UNAVAILABLE";
export type AgentSourceStatus = "READY" | "READY_EMPTY" | "UNAVAILABLE" | "FAILED";

export type AgentWorkspaceFarm = {
  id: number;
  name: string;
  address: string;
};

export type AgentWorkspaceDiagnosis = {
  id?: string;
  title: string;
  severity?: "low" | "medium" | "high";
  confidence?: number;
  summary?: string;
};

export type AgentWorkspaceStep = {
  id: string;
  title: string;
  description: string;
  status: AgentProgressStatus;
  sourceStatus?: AgentSourceStatus;
  duration?: string;
  technicalTool?: string;
};

export type AgentWorkspaceEvidence = DemoEvidence & {
  modality: string;
  availability: "AVAILABLE" | "EMPTY" | "UNAVAILABLE";
  score?: number;
  technicalId?: string;
};

export type AgentWorkspaceFinding = {
  id: string;
  text: string;
  severity?: "low" | "medium" | "high";
  evidenceIds: string[];
  uncertainty?: string;
};

export type AgentWorkspaceAction = {
  id: string;
  type: string;
  title: string;
  detail: string;
  scope: string;
  evidenceIds: string[];
  approvalRequired: boolean;
  approvalState: "NONE" | "REQUIRED" | "APPROVED" | "REJECTED" | "UNAVAILABLE";
  taskId?: string;
  taskStatus?: string;
  outcome?: string;
  technicalTool?: string;
};

export type AgentWorkspaceApproval = {
  state: "NONE" | "REQUIRED" | "APPROVED" | "REJECTED" | "UNAVAILABLE";
  stateLabel: string;
  actionId?: string;
  title?: string;
  detail?: string;
  canApprove: boolean;
  canReject: boolean;
};

export type AgentWorkspaceExecution = {
  state: "NOT_STARTED" | "PENDING_APPROVAL" | "APPROVED" | "CREATED" | "FAILED" | "UNAVAILABLE";
  stateLabel: string;
  detail: string;
  taskId?: string;
};

export type AgentWorkspaceTechnicalDetails = {
  runId?: string;
  rawStatus?: string;
  plan?: AgentPlan;
  toolCalls: AgentToolCall[];
  traces: AgentEvent[];
  metadata: Record<string, unknown>;
  model?: string;
  diagnosisId?: string;
  evidenceIds: string[];
  taskIds: string[];
  modeNote?: string;
};

export type AgentWorkspaceViewModel = {
  mode: AgentWorkspaceMode;
  sourceLabel: string;
  farm: AgentWorkspaceFarm;
  field: DemoField;
  map: { crops: FarmStoreState["crops"]; locations: FarmStoreState["locations"] };
  images: string[];
  weather: { summary: string; temperature: string; updatedAt: string };
  dataSources: Array<{ id: string; label: string; countLabel: string; status: "AVAILABLE" | "EMPTY" | "UNAVAILABLE" }>;
  goal: string;
  diagnosis?: AgentWorkspaceDiagnosis;
  status: AgentWorkspaceStatus;
  statusLabel: string;
  statusDetail: string;
  runId?: string;
  createdAt: string;
  updatedAt: string;
  freshness: { updatedAt: string; stale: boolean };
  steps: AgentWorkspaceStep[];
  findings: AgentWorkspaceFinding[];
  evidence: AgentWorkspaceEvidence[];
  actions: AgentWorkspaceAction[];
  approval: AgentWorkspaceApproval;
  execution: AgentWorkspaceExecution;
  technical: AgentWorkspaceTechnicalDetails;
  failure?: { code?: string; message?: string };
  canStart: boolean;
  canCancel: boolean;
  canRetry: boolean;
};

export type AgentWorkspaceFallback = {
  farm: AgentWorkspaceFarm;
  field: DemoField;
  diagnosis?: AgentWorkspaceDiagnosis;
  goal: string;
  createdAt: string;
  updatedAt?: string;
  images: string[];
  weather: { summary: string; temperature: string; updatedAt: string };
  map: { crops: FarmStoreState["crops"]; locations: FarmStoreState["locations"] };
};

export type AgentWorkspaceRuntimeState = {
  mode: AgentWorkspaceMode;
  runStatus: "pending" | "running" | "completed" | "failed";
  currentStep: number;
  elapsedSeconds: number;
};

export type AgentWorkspaceRun = AgentRun;
export type AgentWorkspaceActionContract = AgentAction;
export type AgentWorkspaceRecommendation = DemoRecommendation;

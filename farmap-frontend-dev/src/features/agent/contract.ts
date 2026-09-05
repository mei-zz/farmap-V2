export type AgentRunStatus = "CREATED" | "PLANNING" | "WAITING_FOR_APPROVAL" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
export type AgentStepStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "SKIPPED";
export type AgentToolStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "UNAVAILABLE";
export type AgentApprovalState = "NONE" | "REQUIRED" | "APPROVED" | "REJECTED";

export type AgentContext = {
  farmId?: string;
  farmName?: string;
  fieldId?: string;
  fieldName?: string;
  crop?: string;
  variety?: string;
  phenologyStage?: string;
  location?: string;
  latitude?: number;
  longitude?: number;
  cameraId?: string;
  imageUrls: string[];
  weather: Record<string, unknown>;
  diagnosis: Record<string, unknown>;
  sourceModes: Record<string, unknown>;
  page?: string;
};

export type AgentPlanStep = { id: string; title: string; description: string; toolName: string; readOnly: boolean; dependsOn: string[]; status: AgentStepStatus };
export type AgentPlan = { steps: AgentPlanStep[]; maxSteps: number; maxToolCalls: number; timeoutMs: number; plannerModel?: string; schemaVersion?: string };
export type AgentToolCall = { id: string; toolName: string; status: AgentToolStatus; input?: Record<string, unknown>; output?: Record<string, unknown>; errorCode?: string; errorMessage?: string; startedAt?: string; completedAt?: string };
export type AgentEvidence = { id: string; modality: string; title: string; summary: string; score?: number; source?: Record<string, unknown>; preview?: Record<string, unknown>; metadata?: Record<string, unknown> };
export type AgentAction = { id: string; type: string; title: string; detail: string; toolName: string; approvalRequired: boolean; approvalState: AgentApprovalState; payload?: Record<string, unknown> };
export type AgentEvent = { sequence: number; timestamp: string; type: string; payload: Record<string, unknown> };
export type AgentRun = { runId: string; goal: string; mode: string; context: AgentContext; status: AgentRunStatus; plan?: AgentPlan; steps: AgentPlanStep[]; toolCalls: AgentToolCall[]; evidence: AgentEvidence[]; output: Record<string, unknown>; actions: AgentAction[]; approvalState: AgentApprovalState; traces: AgentEvent[]; metadata: Record<string, unknown>; failureCode?: string; failureMessage?: string; createdAt: string; updatedAt: string };
export type AgentCreateResponse = { runId: string; status: "planning" };

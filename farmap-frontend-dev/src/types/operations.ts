export type OperationsMode = "REAL" | "DEMO" | "STALE" | "EMPTY" | "UNAVAILABLE";

export type OperationsTaskStatus = "OPEN" | "ASSIGNED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED" | "UNKNOWN";

export type OperationsPriority = "HIGH" | "MEDIUM" | "LOW" | "UNKNOWN";

export type OperationsTaskSource = "AI" | "EXPERT" | "MANUAL" | "SEASONAL" | "MONITORING" | "UNKNOWN";

export type OperationsTaskTraceability = {
  diagnosisId?: string;
  recommendationId?: string;
  agentRunId?: string;
  evidenceCount?: number;
};

export type OperationsOutcome = {
  outcomeId?: string;
  taskId?: string;
  resultType?: string;
  summary?: string;
  executionEvidence?: string;
  notes?: string;
  actor?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type OperationsTaskViewModel = {
  key: string;
  taskId?: string;
  title: string;
  fieldId?: string;
  fieldName?: string;
  status: OperationsTaskStatus;
  statusLabel: string;
  priority: OperationsPriority;
  priorityLabel: string;
  source: OperationsTaskSource;
  sourceLabel: string;
  createdAt?: string;
  updatedAt?: string;
  approvedAt?: string;
  approvedBy?: string;
  assignee?: string;
  assignedAt?: string;
  startedAt?: string;
  completedAt?: string;
  cancelledAt?: string;
  cancelledReason?: string;
  outcome?: OperationsOutcome;
  goal?: string;
  traceability: OperationsTaskTraceability;
  isDemo: boolean;
};

export type OperationsTaskRaw = {
  taskId?: unknown;
  title?: unknown;
  fieldId?: unknown;
  diagnosisId?: unknown;
  recommendationId?: unknown;
  agentRunId?: unknown;
  status?: unknown;
  priority?: unknown;
  source?: unknown;
  createdFrom?: unknown;
  createdBy?: unknown;
  evidenceIds?: unknown;
  approvedBy?: unknown;
  approvedAt?: unknown;
  createdAt?: unknown;
  updatedAt?: unknown;
  goal?: unknown;
  assignee?: unknown;
  assignedAt?: unknown;
  startedAt?: unknown;
  completedAt?: unknown;
  cancelledAt?: unknown;
  cancelledReason?: unknown;
  outcome?: unknown;
};

export type OperationsViewModel = {
  mode: OperationsMode;
  sourceLabel: string;
  farm: { id: number; name: string; address?: string };
  tasks: OperationsTaskViewModel[];
  selectedTask?: OperationsTaskViewModel;
  summary: {
    active: number;
    pending: number;
    inProgress?: number;
    overdue?: number;
  };
  freshness: { updatedAt?: string; stale: boolean };
  capabilities: {
    canCreate: false;
    canStart: false;
    canComplete: false;
    canCancel: false;
    canAssign: false;
    canUpdate: false;
  };
  unavailableReason?: string;
};

import { getDemoOverviewViewModel } from "@/features/overview/adapter";
import type {
  OperationsPriority,
  OperationsTaskRaw,
  OperationsTaskSource,
  OperationsTaskStatus,
  OperationsTaskViewModel,
  OperationsOutcome,
  OperationsViewModel,
} from "@/types/operations";

const statusLabels: Record<OperationsTaskStatus, string> = {
  OPEN: "待处理",
  ASSIGNED: "已分配",
  IN_PROGRESS: "执行中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  UNKNOWN: "状态未知",
};

const priorityLabels: Record<OperationsPriority, string> = {
  HIGH: "高优先级",
  MEDIUM: "重点关注",
  LOW: "一般",
  UNKNOWN: "优先级未知",
};

const sourceLabels: Record<OperationsTaskSource, string> = {
  AI: "AI 分析建议",
  EXPERT: "专家复核",
  MANUAL: "人工创建",
  SEASONAL: "季节性计划",
  MONITORING: "监测告警",
  UNKNOWN: "来源未关联",
};

function text(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function normalizePriority(value: unknown): OperationsPriority {
  const normalized = text(value)?.toUpperCase();
  return normalized === "HIGH" ? "HIGH" : normalized === "MEDIUM" ? "MEDIUM" : normalized === "LOW" ? "LOW" : "UNKNOWN";
}

function normalizeStatus(value: unknown): OperationsTaskStatus {
  const status = text(value)?.toUpperCase();
  return status === "OPEN" || status === "ASSIGNED" || status === "IN_PROGRESS" || status === "COMPLETED" || status === "CANCELLED" ? status : "UNKNOWN";
}

function normalizeOutcome(value: unknown): OperationsOutcome | undefined {
  if (!value || typeof value !== "object") return undefined;
  const raw = value as Record<string, unknown>;
  return {
    outcomeId: text(raw.outcomeId), taskId: text(raw.taskId), resultType: text(raw.resultType), summary: text(raw.summary),
    executionEvidence: text(raw.executionEvidence), notes: text(raw.notes), actor: text(raw.actor), createdAt: text(raw.createdAt), updatedAt: text(raw.updatedAt),
  };
}

function normalizeSource(value: unknown, raw: OperationsTaskRaw): OperationsTaskSource {
  const source = text(value)?.toLowerCase();
  if (source === "agent" || source === "task_tool" || source === "ai") return "AI";
  if (source === "expert" || source === "expert_review") return "EXPERT";
  if (source === "manual" || source === "user") return "MANUAL";
  if (source === "seasonal" || source === "guidance") return "SEASONAL";
  if (source === "monitoring" || source === "monitor" || source === "alert") return "MONITORING";
  if (text(raw.agentRunId)) return "AI";
  return "UNKNOWN";
}

function parseEvidenceIds(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === "string");
  if (typeof value !== "string") return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function taskViewModel(raw: OperationsTaskRaw, options: { isDemo: boolean; key: string }): OperationsTaskViewModel {
  const status = options.isDemo && text(raw.status)?.toUpperCase() === "PENDING" ? "OPEN" : normalizeStatus(raw.status);
  const priority = normalizePriority(raw.priority);
  const source = normalizeSource(raw.source, raw);
  const evidenceIds = parseEvidenceIds(raw.evidenceIds);
  return {
    key: options.key,
    taskId: text(raw.taskId),
    title: text(raw.title) ?? "未命名农事任务",
    fieldId: text(raw.fieldId),
    fieldName: text(raw.fieldId) ? `${text(raw.fieldId)} 地块` : undefined,
    status,
    statusLabel: statusLabels[status],
    priority,
    priorityLabel: priorityLabels[priority],
    source,
    sourceLabel: options.isDemo ? "演示任务 · 未关联真实来源" : sourceLabels[source],
    createdAt: text(raw.createdAt),
    updatedAt: text(raw.updatedAt),
    approvedAt: text(raw.approvedAt),
    approvedBy: text(raw.approvedBy),
    assignee: text(raw.assignee),
    assignedAt: text(raw.assignedAt),
    startedAt: text(raw.startedAt),
    completedAt: text(raw.completedAt),
    cancelledAt: text(raw.cancelledAt),
    cancelledReason: text(raw.cancelledReason),
    outcome: normalizeOutcome(raw.outcome),
    goal: text(raw.goal),
    traceability: {
      diagnosisId: text(raw.diagnosisId),
      recommendationId: text(raw.recommendationId),
      agentRunId: text(raw.agentRunId),
      evidenceCount: evidenceIds.length,
    },
    isDemo: options.isDemo,
  };
}

function summary(tasks: OperationsTaskViewModel[]) {
  return {
    active: tasks.length,
    pending: tasks.filter((task) => task.status === "OPEN").length,
    inProgress: tasks.filter((task) => task.status === "IN_PROGRESS").length,
    overdue: undefined,
  };
}

const readOnlyCapabilities = {
  canCreate: false as const,
  canStart: false as const,
  canComplete: false as const,
  canCancel: false as const,
  canAssign: false as const,
  canUpdate: false as const,
};

export function getDemoOperationsViewModel(): OperationsViewModel {
  const overview = getDemoOverviewViewModel();
  const tasks = overview.operations.tasks.map((task) => taskViewModel({
    title: task.name,
    fieldId: task.fieldId,
    status: task.status,
    priority: task.priority === "CRITICAL" ? "HIGH" : task.priority,
    source: "UNLINKED",
    createdAt: task.updatedAt,
  }, { isDemo: true, key: task.id }));
  return {
    mode: "DEMO",
    sourceLabel: "DEMO TASKS",
    farm: { id: overview.farm.id, name: overview.farm.name, address: overview.farm.address },
    tasks,
    selectedTask: tasks[0],
    summary: summary(tasks),
    freshness: { updatedAt: overview.farm.lastUpdated, stale: false },
    capabilities: readOnlyCapabilities,
  };
}

export function getUnavailableOperationsViewModel(farm: OperationsViewModel["farm"], reason = "Operations 服务暂不可用"): OperationsViewModel {
  return {
    mode: "UNAVAILABLE",
    sourceLabel: "UNAVAILABLE",
    farm,
    tasks: [],
    summary: { active: 0, pending: 0, inProgress: undefined, overdue: undefined },
    freshness: { stale: false },
    capabilities: readOnlyCapabilities,
    unavailableReason: reason,
  };
}

export function getRealOperationsViewModel(farm: OperationsViewModel["farm"], rawTasks: OperationsTaskRaw[]): OperationsViewModel {
  const tasks = rawTasks.map((raw, index) => taskViewModel(raw, { isDemo: false, key: text(raw.taskId) ?? `real-task-${index}` }));
  const timestamps = tasks.map((task) => task.createdAt).filter((value): value is string => Boolean(value)).sort();
  const latest = timestamps.length ? timestamps[timestamps.length - 1] : undefined;
  return {
    mode: tasks.length ? "REAL" : "EMPTY",
    sourceLabel: tasks.length ? "REAL OPERATIONS" : "EMPTY · 当前没有真实任务",
    farm,
    tasks,
    selectedTask: tasks[0],
    summary: summary(tasks),
    freshness: { updatedAt: latest, stale: false },
    capabilities: readOnlyCapabilities,
  };
}

export function selectOperationsTask(viewModel: OperationsViewModel, key?: string): OperationsViewModel {
  return { ...viewModel, selectedTask: viewModel.tasks.find((task) => task.key === key) ?? viewModel.selectedTask };
}

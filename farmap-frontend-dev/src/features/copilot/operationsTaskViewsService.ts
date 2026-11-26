import { req } from "@/utils/reqeust";

export type OperationsTaskViewMode = "tasks" | "calendar" | "records";

export type OperationsTaskStatus =
  | "OPEN"
  | "ASSIGNED"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "CANCELLED"
  | "UNKNOWN";

export type OperationsTaskOutcome = {
  summary?: string;
  notes?: string;
  executionEvidence?: string;
};

export type OperationsTaskViewItem = {
  taskId: string;
  title: string;
  fieldId?: string;
  fieldName?: string;
  status: OperationsTaskStatus;
  scheduledFor?: string;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
  version: number;
  outcome?: OperationsTaskOutcome;
};

export type OperationsTaskViewCounts = {
  total: number;
  pending: number;
  inProgress: number;
  completed: number;
};

export type OperationsTaskViewResult = {
  items: OperationsTaskViewItem[];
  counts: OperationsTaskViewCounts;
  truncated?: boolean;
};

export type OperationsTaskViewQuery = {
  farmId: number;
  fieldId?: string;
  from?: string;
  to?: string;
  view: OperationsTaskViewMode;
  filter?: string;
};

export type OperationsTaskScheduleBatchItem = {
  taskId: string;
  scheduledFor: string;
  version: number;
};

type ApiResult<T> = {
  code?: number | string;
  msg?: string;
  message?: string;
  data?: T;
};

const successCodes = new Set([1, 200]);

function record(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function text(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function apiCode(value: unknown): number | undefined {
  if (typeof value !== "number" && typeof value !== "string") return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function apiMessage(value: Record<string, unknown>): string | undefined {
  return [value.msg, value.message, value.error]
    .find((item): item is string => typeof item === "string" && Boolean(item.trim()))
    ?.trim();
}

function unwrapResult<T>(response: ApiResult<T>, fallback: string): T {
  const envelope = record(response);
  if (envelope && ("code" in envelope || "data" in envelope || "msg" in envelope || "message" in envelope)) {
    const code = apiCode(envelope.code);
    if (code !== undefined && !successCodes.has(code)) {
      throw new Error(apiMessage(envelope) || fallback);
    }
    const data = envelope.data as T | undefined;
    if (data === undefined || data === null) throw new Error(apiMessage(envelope) || fallback);
    return data;
  }
  if (response === undefined || response === null) throw new Error(fallback);
  return response as T;
}

function assertMutationSuccess(response: ApiResult<unknown> | undefined, fallback: string): void {
  const envelope = record(response);
  if (!envelope) return;
  const code = apiCode(envelope.code);
  if (code !== undefined && !successCodes.has(code)) {
    throw new Error(apiMessage(envelope) || fallback);
  }
}

function normalizeStatus(value: unknown): OperationsTaskStatus {
  const status = text(value)?.toUpperCase();
  if (
    status === "OPEN"
    || status === "ASSIGNED"
    || status === "IN_PROGRESS"
    || status === "COMPLETED"
    || status === "CANCELLED"
  ) return status;
  return "UNKNOWN";
}

function normalizeOutcome(value: unknown): OperationsTaskOutcome | undefined {
  const raw = record(value);
  if (!raw) return undefined;
  const summary = text(raw.summary);
  const notes = text(raw.notes);
  const executionEvidence = text(raw.executionEvidence);
  if (!summary && !notes && !executionEvidence) return undefined;
  return { summary, notes, executionEvidence };
}

function nonNegativeInteger(value: unknown, fallback: number): number {
  const parsed = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

function normalizeItem(value: unknown, index: number): OperationsTaskViewItem {
  const raw = record(value);
  if (!raw) throw new Error(`服务端任务列表第 ${index + 1} 项格式无法识别`);
  const taskId = text(raw.taskId);
  if (!taskId) throw new Error(`服务端任务列表第 ${index + 1} 项缺少任务编号`);
  return {
    taskId,
    title: text(raw.title) || "未命名农事任务",
    fieldId: text(raw.fieldId),
    fieldName: text(raw.fieldName),
    status: normalizeStatus(raw.status),
    scheduledFor: text(raw.scheduledFor),
    createdAt: text(raw.createdAt),
    startedAt: text(raw.startedAt),
    completedAt: text(raw.completedAt),
    version: nonNegativeInteger(raw.version, -1),
    outcome: normalizeOutcome(raw.outcome),
  };
}

function fallbackCounts(items: OperationsTaskViewItem[]): OperationsTaskViewCounts {
  return {
    total: items.length,
    pending: items.filter((item) => item.status === "OPEN" || item.status === "ASSIGNED").length,
    inProgress: items.filter((item) => item.status === "IN_PROGRESS").length,
    completed: items.filter((item) => item.status === "COMPLETED").length,
  };
}

function normalizeResult(value: unknown): OperationsTaskViewResult {
  const raw = record(value);
  if (!raw) throw new Error("服务端任务视图数据格式无法识别");
  if (!Array.isArray(raw.items)) throw new Error("服务端任务视图缺少任务列表");
  const items = raw.items.map(normalizeItem);
  const counts = record(raw.counts);
  const fallback = fallbackCounts(items);
  return {
    items,
    counts: {
      total: nonNegativeInteger(counts?.total, fallback.total),
      pending: nonNegativeInteger(counts?.pending, fallback.pending),
      inProgress: nonNegativeInteger(counts?.inProgress, fallback.inProgress),
      completed: nonNegativeInteger(counts?.completed, fallback.completed),
    },
    truncated: raw.truncated === true,
  };
}

export function operationsViewErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export async function getOperationsTaskView(
  query: OperationsTaskViewQuery,
  token: string,
): Promise<OperationsTaskViewResult> {
  if (!Number.isSafeInteger(query.farmId) || query.farmId <= 0) {
    throw new Error("缺少有效的农场编号，无法读取农事任务。");
  }
  const params = new URLSearchParams();
  params.set("farmId", String(query.farmId));
  const fieldId = query.fieldId?.trim();
  const from = query.from?.trim();
  const to = query.to?.trim();
  if (fieldId) params.set("fieldId", fieldId);
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  params.set("view", query.view);
  if (query.filter) params.set("filter", query.filter);
  const response = await req.get<ApiResult<unknown>>(
    `/api/operations/task-view?${params.toString()}`,
    { Authorization: `Bearer ${token}` },
  );
  return normalizeResult(unwrapResult(response, "农事任务视图暂不可用"));
}

export async function scheduleOperationsTask(
  taskId: string,
  scheduledFor: string,
  version: number,
  token: string,
): Promise<void> {
  const id = taskId.trim();
  const date = scheduledFor.trim();
  if (!id) throw new Error("缺少有效的任务编号。");
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(date)) throw new Error("请选择有效的计划日期。");
  if (!Number.isSafeInteger(version) || version < 0) throw new Error("任务版本不可用，请刷新后重试。");
  const response = await req.post<{ scheduledFor: string; version: number }, ApiResult<unknown>>(
    `/api/operations/tasks/${encodeURIComponent(id)}/schedule`,
    { scheduledFor: date, version },
    { Authorization: `Bearer ${token}` },
  );
  assertMutationSuccess(response, "调整计划日期失败");
}

export async function scheduleOperationsTasksBatch(
  farmId: number,
  items: OperationsTaskScheduleBatchItem[],
  token: string,
): Promise<void> {
  if (!Number.isSafeInteger(farmId) || farmId <= 0) throw new Error("缺少有效的农场编号。");
  if (!items.length) throw new Error("请先选择需要调整的任务。");
  const payload = items.map((item) => {
    if (!item.taskId.trim()) throw new Error("批量调整中存在无效的任务编号。");
    if (!/^\d{4}-\d{2}-\d{2}$/u.test(item.scheduledFor)) throw new Error("请选择有效的批量计划日期。");
    if (!Number.isSafeInteger(item.version) || item.version < 0) {
      throw new Error("批量调整中存在版本不可用的任务，请刷新后重试。");
    }
    return {
      taskId: item.taskId.trim(),
      scheduledFor: item.scheduledFor,
      version: item.version,
    };
  });
  const response = await req.post<
    { farmId: number; items: OperationsTaskScheduleBatchItem[] },
    ApiResult<unknown>
  >(
    "/api/operations/tasks/schedule-batch",
    { farmId, items: payload },
    { Authorization: `Bearer ${token}` },
  );
  assertMutationSuccess(response, "批量调整计划日期失败");
}

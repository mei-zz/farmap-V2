import { req } from "@/utils/reqeust";

type ApiResult<T> = { code?: number; msg?: string; data?: T };

export type PlanDraftPriority = "HIGH" | "MEDIUM" | "LOW";
export type PlanDraftStatus = "DRAFT" | "CONFIRMED";

export type PlanDraft = {
  draftId: string;
  analysisRunId: string;
  farmId?: number;
  fieldId: string;
  fieldName?: string;
  title: string;
  detail: string;
  priority: PlanDraftPriority;
  scheduledFor?: string;
  version: number;
  status: PlanDraftStatus;
  taskId?: string;
};

export type PlanDraftInput = {
  version: number;
  title: string;
  detail: string;
  priority: PlanDraftPriority;
  scheduledFor?: string;
};

export type OperationTaskStatus = "OPEN" | "ASSIGNED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";

export type OperationTaskRecord = {
  taskId: string;
  title: string;
  fieldId?: string;
  fieldName?: string;
  status: OperationTaskStatus;
  scheduledFor?: string;
  version?: number;
  priority?: string;
  assignee?: string;
  assignedAt?: string;
  startedAt?: string;
  completedAt?: string;
  cancelledAt?: string;
  cancelledReason?: string;
  goal?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type OperationOutcomeRecord = {
  outcomeId?: string;
  taskId?: string;
  resultType?: string;
  summary: string;
  executionEvidence?: string;
  notes?: string;
  actor?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type CompletedOperation = {
  task: OperationTaskRecord;
  outcome: OperationOutcomeRecord;
};

export type CompleteOperationInput = {
  summary: string;
  executionEvidence?: string;
  notes?: string;
};

const draftStatuses = new Set<PlanDraftStatus>(["DRAFT", "CONFIRMED"]);
const taskStatuses = new Set<OperationTaskStatus>(["OPEN", "ASSIGNED", "IN_PROGRESS", "COMPLETED", "CANCELLED"]);
const priorities = new Set<PlanDraftPriority>(["HIGH", "MEDIUM", "LOW"]);

function auth(token: string) {
  return { Authorization: `Bearer ${token}` };
}

function text(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function record(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

export function checkedArtifactId(value: unknown): string | undefined {
  const id = text(value);
  return id && id.length <= 240 ? id : undefined;
}

function requiredId(value: unknown, label: string): string {
  const id = checkedArtifactId(value);
  if (!id) throw new Error(`${label}缺少有效编号`);
  return id;
}

function unwrap<T>(response: ApiResult<T>, fallback: string): T {
  if (typeof response.code === "number" && response.code !== 1) throw new Error(response.msg || fallback);
  if (response.data === undefined || response.data === null) throw new Error(response.msg || fallback);
  return response.data;
}

export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function draftPriority(value: unknown): PlanDraftPriority {
  const priority = text(value)?.toUpperCase() as PlanDraftPriority | undefined;
  if (!priority || !priorities.has(priority)) throw new Error("服务端草稿的优先级格式无法识别");
  return priority;
}

function taskStatus(value: unknown): OperationTaskStatus {
  const status = text(value)?.toUpperCase() as OperationTaskStatus | undefined;
  if (!status || !taskStatuses.has(status)) throw new Error("服务端任务状态无法识别");
  return status;
}

function integer(value: unknown, label: string): number {
  const parsed = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw new Error(`${label}格式无法识别`);
  return parsed;
}

function normalizeDraft(raw: unknown): PlanDraft {
  const value = record(raw);
  if (!value) throw new Error("服务端草稿数据为空");
  const status = text(value.status)?.toUpperCase() as PlanDraftStatus | undefined;
  if (!status || !draftStatuses.has(status)) throw new Error("服务端草稿状态无法识别");
  const taskId = checkedArtifactId(value.taskId);
  if (status === "CONFIRMED" && !taskId) throw new Error("服务端草稿已确认，但缺少任务编号");
  if (status === "DRAFT" && taskId) throw new Error("服务端草稿状态与任务编号不一致");
  return {
    draftId: requiredId(value.draftId, "草稿"),
    analysisRunId: requiredId(value.analysisRunId, "分析报告"),
    farmId: typeof value.farmId === "number" && Number.isFinite(value.farmId) ? value.farmId : undefined,
    fieldId: requiredId(value.fieldId, "草稿地块"),
    fieldName: text(value.fieldName),
    title: typeof value.title === "string" ? value.title : "",
    detail: typeof value.detail === "string" ? value.detail : "",
    priority: draftPriority(value.priority),
    scheduledFor: text(value.scheduledFor),
    version: integer(value.version, "草稿版本"),
    status,
    taskId,
  };
}

function normalizeTask(raw: unknown, expectedTaskId?: string): OperationTaskRecord {
  const value = record(raw);
  if (!value) throw new Error("服务端任务数据为空");
  const taskId = requiredId(value.taskId, "任务");
  if (expectedTaskId && taskId !== expectedTaskId) throw new Error("服务端返回了不匹配的任务");
  return {
    taskId,
    title: text(value.title) || "未命名农事任务",
    fieldId: checkedArtifactId(value.fieldId),
    fieldName: text(value.fieldName),
    status: taskStatus(value.status),
    scheduledFor: text(value.scheduledFor),
    version: typeof value.version === "number" && Number.isSafeInteger(value.version) && value.version >= 0 ? value.version : undefined,
    priority: text(value.priority),
    assignee: text(value.assignee),
    assignedAt: text(value.assignedAt),
    startedAt: text(value.startedAt),
    completedAt: text(value.completedAt),
    cancelledAt: text(value.cancelledAt),
    cancelledReason: text(value.cancelledReason),
    goal: text(value.goal),
    createdAt: text(value.createdAt),
    updatedAt: text(value.updatedAt),
  };
}

function normalizeOutcome(raw: unknown, expectedTaskId?: string): OperationOutcomeRecord {
  const value = record(raw);
  if (!value) throw new Error("服务端执行结果为空");
  const taskId = checkedArtifactId(value.taskId);
  if (expectedTaskId && taskId && taskId !== expectedTaskId) throw new Error("服务端返回了不匹配的执行结果");
  const summary = text(value.summary);
  if (!summary) throw new Error("服务端执行结果缺少摘要");
  return {
    outcomeId: checkedArtifactId(value.outcomeId),
    taskId,
    resultType: text(value.resultType),
    summary,
    executionEvidence: text(value.executionEvidence),
    notes: text(value.notes),
    actor: text(value.actor),
    createdAt: text(value.createdAt),
    updatedAt: text(value.updatedAt),
  };
}

function draftPayload(input: PlanDraftInput) {
  const version = integer(input.version, "草稿版本");
  const title = text(input.title);
  const detail = text(input.detail);
  if (!title) throw new Error("任务标题不能为空");
  if (!detail) throw new Error("执行说明不能为空");
  if (!priorities.has(input.priority)) throw new Error("请选择有效优先级");
  const scheduledFor = text(input.scheduledFor);
  if (scheduledFor && !/^\d{4}-\d{2}-\d{2}$/u.test(scheduledFor)) throw new Error("计划日期格式不正确");
  return { version, title, detail, priority: input.priority, ...(scheduledFor ? { scheduledFor } : {}) };
}

function draftResponse(response: ApiResult<unknown>, expected: { draftId: string; analysisRunId?: string; fieldId?: string }) {
  const draft = normalizeDraft(unwrap(response, "草稿写入失败"));
  if (draft.draftId !== expected.draftId) throw new Error("服务端返回了不匹配的草稿");
  if (expected.analysisRunId && draft.analysisRunId !== expected.analysisRunId) throw new Error("服务端草稿不属于当前分析报告");
  if (expected.fieldId && draft.fieldId !== expected.fieldId) throw new Error("服务端草稿与当前地块不一致");
  return draft;
}

export async function listPlanDrafts(analysisRunId: string, token: string): Promise<PlanDraft[]> {
  const runId = requiredId(analysisRunId, "分析报告");
  const query = new URLSearchParams({ analysisRunId: runId });
  const response = await req.get<ApiResult<unknown>>(
    `/api/operations/plan-drafts?${query.toString()}`,
    auth(token),
  );
  const data = unwrap(response, "执行草稿暂不可用");
  if (!Array.isArray(data)) throw new Error("服务端草稿列表格式无法识别");
  return data
    .map(normalizeDraft)
    .filter((draft) => draft.analysisRunId === runId);
}

export async function createPlanDraftFromAnalysis(analysisRunId: string, fieldId: string, token: string): Promise<PlanDraft> {
  const runId = requiredId(analysisRunId, "分析报告");
  const safeFieldId = requiredId(fieldId, "地块");
  const response = await req.post<
    { analysisRunId: string; fieldId: string },
    ApiResult<unknown>
  >("/api/operations/plan-drafts/from-analysis", { analysisRunId: runId, fieldId: safeFieldId }, auth(token));
  const draft = normalizeDraft(unwrap(response, "生成执行草稿失败"));
  if (draft.analysisRunId !== runId) throw new Error("服务端草稿不属于当前分析报告");
  if (draft.fieldId !== safeFieldId) throw new Error("服务端草稿与当前地块不一致");
  return draft;
}

export async function savePlanDraft(
  draftId: string,
  input: PlanDraftInput,
  token: string,
  expected?: { analysisRunId?: string; fieldId?: string },
): Promise<PlanDraft> {
  const id = requiredId(draftId, "草稿");
  const body = draftPayload(input);
  const response = await req.post<typeof body, ApiResult<unknown>>(
    `/api/operations/plan-drafts/${encodeURIComponent(id)}/save`,
    body,
    auth(token),
  );
  return draftResponse(response, { draftId: id, ...expected });
}

export async function confirmPlanDraft(
  draftId: string,
  input: PlanDraftInput,
  token: string,
  expected?: { analysisRunId?: string; fieldId?: string },
): Promise<PlanDraft> {
  const id = requiredId(draftId, "草稿");
  const body = draftPayload(input);
  const response = await req.post<typeof body, ApiResult<unknown>>(
    `/api/operations/plan-drafts/${encodeURIComponent(id)}/confirm`,
    body,
    auth(token),
  );
  const draft = draftResponse(response, { draftId: id, ...expected });
  if (draft.status !== "CONFIRMED" || !draft.taskId) throw new Error("服务端尚未确认草稿或未返回任务编号");
  return draft;
}

export async function getOperationTask(taskId: string, token: string): Promise<OperationTaskRecord> {
  const id = requiredId(taskId, "任务");
  const response = await req.get<ApiResult<unknown>>(
    `/api/operations/tasks/${encodeURIComponent(id)}`,
    auth(token),
  );
  return normalizeTask(unwrap(response, "农事任务暂不可用"), id);
}

export async function getOperationOutcome(taskId: string, token: string): Promise<OperationOutcomeRecord> {
  const id = requiredId(taskId, "任务");
  const response = await req.get<ApiResult<unknown>>(
    `/api/operations/tasks/${encodeURIComponent(id)}/outcome`,
    auth(token),
  );
  return normalizeOutcome(unwrap(response, "任务执行结果暂不可用"), id);
}

export async function startOperationTask(taskId: string, token: string): Promise<OperationTaskRecord> {
  const id = requiredId(taskId, "任务");
  const response = await req.post<Record<string, never>, ApiResult<unknown>>(
    `/api/operations/tasks/${encodeURIComponent(id)}/start`,
    {},
    auth(token),
  );
  return normalizeTask(unwrap(response, "开始任务失败"), id);
}

export async function completeOperationTask(
  taskId: string,
  input: CompleteOperationInput,
  token: string,
): Promise<CompletedOperation> {
  const id = requiredId(taskId, "任务");
  const summary = text(input.summary);
  if (!summary) throw new Error("执行结果摘要不能为空");
  const body = {
    summary,
    executionEvidence: text(input.executionEvidence) || "",
    notes: text(input.notes) || "",
  };
  const response = await req.post<typeof body, ApiResult<unknown>>(
    `/api/operations/tasks/${encodeURIComponent(id)}/complete`,
    body,
    auth(token),
  );
  const payload = record(unwrap(response, "提交完成记录失败"));
  if (!payload) throw new Error("服务端完成响应格式无法识别");
  const task = normalizeTask(payload.task ?? payload, id);
  if (task.status !== "COMPLETED") throw new Error("服务端尚未将任务标记为已完成");
  const outcome = normalizeOutcome(payload.outcome, id);
  return { task, outcome };
}

export async function scheduleOperationTask(taskId: string, scheduledFor: string, version: number, token: string): Promise<OperationTaskRecord> {
  const id = requiredId(taskId, "任务");
  const body = { scheduledFor, version };
  const response = await req.post<typeof body, ApiResult<unknown>>(`/api/operations/tasks/${encodeURIComponent(id)}/schedule`, body, auth(token));
  return normalizeTask(unwrap(response, "修改排期失败"), id);
}

import { useEffect, useId, useState, type FormEvent } from "react";
import {
  getMaturityRun,
  stageLabel,
  type MaturityObservation,
  type MaturityParcel,
  type MaturityReport,
} from "@/features/maturity/service";
import { permanence } from "@/utils/permanence";
import {
  checkedArtifactId,
  completeOperationTask,
  confirmPlanDraft,
  createPlanDraftFromAnalysis,
  errorMessage,
  getOperationOutcome,
  getOperationTask,
  listPlanDrafts,
  savePlanDraft,
  scheduleOperationTask,
  startOperationTask,
  type OperationOutcomeRecord,
  type OperationTaskRecord,
  type OperationTaskStatus,
  type PlanDraft,
  type PlanDraftInput,
  type PlanDraftPriority,
} from "./operationsChatArtifactsService";
import "./operations-chat-artifacts.css";

type PlanFromAnalysisProps = {
  analysisRunId: string;
  fieldId?: string;
};

type OperationTaskFeedbackProps = {
  taskId: string;
  expandedByDefault?: boolean;
  onScheduled?: (scheduledFor: string) => void;
};

type MaturityParcelArtifact = MaturityParcel & {
  topology?: string;
  scope?: string;
  sourceMode?: string;
};

type MaturityObservationArtifact = MaturityObservation & { topology?: string };

type MaturityReportArtifact = Omit<MaturityReport, "parcels"> & {
  parcels?: MaturityParcelArtifact[];
  topology?: string;
};

type EligibleHarvestField = {
  fieldId: string;
  name: string;
  evidence: MaturityObservationArtifact[];
  demo: boolean;
};

type DraftFormState = {
  title: string;
  detail: string;
  priority: PlanDraftPriority;
  scheduledFor: string;
};

type PlanPending = "create" | "save" | "confirm" | "reload" | null;
type TaskPending = "start" | "complete" | "reload" | "schedule" | null;

const harvestStages = new Set(["RIPE", "OVER_RIPE"]);
const draftStatusLabels = { DRAFT: "待确认草稿", CONFIRMED: "已创建任务" } as const;
const taskStatusLabels: Record<OperationTaskStatus, string> = {
  OPEN: "待处理",
  ASSIGNED: "已分配",
  IN_PROGRESS: "执行中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
};
const planPendingLabels: Record<Exclude<PlanPending, null>, string> = {
  create: "正在生成执行草稿…",
  save: "正在保存草稿…",
  confirm: "正在确认并创建任务…",
  reload: "正在读取服务端草稿…",
};

function formatTime(value?: string): string {
  if (!value) return "";
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp)
    ? new Date(timestamp).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false })
    : value;
}

function formatDate(value?: string): string {
  const key = dateInputValue(value);
  const [year, month, day] = key.split("-").map(Number);
  if (!year || !month || !day) return value || "";
  return `${year}年${month}月${day}日`;
}

function dateInputValue(value?: string): string {
  return value?.match(/^\d{4}-\d{2}-\d{2}/u)?.[0] ?? "";
}

function formFromDraft(draft: PlanDraft): DraftFormState {
  return {
    title: draft.title,
    detail: draft.detail,
    priority: draft.priority,
    scheduledFor: dateInputValue(draft.scheduledFor),
  };
}

function inputFromDraft(draft: PlanDraft, form: DraftFormState): PlanDraftInput {
  return {
    version: draft.version,
    title: form.title,
    detail: form.detail,
    priority: form.priority,
    scheduledFor: form.scheduledFor || undefined,
  };
}

function isDemoParcel(report: MaturityReportArtifact, parcel: MaturityParcelArtifact, evidence: MaturityObservationArtifact[]): boolean {
  return report.topology === "DEMO"
    || parcel.topology === "DEMO"
    || parcel.scope === "DEMO_MONITORING_REGION"
    || Boolean(parcel.sourceMode?.toUpperCase().includes("SYNTHETIC"))
    || evidence.some((observation) => observation.topology === "DEMO");
}

function eligibleHarvestFields(report?: MaturityReportArtifact): EligibleHarvestField[] {
  if (!report) return [];
  const fields = new Map<string, EligibleHarvestField>();
  for (const parcel of report.parcels ?? []) {
    const fieldId = checkedArtifactId(parcel.fieldId);
    if (!fieldId) continue;
    const evidence = (parcel.observations ?? []).filter((observation) => {
      const stage = typeof observation.stage === "string" ? observation.stage.toUpperCase() : "";
      return observation.status === "SUCCESS" && harvestStages.has(stage);
    }) as MaturityObservationArtifact[];
    if (!evidence.length) continue;
    const existing = fields.get(fieldId);
    if (existing) {
      existing.evidence.push(...evidence);
      existing.demo ||= isDemoParcel(report, parcel, evidence);
      continue;
    }
    fields.set(fieldId, {
      fieldId,
      name: parcel.name?.trim() || fieldId,
      evidence,
      demo: isDemoParcel(report, parcel, evidence),
    });
  }
  return [...fields.values()];
}

function stageSummary(field: EligibleHarvestField): string {
  return [...new Set(field.evidence.map((observation) => stageLabel(observation.stage)))].join(" / ");
}

function HarvestSuggestion({ field }: { field: EligibleHarvestField }) {
  return <div className="farmap-ops-artifact__suggestion">
    <p><strong>建议安排「{field.name}」采收前现场核验。</strong>先由现场人员确认果实成熟度、可采收范围和作业条件，再决定是否采收。</p>
    <p className="farmap-ops-artifact__caution">
      仅依据本报告中的可见画面；画面成熟不代表整块地已成熟，不能替代现场判断。
      {field.demo && <b> 当前为演示区域数据，不能作为真实采收或实测依据。</b>}
    </p>
    <details className="farmap-ops-artifact__evidence">
      <summary>查看 {field.evidence.length} 条合格画面依据</summary>
      <ul>
        {field.evidence.map((observation, index) => <li key={`${observation.cameraId ?? observation.cameraName ?? "observation"}-${index}`}>
          <span><b>{stageLabel(observation.stage)}</b> · {observation.cameraName || "未命名画面来源"}</span>
          {observation.capturedAt && <time dateTime={observation.capturedAt}>{formatTime(observation.capturedAt)}</time>}
          {observation.reason && <small>{observation.reason}</small>}
        </li>)}
      </ul>
    </details>
  </div>;
}

export function PlanFromAnalysis({ analysisRunId, fieldId }: PlanFromAnalysisProps) {
  const token = permanence.token.useToken();
  const runId = checkedArtifactId(analysisRunId);
  const preferredFieldId = checkedArtifactId(fieldId);
  const [report, setReport] = useState<MaturityReportArtifact>();
  const [drafts, setDrafts] = useState<PlanDraft[]>([]);
  const [selectedFieldId, setSelectedFieldId] = useState("");
  const [draft, setDraft] = useState<PlanDraft>();
  const [form, setForm] = useState<DraftFormState>();
  const [loading, setLoading] = useState(true);
  const [draftsReady, setDraftsReady] = useState(false);
  const [pending, setPending] = useState<PlanPending>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setReport(undefined);
    setDrafts([]);
    setDraftsReady(false);
    setSelectedFieldId("");
    setDraft(undefined);
    setForm(undefined);
    setPending(null);
    setError("");
    setNotice("");
    if (!runId) {
      setError("当前消息缺少有效的分析报告编号。");
      setLoading(false);
      return () => { active = false; };
    }
    void Promise.allSettled([getMaturityRun(runId), listPlanDrafts(runId, token)]).then(([reportResult, draftsResult]) => {
      if (!active) return;
      if (reportResult.status === "rejected") {
        setError(errorMessage(reportResult.reason, "成熟度分析报告加载失败"));
        setLoading(false);
        return;
      }
      const currentReport = reportResult.value as MaturityReportArtifact;
      const fields = eligibleHarvestFields(currentReport);
      const currentDrafts = draftsResult.status === "fulfilled" ? draftsResult.value : [];
      const draftsProblem = draftsResult.status === "rejected"
        ? errorMessage(draftsResult.reason, "服务端草稿读取失败")
        : "";
      const preferred = fields.find((field) => field.fieldId === preferredFieldId);
      const existing = currentDrafts.find((item) => fields.some((field) => field.fieldId === item.fieldId));
      const initialFieldId = preferred?.fieldId ?? existing?.fieldId ?? fields[0]?.fieldId ?? "";
      const initialDraft = currentDrafts.find((item) => item.fieldId === initialFieldId);
      setReport(currentReport);
      setDrafts(currentDrafts);
      setDraftsReady(draftsResult.status === "fulfilled");
      setSelectedFieldId(initialFieldId);
      setDraft(initialDraft);
      setForm(initialDraft ? formFromDraft(initialDraft) : undefined);
      if (draftsProblem) {
        setError(`分析报告已载入，但无法核对服务端草稿：${draftsProblem} 为避免重复创建，生成操作已暂停。`);
      } else if (initialDraft) {
        setNotice(initialDraft.status === "CONFIRMED" ? "已从服务端恢复已确认任务。" : "已从服务端恢复待确认草稿。");
      }
      setLoading(false);
    });
    return () => { active = false; };
  }, [runId, preferredFieldId, token, reloadKey]);

  const eligible = eligibleHarvestFields(report);
  const selected = eligible.find((field) => field.fieldId === selectedFieldId);
  const selectedDraft = draft?.fieldId === selectedFieldId ? draft : undefined;
  const busy = pending !== null;

  function rememberDraft(next: PlanDraft) {
    setDraft(next);
    setForm(formFromDraft(next));
    setDrafts((current) => [
      ...current.filter((item) => item.draftId !== next.draftId && item.fieldId !== next.fieldId),
      next,
    ]);
  }

  function selectField(nextFieldId: string) {
    if (busy) return;
    setSelectedFieldId(nextFieldId);
    setError("");
    const nextDraft = drafts.find((item) => item.fieldId === nextFieldId);
    setDraft(nextDraft);
    setForm(nextDraft ? formFromDraft(nextDraft) : undefined);
    setNotice(nextDraft ? "已载入该地块的服务端草稿。" : "该地块尚无草稿；生成草稿不会直接创建任务。");
  }

  async function reloadDrafts() {
    if (!runId || busy) return;
    setPending("reload");
    setError("");
    setNotice("");
    try {
      const latest = await listPlanDrafts(runId, token);
      setDrafts(latest);
      setDraftsReady(true);
      const latestDraft = latest.find((item) => item.fieldId === selectedFieldId);
      setDraft(latestDraft);
      setForm(latestDraft ? formFromDraft(latestDraft) : undefined);
      setNotice(latestDraft ? "已重新读取服务端草稿，未保存的本地修改已被覆盖。" : "服务端暂无该地块草稿。");
    } catch (reason: unknown) {
      setError(errorMessage(reason, "重新读取服务端草稿失败"));
    } finally {
      setPending(null);
    }
  }

  function currentInput(): PlanDraftInput | undefined {
    if (!draft || !form) return undefined;
    if (!form.title.trim()) {
      setError("请填写任务标题。");
      return undefined;
    }
    if (!form.detail.trim()) {
      setError("请填写执行说明。");
      return undefined;
    }
    if (form.scheduledFor && !/^\d{4}-\d{2}-\d{2}$/u.test(form.scheduledFor)) {
      setError("计划日期格式不正确。");
      return undefined;
    }
    return inputFromDraft(draft, form);
  }

  async function createDraft() {
    if (!runId || !selectedFieldId || !draftsReady || busy) return;
    setPending("create");
    setError("");
    setNotice("");
    try {
      const next = await createPlanDraftFromAnalysis(runId, selectedFieldId, token);
      rememberDraft(next);
      setNotice("执行草稿已生成。请核对内容后保存，或明确确认后创建任务。");
    } catch (reason: unknown) {
      const base = errorMessage(reason, "生成执行草稿失败");
      try {
        const latest = await listPlanDrafts(runId, token);
        setDrafts(latest);
        setDraftsReady(true);
        const recovered = latest.find((item) => item.fieldId === selectedFieldId);
        if (recovered) {
          rememberDraft(recovered);
          setNotice(recovered.status === "CONFIRMED" && recovered.taskId
            ? "创建响应未返回，但服务端已有已确认任务，已恢复最新状态。"
            : "创建响应未返回，但服务端已有该地块草稿，已恢复；请勿重复生成。");
        } else {
          setError(`${base}。服务端暂无该地块草稿，可在核对后手动重试。`);
        }
      } catch (recoveryReason: unknown) {
        setError(`${base}；无法核对服务端是否已创建草稿：${errorMessage(recoveryReason, "草稿读取失败")}。请先重新读取，避免重复创建。`);
      }
    } finally {
      setPending(null);
    }
  }

  async function saveDraft(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (!draft || pending) return;
    const input = currentInput();
    if (!input) return;
    setPending("save");
    setError("");
    setNotice("");
    try {
      const next = await savePlanDraft(draft.draftId, input, token, {
        analysisRunId: runId,
        fieldId: draft.fieldId,
      });
      rememberDraft(next);
      setNotice("草稿已保存。");
    } catch (reason: unknown) {
      setError(`${errorMessage(reason, "保存草稿失败")}。保存请求未确认成功；请先重新读取服务端草稿，再决定是否重试。`);
    } finally {
      setPending(null);
    }
  }

  async function confirmDraft() {
    if (!draft || pending) return;
    const input = currentInput();
    if (!input) return;
    setPending("confirm");
    setError("");
    setNotice("");
    try {
      const next = await confirmPlanDraft(draft.draftId, input, token, {
        analysisRunId: runId,
        fieldId: draft.fieldId,
      });
      rememberDraft(next);
      setNotice("任务已确认创建。");
    } catch (reason: unknown) {
      const base = errorMessage(reason, "确认草稿失败");
      if (!runId) {
        setError(`${base}。无法核对服务端状态，请刷新后重试。`);
        return;
      }
      try {
        const latest = await listPlanDrafts(runId, token);
        setDrafts(latest);
        setDraftsReady(true);
        const latestDraft = latest.find((item) => item.fieldId === selectedFieldId);
        if (!latestDraft) {
          setDraft(undefined);
          setForm(undefined);
          setError(`${base}。服务端当前未返回该地块草稿，请重新读取报告范围后再试。`);
        } else {
          setDraft(latestDraft);
          setForm(formFromDraft(latestDraft));
          if (latestDraft.status === "CONFIRMED" && latestDraft.taskId) {
            setNotice("确认响应未返回，但服务端已创建任务，已恢复最新状态。");
          } else {
            setError(`${base}。服务端草稿仍未确认；已恢复最新草稿，请核对后再次明确确认。`);
          }
        }
      } catch (recoveryReason: unknown) {
        setError(`${base}；无法核对服务端任务是否已创建：${errorMessage(recoveryReason, "草稿读取失败")}。请重新读取后再试。`);
      }
    } finally {
      setPending(null);
    }
  }

  return <section className="farmap-ops-artifact" aria-label="成熟度采收执行建议" aria-busy={loading || busy}>
    <header className="farmap-ops-artifact__heading">
      <div>
        <small>成熟度分析 · 农事执行</small>
        <h4>采收前现场核验</h4>
      </div>
      <span className="farmap-ops-artifact__badge" data-state={selectedDraft?.status ?? "NONE"}>
        {selectedDraft ? draftStatusLabels[selectedDraft.status] : "待生成草稿"}
      </span>
    </header>

    {pending && <p className="farmap-ops-artifact__pending" role="status">{planPendingLabels[pending]}</p>}
    {error && <div className="farmap-ops-artifact__alert" role="alert">
      <p>{error}</p>
      {(!report || !draftsReady) && <button type="button" disabled={busy} onClick={() => setReloadKey((value) => value + 1)}>
        {report ? "重新读取分析报告和草稿" : "重新读取分析报告"}
      </button>}
      {report && draftsReady && <button type="button" disabled={busy} onClick={() => void reloadDrafts()}>
        重新读取服务端草稿
      </button>}
    </div>}
    {notice && <p className="farmap-ops-artifact__notice" role="status">{notice}</p>}

    {loading ? <p className="farmap-ops-artifact__pending" role="status">正在读取分析报告与服务端草稿…</p> : report && <>
      <label className="farmap-ops-artifact__field">
        <span>目标地块</span>
        <select
          aria-label="选择有成熟画面依据的地块"
          value={selectedFieldId}
          disabled={busy || !eligible.length}
          onChange={(event) => selectField(event.target.value)}
        >
          <option value="">{eligible.length ? "请选择地块" : "本报告暂无合格地块"}</option>
          {eligible.map((field) => <option key={field.fieldId} value={field.fieldId}>
            {field.name} · {stageSummary(field)} · {field.evidence.length} 条画面{field.demo ? " · 演示区域" : ""}
          </option>)}
        </select>
      </label>

      {!eligible.length && <div className="farmap-ops-artifact__caution" role="status">
        报告里暂时没有可支持安排采收复核的地块观测。
      </div>}
      {eligible.length > 0 && !selected && <div className="farmap-ops-artifact__caution" role="status">
        请选择一个地块查看其画面依据。
      </div>}
      {selected && <HarvestSuggestion field={selected} />}

      {selected && draftsReady && !selectedDraft && <div className="farmap-ops-artifact__create">
        <button type="button" className="farmap-ops-artifact__primary" disabled={busy} onClick={() => void createDraft()}>
          {pending === "create" ? "正在生成…" : "生成执行草稿"}
        </button>
      </div>}
      {!draftsReady && !loading && <p className="farmap-ops-artifact__caution">
        尚未核对服务端是否已有草稿，生成操作已暂停。请先重新读取，避免重复创建。
      </p>}

      {selectedDraft?.status === "CONFIRMED" && selectedDraft.taskId && <section className="farmap-ops-artifact__confirmed" aria-label="已确认任务">
        <OperationTaskFeedback taskId={selectedDraft.taskId} />
      </section>}

      {selectedDraft?.status === "DRAFT" && form && <form className="farmap-ops-artifact__form" onSubmit={(event) => void saveDraft(event)}>
        <div className="farmap-ops-artifact__form-grid">
          <label className="farmap-ops-artifact__wide">任务标题
            <input
              value={form.title}
              maxLength={160}
              required
              disabled={busy}
              onChange={(event) => setForm((current) => current ? { ...current, title: event.target.value } : current)}
            />
          </label>
          <label className="farmap-ops-artifact__wide">执行说明
            <textarea
              value={form.detail}
              rows={4}
              maxLength={2000}
              required
              disabled={busy}
              onChange={(event) => setForm((current) => current ? { ...current, detail: event.target.value } : current)}
            />
          </label>
          <label>优先级
            <select
              value={form.priority}
              disabled={busy}
              onChange={(event) => setForm((current) => current ? { ...current, priority: event.target.value as PlanDraftPriority } : current)}
            >
              <option value="HIGH">高优先级</option>
              <option value="MEDIUM">中优先级</option>
              <option value="LOW">低优先级</option>
            </select>
          </label>
          <label>计划日期（可选）
            <input
              type="date"
              value={form.scheduledFor}
              disabled={busy}
              onChange={(event) => setForm((current) => current ? { ...current, scheduledFor: event.target.value } : current)}
            />
          </label>
        </div>
        <div className="farmap-ops-artifact__actions">
          <button type="submit" disabled={busy}>{pending === "save" ? "正在保存…" : "保存草稿"}</button>
          <button type="button" className="farmap-ops-artifact__primary" disabled={busy} onClick={() => void confirmDraft()}>
            {pending === "confirm" ? "正在确认…" : "确认并创建任务"}
          </button>
          <button type="button" disabled={busy} title="会覆盖未保存的表单修改" onClick={() => void reloadDrafts()}>
            重新读取
          </button>
        </div>
      </form>}
    </>}
  </section>;
}

export function OperationTaskFeedback({
  taskId,
  expandedByDefault = false,
  onScheduled,
}: OperationTaskFeedbackProps) {
  const token = permanence.token.useToken();
  const safeTaskId = checkedArtifactId(taskId);
  const detailsId = useId();
  const scheduleId = useId();
  const scheduleInputId = useId();
  const [task, setTask] = useState<OperationTaskRecord>();
  const [outcome, setOutcome] = useState<OperationOutcomeRecord>();
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState<TaskPending>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [completion, setCompletion] = useState({ summary: "", executionEvidence: "", notes: "" });
  const [detailsOpen, setDetailsOpen] = useState(expandedByDefault);
  const [scheduleOpen, setScheduleOpen] = useState(false);

  async function saveSchedule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!safeTaskId || task?.version === undefined || pending || !["OPEN", "ASSIGNED"].includes(task.status)) return;
    const day = new FormData(event.currentTarget).get("scheduledFor");
    if (typeof day !== "string" || !day) return;
    setPending("schedule");
    setError("");
    setNotice("");
    try {
      setTask(await scheduleOperationTask(safeTaskId, day, task.version, token));
      setScheduleOpen(false);
      setNotice("已保存计划日期。");
      onScheduled?.(day);
    } catch (failure) {
      setError(errorMessage(failure, "排期未保存，请重新读取任务后再试。"));
    } finally { setPending(null); }
  }

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    setNotice("");
    setTask(undefined);
    setOutcome(undefined);
    setPending(null);
    setCompletion({ summary: "", executionEvidence: "", notes: "" });
    setDetailsOpen(expandedByDefault);
    setScheduleOpen(false);
    if (!safeTaskId) {
      setError("当前消息缺少有效的任务编号。");
      setLoading(false);
      return () => { active = false; };
    }
    void (async () => {
      try {
        const current = await getOperationTask(safeTaskId, token);
        if (!active) return;
        setTask(current);
        if (current.status === "COMPLETED") {
          try {
            const persisted = await getOperationOutcome(safeTaskId, token);
            if (active) setOutcome(persisted);
          } catch (reason: unknown) {
            if (active) setError(`任务已完成，但服务端执行结果读取失败：${errorMessage(reason, "执行结果暂不可用")}`);
          }
        }
      } catch (reason: unknown) {
        if (active) setError(errorMessage(reason, "农事任务加载失败"));
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; };
  }, [safeTaskId, token, reloadKey, expandedByDefault]);

  const busy = pending !== null;
  const canEditSchedule = Boolean(task
    && (task.status === "OPEN" || task.status === "ASSIGNED")
    && task.version !== undefined);
  const detailsLabel = detailsOpen
    ? "收起详情"
    : task?.status === "IN_PROGRESS" ? "填写完成记录"
      : task?.status === "COMPLETED" ? "查看执行结果"
        : task?.status === "CANCELLED" ? "查看取消说明" : "查看任务详情";

  async function reconcileAfterStart(base: string) {
    if (!safeTaskId) return;
    try {
      const current = await getOperationTask(safeTaskId, token);
      setTask(current);
      if (current.status === "IN_PROGRESS") {
        setNotice(`${base}。重新读取后，服务端已显示任务为“执行中”。`);
      } else if (current.status === "COMPLETED") {
        try {
          const persisted = await getOperationOutcome(safeTaskId, token);
          setOutcome(persisted);
          setNotice(`${base}。重新读取后，服务端已显示任务为“已完成”，执行结果已同步。`);
        } catch (outcomeReason: unknown) {
          setOutcome(undefined);
          setError(`${base}。任务已完成，但执行结果读取失败：${errorMessage(outcomeReason, "执行结果暂不可用")}`);
        }
      } else {
        setError(`${base}。重新读取后，服务端任务状态仍为“${taskStatusLabels[current.status]}”。`);
      }
    } catch (reason: unknown) {
      setError(`${base}；无法重新读取服务端任务状态：${errorMessage(reason, "任务读取失败")}。请刷新后重试。`);
    }
  }

  async function startTask() {
    if (!safeTaskId || busy) return;
    setPending("start");
    setError("");
    setNotice("");
    try {
      const updated = await startOperationTask(safeTaskId, token);
      setTask(updated);
      setNotice(`服务端已确认任务状态为“${taskStatusLabels[updated.status]}”。`);
    } catch (reason: unknown) {
      await reconcileAfterStart(errorMessage(reason, "开始任务失败"));
    } finally {
      setPending(null);
    }
  }

  async function completeTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!safeTaskId || pending) return;
    const summary = completion.summary.trim();
    if (!summary) {
      setError("请填写执行结果摘要。");
      return;
    }
    setPending("complete");
    setError("");
    setNotice("");
    try {
      const completed = await completeOperationTask(safeTaskId, {
        summary,
        executionEvidence: completion.executionEvidence,
        notes: completion.notes,
      }, token);
      setTask(completed.task);
      setOutcome(completed.outcome);
      setCompletion({ summary: "", executionEvidence: "", notes: "" });
      setNotice("任务已完成，执行结果已由服务端保存。");
    } catch (reason: unknown) {
      const base = errorMessage(reason, "提交完成记录失败");
      try {
        const current = await getOperationTask(safeTaskId, token);
        setTask(current);
        if (current.status === "COMPLETED") {
          try {
            const persisted = await getOperationOutcome(safeTaskId, token);
            setOutcome(persisted);
            setNotice(`${base}。重新读取后，服务端已保存该任务的执行结果。`);
          } catch (outcomeReason: unknown) {
            setOutcome(undefined);
            setError(`${base}。服务端任务已完成，但执行结果读取失败：${errorMessage(outcomeReason, "执行结果暂不可用")}`);
          }
        } else {
          setError(`${base}。重新读取后，任务状态仍为“${taskStatusLabels[current.status]}”，完成记录尚未保存。`);
        }
      } catch (refreshReason: unknown) {
        setError(`${base}；无法重新读取服务端任务状态：${errorMessage(refreshReason, "任务读取失败")}。请刷新后重试。`);
      }
    } finally {
      setPending(null);
    }
  }

  return <section className="farmap-ops-task" aria-label="农事任务与执行反馈" aria-busy={loading || busy}>
    <header className="farmap-ops-task__heading">
      <div>
        <small>农事任务</small>
        <h4>{task?.title ?? (loading ? "正在读取任务" : "任务暂不可用")}</h4>
      </div>
      <span className="farmap-ops-artifact__badge" data-state={task?.status ?? "NONE"}>
        {task ? taskStatusLabels[task.status] : loading ? "读取中" : "未读取"}
      </span>
    </header>

    {pending && <p className="farmap-ops-artifact__pending" role="status">
      {pending === "start" ? "正在标记开始…" : pending === "schedule" ? "正在保存排期…" : "正在提交完成记录…"}
    </p>}
    {error && <div className="farmap-ops-artifact__alert" role="alert">
      <p>{error}</p>
      <button type="button" disabled={busy || loading} onClick={() => setReloadKey((value) => value + 1)}>
        重新读取任务
      </button>
    </div>}
    {notice && <p className="farmap-ops-artifact__notice" role="status">{notice}</p>}

    {loading ? <p className="farmap-ops-artifact__pending" role="status">正在读取任务及服务端执行结果…</p> : task && <>
      <div className="farmap-ops-task__summary">
        <span className={`farmap-ops-task__summary-item${task.scheduledFor ? "" : " is-empty"}`}>
          <span className="farmap-ops-task__summary-label">计划</span>
          {task.scheduledFor
            ? <time dateTime={dateInputValue(task.scheduledFor) || undefined}>{formatDate(task.scheduledFor)}</time>
            : "未排期"}
        </span>
        {task.fieldName && <span className="farmap-ops-task__summary-item">
          <span className="farmap-ops-task__summary-label">地块</span>
          {task.fieldName}
        </span>}
        {task.completedAt && <span className="farmap-ops-task__summary-item">
          <span className="farmap-ops-task__summary-label">完成</span>
          <time dateTime={task.completedAt}>{formatTime(task.completedAt)}</time>
        </span>}
      </div>

      <div className="farmap-ops-task__actions">
        {canEditSchedule && <button
          type="button"
          aria-expanded={scheduleOpen}
          aria-controls={scheduleOpen ? scheduleId : undefined}
          disabled={busy}
          onClick={() => setScheduleOpen((open) => !open)}
        >
          {scheduleOpen ? "收起排期" : "修改排期"}
        </button>}
        <button
          type="button"
          aria-expanded={detailsOpen}
          aria-controls={detailsOpen ? detailsId : undefined}
          onClick={() => setDetailsOpen((open) => !open)}
        >
          {detailsLabel}
        </button>
      </div>

      {scheduleOpen && canEditSchedule && <form
        id={scheduleId}
        key={task.version}
        className="farmap-ops-task__schedule"
        aria-label="修改任务排期"
        onSubmit={(event) => void saveSchedule(event)}
      >
        <label htmlFor={scheduleInputId}>计划日期</label>
        <div className="farmap-ops-task__schedule-row">
          <input
            id={scheduleInputId}
            type="date"
            name="scheduledFor"
            required
            defaultValue={dateInputValue(task.scheduledFor)}
            disabled={busy}
          />
          <button type="submit" className="farmap-ops-artifact__primary" disabled={busy}>
            {pending === "schedule" ? "正在保存…" : "保存排期"}
          </button>
          <button type="button" disabled={busy} onClick={() => setScheduleOpen(false)}>取消</button>
        </div>
      </form>}

      {detailsOpen && <section id={detailsId} className="farmap-ops-task__details" aria-label="任务详情">
        {(task.assignee || task.assignedAt || task.startedAt) && <dl className="farmap-ops-task__facts">
          <div><dt>负责人</dt><dd>{task.assignee || "未分配"}</dd></div>
          {task.assignedAt && <div><dt>分配时间</dt><dd>{formatTime(task.assignedAt)}</dd></div>}
          {task.startedAt && <div><dt>开始时间</dt><dd>{formatTime(task.startedAt)}</dd></div>}
        </dl>}
        {task.goal && <p className="farmap-ops-task__goal">{task.goal}</p>}

        {(task.status === "OPEN" || task.status === "ASSIGNED") && <div className="farmap-ops-task__action">
          <p>确认现场条件具备后，再将任务标记为执行中。</p>
          <button type="button" className="farmap-ops-artifact__primary" disabled={busy} onClick={() => void startTask()}>
            {pending === "start" ? "正在标记开始…" : "标记为执行中"}
          </button>
        </div>}

        {task.status === "IN_PROGRESS" && <form className="farmap-ops-artifact__form" aria-label="完成记录" onSubmit={(event) => void completeTask(event)}>
          <h5>完成记录</h5>
          <div className="farmap-ops-artifact__form-grid">
            <label className="farmap-ops-artifact__wide">执行结果摘要
              <textarea
                value={completion.summary}
                rows={3}
                maxLength={1000}
                required
                disabled={busy}
                onChange={(event) => setCompletion((current) => ({ ...current, summary: event.target.value }))}
              />
            </label>
            <label className="farmap-ops-artifact__wide">执行证据（可选）
              <textarea
                value={completion.executionEvidence}
                rows={2}
                maxLength={2000}
                disabled={busy}
                placeholder="例如现场照片编号、记录链接或作业凭证"
                onChange={(event) => setCompletion((current) => ({ ...current, executionEvidence: event.target.value }))}
              />
            </label>
            <label className="farmap-ops-artifact__wide">备注（可选）
              <textarea
                value={completion.notes}
                rows={2}
                maxLength={1000}
                disabled={busy}
                onChange={(event) => setCompletion((current) => ({ ...current, notes: event.target.value }))}
              />
            </label>
          </div>
          <div className="farmap-ops-artifact__actions">
            <button type="submit" className="farmap-ops-artifact__primary" disabled={busy}>
              {pending === "complete" ? "正在提交…" : "提交完成记录"}
            </button>
          </div>
        </form>}

        {task.status === "COMPLETED" && <section className="farmap-ops-task__outcome" aria-label="执行结果">
          <h5>执行结果</h5>
          {outcome ? <dl className="farmap-ops-task__outcome-facts">
            <div><dt>执行摘要</dt><dd>{outcome.summary}</dd></div>
            <div><dt>执行证据</dt><dd>{outcome.executionEvidence || "未填写"}</dd></div>
            <div><dt>备注</dt><dd>{outcome.notes || "未填写"}</dd></div>
            {outcome.actor && <div><dt>记录人</dt><dd>{outcome.actor}</dd></div>}
            {outcome.createdAt && <div><dt>保存时间</dt><dd>{formatTime(outcome.createdAt)}</dd></div>}
          </dl> : <>
            <p className="farmap-ops-artifact__caution">当前未取得服务端执行结果。不会在本地伪造完成内容。</p>
            <div className="farmap-ops-artifact__actions">
              <button type="button" disabled={busy || loading} onClick={() => setReloadKey((value) => value + 1)}>
                重新读取执行结果
              </button>
            </div>
          </>}
        </section>}

        {task.status === "CANCELLED" && <section className="farmap-ops-task__outcome" aria-label="取消说明">
          <h5>取消说明</h5>
          <p>{task.cancelledReason || "服务端未返回取消原因。"}</p>
          {task.cancelledAt && <small>取消时间：{formatTime(task.cancelledAt)}</small>}
        </section>}
      </section>}
    </>}
  </section>;
}

import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { AlertTriangle, ArrowUpRight, BookOpen, CalendarDays, ChartNoAxesCombined, ChevronDown, CloudSun, Leaf, ListChecks, Map, Maximize2, Sprout } from "lucide-react";
import { Modal } from "antd";
import type { AgentRun } from "@/features/agent/contract";
import { approveAgentAction, getAgentRun, rejectAgentAction } from "@/features/agent/service";
import { getMaturityRun } from "@/features/maturity/service";
import { OperationTaskFeedback, PlanFromAnalysis } from "./OperationsChatArtifacts";
import { WeatherPresentationCard } from "./WeatherPresentationCard";
import { OperationsTaskView } from "./OperationsTaskViews";
import { operationsViews } from "@/routes/navigation";
import PlanningRecommendations from "@/features/pro-workspaces/PlanningRecommendations";
import { detailRoute, parseUiBlock, uiId, type AgentUiAction, type AgentUiBlock, type BlockComponentType, type BlockData, type BlockItem, type MaturityStage } from "./uiBlocks";
import "./agent-ui-blocks.css";

type BlockActions = {
  onPrompt: (message: string) => void;
  onSelectField?: (id: string) => void;
  onOpenDetail?: (route: string) => void;
  onOpenAnalysis?: (runId: string, farmId?: number, fieldId?: string) => void;
  busy?: boolean;
};
type ContentProps = BlockActions & { block: AgentUiBlock };

const blockStatus = { ready: "已获取", loading: "读取中", unavailable: "暂无数据", "needs-input": "待补充", completed: "已完成", failed: "暂不可用" };
const runStatus: Record<AgentRun["status"], string> = {
  CREATED: "等待准备草稿", PLANNING: "正在制定计划", WAITING_FOR_APPROVAL: "等待确认", RUNNING: "工作流正在处理",
  COMPLETED: "工作流已完成", FAILED: "工作流失败", CANCELLED: "已中止",
};
const stepStatus = { PENDING: "等待执行", RUNNING: "进行中", COMPLETED: "已完成", FAILED: "失败", SKIPPED: "已跳过" };
const observationQualityLabels: Record<string, string> = { USABLE: "可判读", INSUFFICIENT: "证据不足", UNKNOWN: "未确认" };

function showValue(item: BlockItem) {
  if (item.value === undefined) return "";
  return [item.value, item.unit].filter((value) => value !== undefined && value !== "").join(" ");
}

function DataRows({ block }: ContentProps) {
  const metrics = block.data?.metrics ?? [];
  const items = block.data?.items ?? [];
  return <>
    {metrics.length > 0 && <dl className="farmap-block__metrics">{metrics.map((item, index) => <div key={index}>
      <dt>{item.label}</dt><dd>{showValue(item) || "—"}</dd>
    </div>)}</dl>}
    {items.length > 0 && <dl className="farmap-block__rows">{items.map((item, index) => <div key={index}>
      <dt>{item.label}</dt>{(item.value !== undefined || item.status) && <dd>{showValue(item)}{item.status && <span className="farmap-block__tag">{item.status}</span>}</dd>}
    </div>)}</dl>}
    {!metrics.length && !items.length && block.status === "unavailable" && !block.summary && <p>当前没有可展示的数据。</p>}
  </>;
}

function Plot({ block, ...actions }: ContentProps) {
  const series = block.data?.series ?? [];
  return <>
    <DataRows block={block} {...actions} />
    {series.map(({ label, points }, index) => {
      const values = points.map(({ value }) => value);
      const low = Math.min(...values);
      const high = Math.max(...values);
      const coordinates = values.map((value, i) => `${18 + 604 * i / Math.max(1, values.length - 1)},${high === low ? 70 : 120 - 100 * (value - low) / (high - low)}`);
      return <figure className="farmap-block__plot" key={index}>
        <figcaption>{label}</figcaption>
        <svg viewBox="0 0 640 146" role="img" aria-label={`${label}，${points[0].label}至${points.at(-1)?.label}`}>
          {[20, 70, 120].map((y) => <line key={y} x1="18" x2="622" y1={y} y2={y} stroke="#e3e9e3" />)}
          <polyline points={coordinates.join(" ")} fill="none" stroke="#176b4d" strokeWidth="2.5" strokeLinejoin="round" />
          {coordinates.map((pair, pointIndex) => { const [x, y] = pair.split(","); return <circle key={pointIndex} cx={x} cy={y} r="3" fill="#176b4d"><title>{points[pointIndex].label}：{values[pointIndex]}</title></circle>; })}
        </svg>
        <div className="farmap-block__axis"><span>{points[0].label}</span><span>{points.at(-1)?.label}</span></div>
        <details className="farmap-block__sources"><summary>查看数据</summary><dl className="farmap-block__rows">
          {points.map((point, i) => <div key={i}><dt>{point.label}</dt><dd>{point.value}</dd></div>)}
        </dl></details>
      </figure>;
    })}
  </>;
}

function MapOverview({ block, onSelectField, ...actions }: ContentProps) {
  const locations = block.data?.locations ?? [];
  if (!locations.length) return <DataRows block={block} onSelectField={onSelectField} {...actions} />;
  const west = Math.min(...locations.map(({ longitude }) => longitude));
  const east = Math.max(...locations.map(({ longitude }) => longitude));
  const south = Math.min(...locations.map(({ latitude }) => latitude));
  const north = Math.max(...locations.map(({ latitude }) => latitude));
  return <>
    <figure className="farmap-block__plot">
      <svg viewBox="0 0 640 175" role="img" aria-label="地块相对位置示意，北向上">
        {[30, 65, 100, 135].map((y) => <line key={y} x1="30" x2="610" y1={y} y2={y} stroke="#e1e9df" />)}
        {[80, 200, 320, 440, 560].map((x) => <line key={x} x1={x} x2={x} y1="30" y2="135" stroke="#e1e9df" />)}
        <text x="608" y="22" fill="#536c59" fontSize="11" textAnchor="end">北 ↑</text>
        {locations.map(({ id, latitude, longitude, label }, index) => {
          const x = west === east ? 320 : 44 + 552 * (longitude - west) / (east - west);
          const y = south === north ? 85 : 132 - 92 * (latitude - south) / (north - south);
          return <g key={id}><circle cx={x} cy={y} r="9" fill="#176b4d" /><text x={x} y={y + 3} textAnchor="middle" fontSize="9" fill="#fff">{index + 1}</text><title>{label}</title></g>;
        })}
      </svg>
      <figcaption>地块相对位置</figcaption>
    </figure>
    <div className="farmap-block__actions">{locations.map(({ id, label }, index) =>
      <button key={id} type="button" onClick={() => onSelectField?.(id)} disabled={!onSelectField}>{index + 1}. {label}</button>)}</div>
  </>;
}

const maturityStages: Record<MaturityStage, { label: string; color: string }> = {
  UNRIPE: { label: "未成熟", color: "#3c8a5b" }, TURNING: { label: "转色期", color: "#bf8b19" },
  RIPE: { label: "外观成熟", color: "#ba5140" }, OVER_RIPE: { label: "过熟风险", color: "#812e35" }, MIXED: { label: "混合", color: "#845ba3" }, UNKNOWN: { label: "未知", color: "#73817a" },
};

function MaturityOverview({ block, onOpenAnalysis }: ContentProps) {
  const [report, setReport] = useState<{ id: string; data?: BlockData }>();
  const reportId = block.data?.runId;
  const hasInlineObservations = Boolean(block.data?.parcels?.length);
  useEffect(() => {
    if (!reportId || hasInlineObservations) return;
    let active = true;
    void getMaturityRun(reportId).then((result) => {
      if (active) setReport({ id: reportId, data: parseUiBlock({ ...block, data: result })?.data });
    }).catch(() => {
      if (active) setReport({ id: reportId });
    });
    return () => { active = false; };
  }, [block, reportId, hasInlineObservations]);
  const currentReport = report?.id === reportId ? report : undefined;
  const data = currentReport?.data ?? block.data;
  const parcels = data?.parcels ?? [];
  if (reportId && data?.mode === "FARM") {
    const observations = parcels.flatMap((parcel) => parcel.observations);
    const valid = observations.filter((item) => item.status === "SUCCESS").length;
    const samples = observations.filter((item) => item.imageUrl).slice(0, 3);
    return <section className="farmap-maturity">
      {data.farmName && <strong>{data.farmName}</strong>}
      {currentReport?.data && <div className="farmap-inspection__metrics">
        <span>监测画面 <b>{observations.length}</b></span>
        <span>有效识别 <b>{valid}</b></span>
        <span>待复核 <b>{observations.length - valid}</b></span>
      </div>}
      <p>查看区域分布、观测覆盖与摄像头证据。</p>
      {samples.length > 0 && <details>
        <summary>查看摄像头样本画面</summary>
        <div className="farmap-inspection__samples">{samples.map((sample, index) => <figure key={`${sample.cameraId ?? "image"}-${index}`}>
          <img src={sample.imageUrl} alt={`${sample.cameraName}观测画面`} loading="lazy" referrerPolicy="no-referrer" />
          <figcaption>{sample.cameraName}</figcaption>
        </figure>)}</div>
      </details>}
      {onOpenAnalysis && <div className="farmap-block__actions"><button type="button" onClick={() => onOpenAnalysis(reportId, data.farmId)}>打开空间分析 <ArrowUpRight size={14} /></button></div>}
    </section>;
  }
  if (data?.mode === "CAMERA" || data?.mode === "FIELD" || data?.mode === "IMAGE") {
    return <section className="farmap-maturity" aria-label="图像分析结果">
      {!parcels.length && reportId && <p role="status">{currentReport ? "暂时无法读取分析记录。" : "正在载入分析记录…"}</p>}
      {parcels.map((parcel, index) => <div key={parcel.fieldId ?? index} className="farmap-maturity__image-result">
        <strong>{parcel.name}</strong>
        {parcel.reason && <p>{parcel.reason}</p>}
        {parcel.observations.map((observation, i) => <div key={observation.cameraId ?? i} className="farmap-maturity__single-observation">
          {observation.imageUrl && <img src={observation.imageUrl} alt={`${observation.cameraName}观测画面`} loading="lazy" referrerPolicy="no-referrer" />}
          <div><strong>{observation.cameraName} · {observation.status === "OFFLINE" ? "离线" : data.kind === "MATURITY" || observation.label === "UNKNOWN" ? maturityStages[observation.stage].label : observation.label ?? maturityStages[observation.stage].label}</strong>
            {observation.quality && <small>画面质量 · {observationQualityLabels[observation.quality] ?? observation.quality}</small>}
            {observation.capturedAt && <small>{new Date(observation.capturedAt).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })}</small>}
            {observation.reason && <p>{observation.reason}</p>}
          </div>
        </div>)}
      </div>)}
      {reportId && onOpenAnalysis && <div className="farmap-block__actions"><button type="button" onClick={() => onOpenAnalysis(reportId, data.farmId, data.fieldId ?? parcels[0]?.fieldId)}>{data.mode === "IMAGE" ? "打开分析记录" : "查看所属区域"} <ArrowUpRight size={14} /></button></div>}
    </section>;
  }
  const mapped = parcels.filter((parcel) => parcel.latitude !== undefined && parcel.longitude !== undefined);
  const observed = parcels.filter((parcel) => parcel.observations.some((observation) => observation.capturedAt || observation.imageUrl)).length;
  const west = Math.min(...mapped.map((parcel) => parcel.longitude!));
  const east = Math.max(...mapped.map((parcel) => parcel.longitude!));
  const south = Math.min(...mapped.map((parcel) => parcel.latitude!));
  const north = Math.max(...mapped.map((parcel) => parcel.latitude!));
  return <section className="farmap-maturity" aria-label="成熟度观测分布">
    <div><strong>{data?.farmName || "农场"}</strong>{data?.observedAt && <span> · 观测时间：{data.observedAt}</span>}</div>
    <p>已列出 {parcels.length} 个地块或未绑定摄像头点位，其中 {observed} 个取得画面，{mapped.length} 个有位置坐标。</p>
    <p className="farmap-maturity__note">成熟度基于相机观测，仅反映画面中的情况，不能代表整个地块或作为准确采收依据。</p>
    {mapped.length > 0 && <figure className="farmap-block__plot">
      <svg viewBox="0 0 640 175" role="img" aria-label="有坐标的地块相对位置示意，北向上；具体状态见下方列表">
        {[30, 65, 100, 135].map((y) => <line key={y} x1="30" x2="610" y1={y} y2={y} stroke="#e1e9df" />)}
        {[80, 200, 320, 440, 560].map((x) => <line key={x} x1={x} x2={x} y1="30" y2="135" stroke="#e1e9df" />)}
        <text x="608" y="22" fill="#536c59" fontSize="11" textAnchor="end">北 ↑</text>
        {mapped.map((parcel, index) => {
          const x = west === east ? 320 : 44 + 552 * (parcel.longitude! - west) / (east - west);
          const y = south === north ? 85 : 132 - 92 * (parcel.latitude! - south) / (north - south);
          return <g key={index}><circle cx={x} cy={y} r="11" fill={maturityStages[parcel.stage].color} stroke="#fff" strokeWidth="2" /><text x={x} y={y + 3} textAnchor="middle" fontSize="9" fill="#fff">{index + 1}</text><title>{parcel.name}：{maturityStages[parcel.stage].label}</title></g>;
        })}
      </svg><figcaption>有坐标的地块相对位置示意（非地块边界）</figcaption>
    </figure>}
    <div className="farmap-maturity__legend" aria-label="成熟度图例">{Object.entries(maturityStages).map(([stage, value]) => <span key={stage}><i style={{ background: value.color }} />{value.label}</span>)}</div>
    {!parcels.length ? <p>暂无地块或相机观测数据。</p> : <ul className="farmap-maturity__parcels">{parcels.map((parcel, index) => <li key={`${parcel.fieldId ?? parcel.name}-${index}`}>
      <div className="farmap-maturity__parcel-heading"><strong>{mapped.includes(parcel) ? `${mapped.indexOf(parcel) + 1}. ` : ""}{parcel.name}</strong><span style={{ color: maturityStages[parcel.stage].color }}>{maturityStages[parcel.stage].label}</span></div>
      {!parcel.fieldId && <small>未关联地块</small>}{parcel.latitude === undefined || parcel.longitude === undefined ? <small>无位置坐标</small> : null}
      {parcel.reason && <p>{parcel.reason}</p>}
      {parcel.observations.length ? <details><summary>相机观测（{parcel.observations.length}）</summary><ul className="farmap-maturity__observations">{parcel.observations.map((observation, i) => <li key={`${observation.cameraId ?? observation.cameraName}-${i}`}>
        <strong>{observation.cameraName}</strong> · {maturityStages[observation.stage].label}
        {observation.source === "BROWSER_CAMERA_FRAME" && <> · 上传的监控截图</>}
        {observation.source === "HIKVISION_REMOTE_CAPTURE" && <> · 云端抓拍</>}
        {observation.capturedAt && <> · {observation.capturedAt}</>}
        {observation.reason && <p>{observation.reason}</p>}
        {observation.imageUrl && <img src={observation.imageUrl} alt={`${observation.cameraName}的观测图像`} loading="lazy" referrerPolicy="no-referrer" />}
      </li>)}</ul></details> : <small>暂无相机观测</small>}
    </li>)}</ul>}
  </section>;
}

function InspectionProgress({ block }: ContentProps) {
  const counts = block.data?.inspection;
  if (!counts) return <p role="status">{block.status === "failed" ? "巡检未完成" : "正在核对可观测范围…"}</p>;
  return <div className="farmap-inspection" aria-label="巡检统计">
    {counts.total > 0 ? <progress aria-label="已处理画面来源" value={counts.processed} max={counts.total} /> : <p>当前范围暂无可用画面来源。</p>}
    <div className="farmap-inspection__metrics">
      <span>已处理 <b>{counts.processed} / {counts.total}</b></span>
      <span>取得画面 <b>{counts.captured}</b></span>
      <span>有效识别 <b>{counts.valid}</b></span>
    </div>
  </div>;
}

function Recommendation({ block }: ContentProps) {
  return block.data?.analysisRunId ? <PlanFromAnalysis analysisRunId={block.data.analysisRunId} fieldId={block.data.fieldId} /> : null;
}

function TaskFeedback({ block }: ContentProps) {
  return block.data?.taskId ? <OperationTaskFeedback taskId={block.data.taskId} /> : null;
}

function FarmDirectory({ block }: ContentProps) {
  const farms = block.data?.farms ?? [];
  return farms.length ? <ul className="farmap-farm-directory">
    {farms.map((farm) => <li key={farm.id}><Map size={16} aria-hidden="true" /><strong>{farm.name}</strong></li>)}
  </ul> : null;
}

function ParcelOverview({ block, ...actions }: ContentProps) {
  const fields = block.data?.items ?? [];
  const name = fields.find((item) => item.label === "地块");
  const attributes = fields.filter((item) => item !== name && item.value !== undefined && item.value !== "");
  if (!name) return <DataRows block={block} {...actions} />;
  return <div className="farmap-parcel-overview">
    <strong>{showValue(name)}</strong>
    {attributes.length > 0 && <dl>{attributes.map((item, index) => <div key={`${item.label}-${index}`}><dt>{item.label}</dt><dd>{showValue(item)}</dd></div>)}</dl>}
  </div>;
}

const actionCards = new Set<BlockComponentType>(["FarmPlanCard", "FertilizerPlanCard", "IrrigationPlanCard", "FarmCalendarCard",
  "FarmTaskList", "ClarificationForm", "ConfirmationCard", "ApprovalCard", "TaskProgressCard", "FeedbackCard", "FarmRecommendationCard", "OperationTaskCard", "OperationsQueryCard"]);
const analysisCards = new Set<BlockComponentType>(["CropGrowthChart", "WeatherTrendChart", "SoilTrendChart", "ParcelCompareChart", "FarmAnalyticsCard",
  "DiagnosisCard", "PestDiagnosisCard", "RiskAlertCard", "DiagnosisEvidenceCard", "EvidenceCard", "ExpertReviewCard", "FarmMapCard", "FarmMaturityCard", "FarmAdviceCard"]);

function TaskQuery({ block, onOpenDetail }: ContentProps) {
  const scope = block.data;
  if (!scope?.taskView || !scope.farmId) return null;
  const fullView = operationsViews[scope.taskView];
  const range = new URLSearchParams({ farmId: String(scope.farmId) });
  if (scope.fieldId) range.set("fieldId", scope.fieldId);
  if (scope.from) range.set("from", scope.from);
  if (scope.to) range.set("to", scope.to);
  if (scope.taskFilter) range.set("filter", scope.taskFilter);
  return <>
    <OperationsTaskView farmId={scope.farmId} fieldId={scope.fieldId} from={scope.from} to={scope.to} filter={scope.taskFilter} view={scope.taskView} embedded />
    {onOpenDetail && <div className="farmap-block__actions"><button type="button" onClick={() => onOpenDetail(`${fullView.path}&${range.toString()}`)}>
      打开{fullView.label} <ArrowUpRight size={14} />
    </button></div>}
  </>;
}

function FarmAdvice({ block }: ContentProps) {
  return block.data?.farmId ? <PlanningRecommendations farmId={block.data.farmId} scopeFieldId={block.data.fieldId ?? null} compact /> : null;
}

function Clarification({ block, onPrompt, onSelectField, busy }: ContentProps) {
  const fields = block.data?.fields ?? [];
  const [submitted, setSubmitted] = useState(false);
  if (submitted) return <p role="status">已提交补充信息。</p>;
  if (!fields.length) return null;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const values = new FormData(event.currentTarget);
    const fieldId = values.get("fieldId");
    if (typeof fieldId === "string" && fieldId && fields.find((field) => field.id === "fieldId")?.options?.some((option) => option.value === fieldId)) {
      onSelectField?.(fieldId);
    }
    const response = fields.flatMap((field) => {
      const value = String(values.get(field.id) ?? "").trim();
      const label = field.options?.find((option) => option.value === value)?.label ?? value;
      return label ? [`${field.label}：${label}`] : [];
    }).join("\n");
    if (!response) return;
    onPrompt(`${block.data?.originalRequest ? `继续处理：${block.data.originalRequest}\n` : ""}补充信息：\n${response}`);
    setSubmitted(true);
  }
  return <form className="farmap-block__form" onSubmit={submit}>
    {fields.map((field) => <label key={field.id}>{field.label}
      {field.options?.length ? <select name={field.id} aria-label={field.label} defaultValue="" required={field.required}>
        <option value="">请选择</option>{field.options.map(({ label, value }) => <option key={value} value={value}>{label}</option>)}
      </select> : <input name={field.id} aria-label={field.label} required={field.required} maxLength={400} />}
    </label>)}
    <button type="submit" className="farmap-block__primary" disabled={busy}>提交并继续</button>
  </form>;
}

function Workflow({ block, onOpenDetail }: ContentProps) {
  const runId = block.data?.runId;
  const [run, setRun] = useState<AgentRun>();
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const refreshable = !!runId;
  useEffect(() => {
    if (!runId) return;
    let active = true;
    let timer: number | undefined;
    const refresh = async () => {
      try {
        const current = await getAgentRun(runId);
        if (!active) return;
        setRun(current);
        setError("");
        if (!["COMPLETED", "FAILED", "CANCELLED"].includes(current.status)) timer = window.setTimeout(refresh, 3000);
      } catch {
        if (!active) return;
        setError("暂时无法读取任务状态，正在重试。");
        timer = window.setTimeout(refresh, 12_000);
      }
    };
    void refresh();
    return () => { active = false; window.clearTimeout(timer); };
  }, [runId]);
  async function decide(actionId: string, approve: boolean) {
    if (!runId || pending) return;
    setPending(true);
    try {
      setRun(await (approve ? approveAgentAction(runId, actionId) : rejectAgentAction(runId, actionId)));
      setError("");
    } catch { setError("未能提交操作，请刷新状态后再试。"); }
    finally { setPending(false); }
  }
  const task = run?.output?.task;
  const taskId = task && typeof task === "object" && "taskId" in task ? uiId(task.taskId) : undefined;
  return <div className="farmap-block__workflow">
    {refreshable && <p aria-live="polite">{run ? runStatus[run.status] : error ? "状态暂不可用" : "正在读取任务状态…"}</p>}
    {run?.steps?.length ? <ol aria-label="执行进度">{run.steps.map((step) => <li key={step.id}><span>{step.title}</span><small>{stepStatus[step.status]}</small></li>)}</ol> : null}
    {run?.status === "WAITING_FOR_APPROVAL" && run.actions.filter((action) => action.approvalRequired && action.approvalState === "REQUIRED").map((action) => (
      <div key={action.id} className="farmap-block__approval">
        <strong>{action.title}</strong><p>{action.detail}</p>
        {action.scheduledFor && <p>计划日期 {action.scheduledFor}</p>}
        <div className="farmap-block__actions">
          <button type="button" className="farmap-block__primary" disabled={pending} onClick={() => void decide(action.id, true)}>确认并执行</button>
          <button type="button" disabled={pending} onClick={() => void decide(action.id, false)}>拒绝</button>
        </div>
      </div>
    ))}
    {run?.failureMessage && <p role="alert">{run.failureMessage}</p>}
    {error && <p role="status">{error}</p>}
    {taskId ? <OperationTaskFeedback taskId={taskId} /> : run?.status === "COMPLETED" && onOpenDetail && <button type="button" onClick={() => onOpenDetail("/operations/tasks")}>查看历史任务 <ArrowUpRight size={13} /></button>}
  </div>;
}

// Each entry selects a renderer, never interprets markup or actions supplied by the model.
const registry: Record<BlockComponentType, { content: (props: ContentProps) => ReactNode; icon: typeof Sprout }> = {
  FarmDirectoryCard: { content: FarmDirectory, icon: Map },
  ParcelOverviewCard: { content: ParcelOverview, icon: Map }, WeatherCard: { content: WeatherPresentationCard, icon: CloudSun },
  EnvironmentCard: { content: DataRows, icon: Leaf }, CropInfoCard: { content: DataRows, icon: Sprout },
  FarmHistoryCard: { content: DataRows, icon: CalendarDays }, CropGrowthChart: { content: Plot, icon: ChartNoAxesCombined },
  WeatherTrendChart: { content: Plot, icon: CloudSun }, SoilTrendChart: { content: Plot, icon: ChartNoAxesCombined },
  ParcelCompareChart: { content: Plot, icon: ChartNoAxesCombined }, FarmAnalyticsCard: { content: Plot, icon: ChartNoAxesCombined },
  DiagnosisCard: { content: DataRows, icon: Leaf }, PestDiagnosisCard: { content: DataRows, icon: Leaf },
  RiskAlertCard: { content: DataRows, icon: AlertTriangle }, DiagnosisEvidenceCard: { content: DataRows, icon: BookOpen },
  FarmPlanCard: { content: Workflow, icon: CalendarDays }, FertilizerPlanCard: { content: Workflow, icon: Sprout },
  IrrigationPlanCard: { content: Workflow, icon: Leaf }, FarmCalendarCard: { content: DataRows, icon: CalendarDays },
  FarmTaskList: { content: DataRows, icon: ListChecks }, ClarificationForm: { content: Clarification, icon: ListChecks },
  ConfirmationCard: { content: Workflow, icon: ListChecks }, ApprovalCard: { content: Workflow, icon: ListChecks },
  TaskProgressCard: { content: Workflow, icon: ListChecks }, FeedbackCard: { content: Clarification, icon: ListChecks },
  EvidenceCard: { content: DataRows, icon: BookOpen }, KnowledgeSourceCard: { content: DataRows, icon: BookOpen },
  ExpertReviewCard: { content: DataRows, icon: BookOpen }, FarmMapCard: { content: MapOverview, icon: Map }, FarmMaturityCard: { content: MaturityOverview, icon: Map },
  FarmInspectionProgress: { content: InspectionProgress, icon: ListChecks },
  FarmRecommendationCard: { content: Recommendation, icon: Sprout },
  FarmAdviceCard: { content: FarmAdvice, icon: Sprout },
  OperationTaskCard: { content: TaskFeedback, icon: ListChecks },
  OperationsQueryCard: { content: TaskQuery, icon: ListChecks },
};

function AgentUiCard({ block, ...actions }: ContentProps) {
  const [expanded, setExpanded] = useState(true);
  const [large, setLarge] = useState(false);
  const { content: Content, icon: Icon } = registry[block.componentType];
  if (block.componentType === "FarmInspectionProgress") {
    return <article className="farmap-block farmap-block--progress" data-block-type={block.componentType} aria-label={block.title}>
      <p><ListChecks size={15} aria-hidden="true" /><strong>{block.title}</strong><span>{block.summary}</span></p>
      <Content block={block} {...actions} />
    </article>;
  }
  const level = actionCards.has(block.componentType) ? "action" : analysisCards.has(block.componentType) ? "analysis" : "info";
  function dispatch(action: AgentUiAction) {
    if (action.type === "expand") setLarge(true);
    if (action.type === "prompt" && action.value) actions.onPrompt(action.value);
    if (action.type === "select-field" && action.value) actions.onSelectField?.(action.value);
    if (action.type === "open-detail" && action.value) {
      const route = detailRoute(action.value);
      if (route) actions.onOpenDetail?.(route);
    }
  }
  const content = <div className="farmap-block__body">
    {block.summary && <p className="farmap-block__summary">{block.summary}</p>}
    <Content block={block} {...actions} />
    {block.componentType !== "WeatherCard" && block.provenance.length > 0 && <details className="farmap-block__sources"><summary>查看来源</summary>
      <ul>{block.provenance.map((source, index) => <li key={index}>{source.label}</li>)}</ul>
    </details>}
    {block.actions.length > 0 && <div className="farmap-block__actions">
      {block.actions.filter((action) => action.type !== "expand" || !large).map((action, index) => (
        <button key={index} type="button" onClick={() => dispatch(action)}>{action.label}{action.type === "open-detail" && <ArrowUpRight size={13} />}</button>
      ))}
    </div>}
  </div>;
  return <article className={`farmap-block farmap-block--${level}`} data-status={block.status} data-block-type={block.componentType}>
    <header className="farmap-block__heading">
      <span className="farmap-block__icon"><Icon size={17} /></span><h3>{block.title}</h3>
      {block.status !== "ready" && <span className="farmap-block__tag">{blockStatus[block.status]}</span>}
      {((block.componentType === "FarmMaturityCard" && !block.data?.runId) || !!block.data?.series?.length || !!block.data?.locations?.length || block.actions.some((action) => action.type === "expand")) && <button type="button" className="farmap-block__icon-button" aria-label="展开分析视图" onClick={() => setLarge(true)}><Maximize2 size={15} /></button>}
      <button type="button" className="farmap-block__icon-button" aria-expanded={expanded} aria-label={expanded ? "收起组件" : "展开组件"} onClick={() => setExpanded(!expanded)}><ChevronDown size={16} /></button>
    </header>
    {expanded && !large && content}
    {large && <Modal open title={block.title} onCancel={() => setLarge(false)} footer={null} width="100%"
      className="farmap-block-modal" style={{ top: 0, maxWidth: "100vw", margin: 0, paddingBottom: 0 }}>
      {content}
    </Modal>}
  </article>;
}

export function AgentUiBlocks({ blocks, ...actions }: BlockActions & { blocks: AgentUiBlock[] }) {
  if (!blocks.length) return null;
  return <div className="farmap-blocks">{blocks.map((block) => <AgentUiCard key={block.blockId} block={block} {...actions} />)}</div>;
}

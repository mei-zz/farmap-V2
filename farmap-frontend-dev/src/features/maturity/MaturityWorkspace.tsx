import { useEffect, useRef, useState } from "react";
import MapContainer from "@/views/dashboard/Map";
import { toFarmStore, type CanonicalFarmMapData } from "@/features/farm/mapData";
import type { FarmStoreState } from "@/store/farm";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import MaturitySchematic from "./MaturitySchematic";
import { includeUnobservedFields, maturityMapColor } from "./geometry";
import { listDiagnosisFields, type DiagnosisField } from "@/features/diagnosis/service";
import { getMaturityRun, getMaturityRuns, stageLabel, type MaturityParcel, type MaturityReport } from "./service";
import "./maturity.css";

type Props = {
  runId: string;
  initialFieldId?: string;
  initialView?: "history" | "ranking";
  onChangeRun?: (id: string) => void;
  onClose: () => void;
  onPrompt: (prompt: string) => void;
};
const ranks: Record<string, number> = { OVER_RIPE: 0, OVERRIPE: 0, RIPE: 1, MIXED: 2, TURNING: 3, UNRIPE: 4, UNKNOWN: 5 };
const legend = ["UNRIPE", "TURNING", "RIPE", "OVER_RIPE", "MIXED", "UNKNOWN"];

function timeLabel(value?: string) {
  return value && Number.isFinite(Date.parse(value))
    ? new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false })
    : "时间未记录";
}

function observedTotals(report: MaturityReport) {
  const observations = (report.parcels ?? []).flatMap((parcel) => parcel.observations ?? []);
  const usable = observations.filter((observation) => observation.status === "SUCCESS" || (!observation.status && observation.stage && observation.stage !== "UNKNOWN"));
  const ripe = usable.filter((observation) => ["RIPE", "OVER_RIPE", "OVERRIPE"].includes(observation.stage ?? ""));
  return { total: observations.length, usable: usable.length, ripe: ripe.length };
}

function ObservedTrend({ reports }: { reports: MaturityReport[] }) {
  const samples = reports.filter((report) => report.mode === "FARM" && (report.kind ?? "MATURITY") === "MATURITY")
    .sort((a, b) => (a.observedAt ?? "").localeCompare(b.observedAt ?? "")).map((report) => ({ report, ...observedTotals(report) }));
  if (samples.length < 2) return <p>至少需要两次全农场观测记录才能比较变化。</p>;
  const max = Math.max(1, ...samples.map(({ total }) => total));
  return <div className="maturity-trend" aria-label="最近一周画面观测趋势">
    <p>横条是每次实际取得的画面数。采样范围变化时，这些数量不能代表成熟比例。</p>
    {samples.map(({ report, ripe, usable, total }) => <div key={report.runId} className="maturity-trend__row">
      <time>{timeLabel(report.observedAt)}</time>
      <div className="maturity-trend__track" aria-hidden="true"><i style={{ width: `${usable / max * 100}%` }} /><b style={{ width: `${ripe / max * 100}%` }} /></div>
      <small>{usable}/{total} 可判断 · {ripe} 成熟或过熟</small>
    </div>)}
  </div>;
}

export default function MaturityWorkspace({ runId, initialFieldId, initialView, onChangeRun, onClose, onPrompt }: Props) {
  const [report, setReport] = useState<MaturityReport>();
  const [history, setHistory] = useState<MaturityReport[]>([]);
  const [mapData, setMapData] = useState<FarmStoreState>();
  const [fields, setFields] = useState<DiagnosisField[]>();
  const [selected, setSelected] = useState(0);
  const [error, setError] = useState("");
  const rankingRef = useRef<HTMLHeadingElement>(null);
  const historyRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    let active = true;
    setReport(undefined);
    setHistory([]);
    setMapData(undefined);
    setFields(undefined);
    setError("");
    setSelected(0);
    getMaturityRun(runId).then((data) => {
      if (!active) return;
      setReport(data);
      const fieldIndex = data.parcels?.findIndex((parcel) => parcel.fieldId === initialFieldId) ?? -1;
      if (fieldIndex >= 0) setSelected(fieldIndex);
      if (!data.farmId) return;
      void listDiagnosisFields(data.farmId).then((result) => { if (active) setFields(result); }).catch(() => undefined);
      void getMaturityRuns(data.farmId).then((reports) => { if (active) setHistory(reports); }).catch(() => undefined);
      void req.get<{ data: CanonicalFarmMapData }>(`/api/farms/${data.farmId}/map-data`, { Authorization: `Bearer ${permanence.token.useToken()}` })
        .then((response) => { if (active && response.data) setMapData(toFarmStore(response.data)); }).catch(() => undefined);
    }).catch((reason: unknown) => { if (active) setError(reason instanceof Error ? reason.message : "报告加载失败"); });
    return () => { active = false; };
  }, [runId, initialFieldId]);

  useEffect(() => {
    if (!report || !initialView) return;
    const target = initialView === "history" ? historyRef.current : rankingRef.current;
    target?.scrollIntoView({ block: "start" });
    target?.focus({ preventScroll: true });
  }, [report, initialView]);

  const parcels = includeUnobservedFields(report?.parcels ?? [], fields ?? []);
  const parcel = parcels[selected];
  const isMaturity = (report?.kind ?? "MATURITY") === "MATURITY";
  const ranked = parcels.map((region, index) => ({ region, index }));
  if (isMaturity) ranked.sort((a, b) => (ranks[a.region.stage ?? "UNKNOWN"] ?? 5) - (ranks[b.region.stage ?? "UNKNOWN"] ?? 5));
  const recent = history.filter((item) => item.observedAt && Date.parse(item.observedAt) <= Date.now() && Date.parse(item.observedAt) >= Date.now() - 7 * 86400000);
  const center = mapData?.center.split(",").map(Number);
  const validCenter = center?.length === 2 && center.every(Number.isFinite);
  const totals = report ? observedTotals(report) : undefined;

  return <section className="maturity-workspace" aria-label="成熟度分析工作区" data-testid="maturity-workspace">
    <header>
      <div><small>{isMaturity ? "成熟度分析" : report?.kind === "PHENOLOGY" ? "物候期分析" : "长势分析"}</small>
        <h2>{report?.farmName || "空间分析"}</h2><span>{report?.observedAt && timeLabel(report.observedAt)}</span></div>
      <button type="button" onClick={onClose} aria-label="关闭成熟度分析">×</button>
    </header>
    {error ? <p role="alert">{error}</p> : !report ? <p role="status">正在加载分析报告…</p> : <>
      <p>{report.summary}</p>
      <div className="maturity-overview" aria-label="观测覆盖">
        <span><b>{parcels.length}</b> 区域或图像</span><span><b>{totals?.usable}</b> 可判断画面</span>
        {isMaturity && <span><b>{totals?.ripe}</b> 成熟或过熟画面</span>}
      </div>
      <p className="maturity-note">观测结果仅来自已取得的画面。{report.topology === "DEMO" && "演示分区使用登记坐标估算，不能作为测绘边界。"}</p>
      <h3>区域空间分布</h3>
      {mapData && validCenter ? <div className="maturity-map">
        <MapContainer farmId={report.farmId} center={center as [number, number]} zoom={mapData.zoom} crops={[]}
          farmLocations={mapData.locations} infoKey="yield" modeKey="monitor" readOnly
          slider={{ value: { left: 0, right: 100 }, scale: { min: 0, max: 100 }, decimal: false }}
          maturityParcels={parcels} demoMaturityGeometry={report.topology === "DEMO" && (report.mode === "FARM" || Boolean(fields?.length))}
          selectedMaturity={selected} onMaturitySelect={setSelected} />
      </div> : <MaturitySchematic parcels={parcels} shapes={[]} outline={[]} selected={selected} onSelect={setSelected} />}
      {isMaturity && <div className="maturity-legend" aria-label="成熟度图例">
        {legend.map((stage) => <span key={stage}><i style={{ background: maturityMapColor(stage) }} />{stageLabel(stage)}</span>)}
      </div>}
      <div className="maturity-grid">
        <div><h3 ref={rankingRef} tabIndex={-1}>{isMaturity ? "区域观测 · 按阶段排序" : "区域观测"}</h3>
          {ranked.length ? ranked.map(({ region, index }) => <button type="button" className={`maturity-parcel ${selected === index ? "selected" : ""}`}
            key={`${region.fieldId ?? region.name}-${index}`} onClick={() => setSelected(index)}>
            <strong>{region.name || "区域"}</strong><span>{stageLabel(region.stage)}</span>
          </button>) : <p>暂无区域观测</p>}
        </div>
        <div><h3>{parcel?.name || "观测详情"}</h3>{parcel ? <ParcelDetails parcel={parcel} demo={report.topology === "DEMO"} /> : <p>选择一个区域查看依据</p>}</div>
      </div>
      <h3 ref={historyRef} tabIndex={-1}>历史记录</h3>
      <p>近 7 天有 {recent.length} 次已保存的视觉观测。</p>
      {isMaturity && <ObservedTrend reports={recent} />}
      {history.length ? <label className="maturity-history-select">查看历史快照 <select value={runId} disabled={!onChangeRun} onChange={(event) => onChangeRun?.(event.target.value)}>
        {!history.some((item) => item.runId === runId) && <option value={runId}>当前报告</option>}
        {history.map((item) => <option key={item.runId} value={item.runId}>{timeLabel(item.observedAt)} · {item.mode === "FARM" ? "全农场" : "局部观测"}</option>)}
      </select></label> : <p>暂无可用历史记录</p>}
      {isMaturity && <><h3>采收建议</h3>
        {report.recommendations?.length ? report.recommendations.map((item, i) => <p key={i}>{typeof item === "string" ? item : [item.title, item.detail].filter(Boolean).join("：")}</p>) : <p>当前证据不足以给出采收建议。</p>}
        <p className="maturity-note">建议需人工复核，创建执行任务仍需确认。</p>
        {report.recommendations?.length ? <button type="button" onClick={() => onPrompt("根据当前成熟度报告，帮我制定采收复核计划草稿。")}>在对话中安排复核</button> : null}
      </>}
      <button type="button" onClick={() => onPrompt(isMaturity ? `请解释本次报告里${parcel?.name ? `「${parcel.name}」` : "各区域"}的成熟度依据和不确定性。` : "请解释这份观测报告的依据和不确定性。")}>继续向 Agent 提问</button>
    </>}
  </section>;
}

function ParcelDetails({ parcel, demo }: { parcel: MaturityParcel; demo: boolean }) {
  return <>
    <strong>{stageLabel(parcel.stage)}</strong>
    {parcel.reason && <p>{parcel.reason}</p>}
    {demo && <p className="maturity-note">当前显示的区域边界为演示分区。</p>}
    {parcel.observations?.length ? parcel.observations.map((observation, index) => <article className="maturity-evidence" key={`${observation.cameraId}-${index}`}>
      <b>{observation.cameraName || "图像观测"} · {observation.status === "OFFLINE" ? "离线" : observation.status === "UNBOUND" ? "未绑定" : observation.label ?? stageLabel(observation.stage)}</b>
      <p>{observation.reason || "暂无判断依据"}</p>
      {observation.quality && <small>画面质量 · {observation.quality}　</small>}
      {observation.capturedAt && <small>{timeLabel(observation.capturedAt)}</small>}
      {observation.imageUrl && /^https?:\/\//i.test(observation.imageUrl) && <img src={observation.imageUrl} alt={`${observation.cameraName || "摄像头"}观测画面`} loading="lazy" referrerPolicy="no-referrer" />}
    </article>) : <p>没有可用画面</p>}
  </>;
}

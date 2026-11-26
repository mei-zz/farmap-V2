import { listDiagnosisFields, type DiagnosisField } from "@/features/diagnosis/service";
import { getMaturityRuns, stageLabel, type MaturityReport } from "@/features/maturity/service";
import { useEffect, useRef, useState } from "react";
import { Camera, Sparkles, MapPin, Upload, Sprout } from "lucide-react";
import { useNavigate, useSearchParams } from "react-router";
import {
  loadAgriculturalContext,
  type AgriculturalContext,
} from "@/features/agricultural/context";
import {
  captureFarmCamera,
  farmMaturityPrompt,
  selectedCameraContext,
  uploadFarmCameraFrame,
  type FarmCamera,
} from "@/features/farm/cameras";
import {
  getMonitorAccessToken,
  getMonitorPreview,
} from "@/views/monitor/monitorService";
import VideoPlayer from "@/views/monitor/VideoPlayer";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import "./spatial-monitor.css";
type Tab = "overview" | "devices" | "inspection";
function TemperatureTrend({
  series,
}: {
  series: Array<{ date?: string; temperature?: number }>;
}) {
  const points = series
    .filter(
      (p): p is { date: string; temperature: number } =>
        !!p.date &&
        !Number.isNaN(Date.parse(p.date)) &&
        typeof p.temperature === "number" &&
        Number.isFinite(p.temperature),
    )
    .sort((a, b) => a.date.localeCompare(b.date))
    .slice(-14);
  if (!points.length)
    return <div className="fm-empty fm-empty--small">暂无带日期的温度观测</div>;
  const min = Math.min(...points.map((p) => p.temperature)),
    max = Math.max(...points.map((p) => p.temperature)),
    range = max - min || 1;
  const firstTime = Date.parse(points[0].date),
    span = Date.parse(points[points.length - 1].date) - firstTime;
  const coords = points.map((p) => ({
    ...p,
    x: span ? 15 + ((Date.parse(p.date) - firstTime) * 270) / span : 150,
    y: 85 - ((p.temperature - min) / range) * 65,
  }));
  return (
    <div className="fm-trend-chart">
      <svg
        viewBox="0 0 300 105"
        role="img"
        aria-label={`历史温度，${points[0].date}至${points[points.length - 1].date}，${min}至${max}摄氏度`}
      >
        <line x1="15" y1="85" x2="285" y2="85" stroke="#e5eae6" />
        <polyline
          fill="none"
          stroke="#176b4d"
          strokeWidth="2"
          points={coords.map((p) => `${p.x},${p.y}`).join(" ")}
        />
        {coords.map((p, i) => (
          <circle key={i} cx={p.x} cy={p.y} r="3" fill="#176b4d">
            <title>
              {p.date}：{p.temperature}°C
            </title>
          </circle>
        ))}
      </svg>
      <div className="fm-trend-axis">
        <span>{points[0].date}</span>
        <span>
          {min}–{max} °C
        </span>
        <span>{points[points.length - 1].date}</span>
      </div>
    </div>
  );
}
export default function MonitoringWorkspace() {
  const farm = useAIContextStore((s) => s.context.currentFarm),
    setContext = useAIContextStore((s) => s.setContext),
    images = useAIContextStore((s) => s.context.selectedImages);
  const fieldId = useAIContextStore((s) => s.context.currentField?.id),
    referenceDate = useAIContextStore((s) => s.context.referenceDate)?.slice(
      0,
      10,
    );
  const open = useCopilotStore((s) => s.open),
    setDraft = useCopilotStore((s) => s.setDraft),
    token = permanence.token.useToken(),
    navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab: Tab =
    params.get("view") === "devices"
      ? "devices"
      : params.get("view") === "images"
        ? "inspection"
        : "overview";
  const setTab = (next: Tab) => {
    const updated = new URLSearchParams(params);
    if (next === "overview") updated.delete("view");
    else updated.set("view", next === "inspection" ? "images" : "devices");
    setParams(updated, { replace: true });
  };
  const [bindingFields, setBindingFields] = useState<DiagnosisField[]>([]);
  const [bindingField, setBindingField] = useState("");
  const [newFieldName, setNewFieldName] = useState("");
  const [newFieldCrop, setNewFieldCrop] = useState("");
  const [areaName, setAreaName] = useState("");
  const [bindingMessage, setBindingMessage] = useState("");
  const [maturityRuns, setMaturityRuns] = useState<MaturityReport[]>([]);
  useEffect(() => { let active = true; setBindingFields([]); setMaturityRuns([]); if (farm.id > 0) { listDiagnosisFields(farm.id).then((items) => { if (active) setBindingFields(items.filter((item) => !item.sourceMode?.startsWith("SYNTHETIC"))); }).catch(() => {}); getMaturityRuns(farm.id).then((runs) => { if (active && Array.isArray(runs)) setMaturityRuns(runs); }).catch(() => {}); } return () => { active = false; }; }, [farm.id]);
  const selectionVersion = useRef(0);
  const [cameras, setCameras] = useState<FarmCamera[]>([]),
    [agriculture, setAgriculture] = useState<AgriculturalContext>(),
    [selected, setSelected] = useState<FarmCamera>(),
    [video, setVideo] = useState(""),
    [error, setError] = useState(""),
    [cameraError, setCameraError] = useState(""),
    [weatherError, setWeatherError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    setContext({ currentPage: "/monitoring", pageKey: "/monitoring" });
  }, [setContext]);
  useEffect(() => {
    let active = true;
    selectionVersion.current++;
    setCameras([]);
    setAgriculture(undefined);
    setSelected(undefined);
    setVideo("");
    setError("");
    setCameraError("");
    setWeatherError("");
    if (farm.id <= 0) return;
    Promise.allSettled([
      req.get<{ data: FarmCamera[] }>(`/api/farms/${farm.id}/cameras`, {
        Authorization: `Bearer ${token}`,
      }),
      loadAgriculturalContext(farm.id, token),
    ]).then(([c, w]) => {
      if (!active) return;
      if (c.status === "fulfilled" && Array.isArray(c.value.data))
        setCameras(c.value.data);
      else setCameraError("设备列表暂不可用");
      if (w.status === "fulfilled") {
        setAgriculture(w.value);
        const x = w.value.currentWeather;
        setContext({
          currentWeather: {
            summary: x.condition ?? x.reason ?? "天气数据暂不可用",
            temperature: x.temperature ? `${x.temperature}°C` : "—",
            precipitation:
              x.rainfallForecast24h !== undefined
                ? `未来24小时 ${x.rainfallForecast24h} mm`
                : "暂无降雨预报",
            updatedAt: x.observedAt ?? x.retrievedAt ?? "",
            rainfall14d: x.rainfall14d,
            rainfallForecast24h: x.rainfallForecast24h,
            rainfallProbability24h: x.rainfallProbability24h,
            rainfallForecast: x.rainfallForecast,
            rainfallForecastDays: x.rainfallForecastDays,
            forecast: x.forecast,
          },
        });
      } else setWeatherError("环境数据暂不可用");
    });
    return () => {
      active = false;
    };
  }, [farm.id, token, setContext]);
  const selectCamera = async (c: FarmCamera) => {
    const version = ++selectionVersion.current;
    setSelected(c);
    setVideo("");
    setError("");
    setTab("inspection");
    setContext({
      selectedDevice: selectedCameraContext(c),
      currentField: c.fieldId
        ? {
            id: c.fieldId,
            name: c.fieldName || c.fieldId,
            area: c.fieldArea ?? 0,
            crop: c.crop || "",
          }
        : undefined,
      currentCrop: c.crop
        ? { name: c.crop, growthStage: c.growthStage }
        : undefined,
      currentPhenology: c.growthStage
        ? { stage: c.growthStage, progress: 0 }
        : undefined,
      currentLocation: {
        label: c.fieldName || c.name,
        latitude: c.latitude,
        longitude: c.longitude,
      },
      selectedImages: [],
      selectedDiagnosis: undefined,
      agentRunId: undefined,
      taskId: undefined,
    });
    useCopilotStore.getState().setContext(undefined);
    try {
      const access = await getMonitorAccessToken(),
        preview = await getMonitorPreview(access.accessToken, c.serial);
      if (version !== selectionVersion.current) return;
      setVideo(preview.previewUrl);
      setContext({ selectedDevice: selectedCameraContext(c, true) });
    } catch (e) {
      if (version === selectionVersion.current)
        setError(e instanceof Error ? e.message : "预览暂不可用");
    }
  };
  const frameInput = useRef<HTMLInputElement>(null);
  const capture = async (file?: File) => {
    if (!selected) return;
    const version = selectionVersion.current;
    setBusy(true);
    setError("");
    try {
      const image = file ? await uploadFarmCameraFrame(selected.cameraId, file) : await captureFarmCamera(selected.cameraId);
      if (version !== selectionVersion.current) return;
      setContext({
        referenceDate: image.capturedAt.slice(0, 10),
        selectedImages: [
          {
            id: `${image.cameraId}-${image.capturedAt}`,
            name: `${image.cameraName} · ${image.capturedAt}`,
            url: image.imageUrl,
          },
        ],
      });
    } catch (e) {
      if (version === selectionVersion.current) setError(e instanceof Error ? e.message : file ? "截图上传失败" : "抓拍失败");
    } finally {
      setBusy(false);
    }
  };
  const scopedCameras = fieldId
    ? cameras.filter((camera) => camera.fieldId === fieldId)
    : cameras;
  const cameraList = (
    <div className="fm-camera-list">
      {scopedCameras.map((c) => (
        <button key={c.cameraId} onClick={() => void selectCamera(c)}>
          <Camera size={17} />
          <span>
            <strong>{c.name}</strong>
            <small>
              {c.fieldName || "未绑定地块"} · {c.serial}
            </small>
          </span>
          <b>查看影像 →</b>
        </button>
      ))}
    </div>
  );
  const weather = agriculture?.currentWeather,
    history = agriculture?.historicalTemperature;
  const trendSeries = (history?.series ?? []).filter(
    (point) =>
      !!point.date &&
      !Number.isNaN(Date.parse(point.date)) &&
      typeof point.temperature === "number" &&
      Number.isFinite(point.temperature) &&
      (!referenceDate || point.date.slice(0, 10) <= referenceDate),
  );
  return (
    <main className="fm-workspace">
      <header className="fm-workspace__header">
        <div>
          <p className="fm-workspace__eyebrow">PRODUCTION MONITORING</p>
          <h1>生产监测</h1>
          <p>设备、环境和现场影像集中查看</p>
        </div>
        {farm.id > 0 && <button className="fm-primary" type="button" onClick={() => { setDraft(farmMaturityPrompt); open(); }}>
          <Sprout size={16} /> 评估全农场成熟度
        </button>}
      </header>
      <div className="fm-workspace-tabs" role="tablist">
        {(
          [
            ["overview", "监测总览"],
            ["devices", "设备与传感器"],
            ["inspection", "影像巡检"],
          ] as [Tab, string][]
        ).map(([key, label]) => (
          <button
            role="tab"
            aria-selected={tab === key}
            key={key}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === "overview" && (
        <>
          <div className="fm-monitor-status" aria-label="监测状态">
            <span>
              摄像头{" "}
              <strong>
                {cameraError ? "不可用" : `${scopedCameras.length} 台`}
              </strong>
            </span>
            <span>
              天气{" "}
              <strong>
                {weather?.status === "READY"
                  ? `${weather.temperature ?? "—"}°C ${weather.condition || ""}`
                  : weatherError || "暂无数据"}
              </strong>
            </span>
            <span>
              传感器 <strong>暂无数据</strong>
            </span>
            {(weather?.observedAt || weather?.retrievedAt) && (
              <span>
                天气更新{" "}
                <strong>{weather.observedAt || weather.retrievedAt}</strong>
              </span>
            )}
          </div>
          <div className="fm-monitor-grid">
            <section
              className={
                trendSeries.length ? "fm-surface" : "fm-monitor-compact"
              }
            >
              <div className="fm-section-heading">
                <div>
                  <h2>环境趋势</h2>
                  <p>农场历史温度观测</p>
                </div>
              </div>
              {trendSeries.length ? (
                <TemperatureTrend series={trendSeries} />
              ) : (
                <div className="fm-empty fm-empty--small">
                  {weatherError || "暂无历史温度观测"}
                </div>
              )}
            </section>
            <section
              className={
                scopedCameras.length ? "fm-surface" : "fm-monitor-compact"
              }
            >
              <div className="fm-section-heading">
                <div>
                  <h2>设备与影像</h2>
                  <p>选择设备进入实时查看和巡检</p>
                </div>
                <button
                  className="fm-text-button"
                  onClick={() => setTab("devices")}
                >
                  查看设备 →
                </button>
              </div>
              {scopedCameras.length ? (
                cameraList
              ) : (
                <div className="fm-empty fm-empty--small">
                  {cameraError ||
                    (fieldId
                      ? "当前地块暂无绑定摄像头。"
                      : "当前农场暂无绑定摄像头，请联系管理员配置设备。")}
                </div>
              )}
            </section>
          </div>
        </>
      )}
      {tab === "devices" && (
        <section className="fm-surface">
          <div className="fm-section-heading">
            <div>
              <h2>设备与传感器</h2>
              <p>已绑定当前农场的设备</p>
            </div>
          </div>
          {scopedCameras.length ? (
            cameraList
          ) : (
            <div className="fm-empty fm-empty--small">
              {cameraError || "当前农场暂无绑定摄像头。"}
            </div>
          )}
          <div className="fm-inline-note">传感器与土壤监测：暂无可用数据</div>
        </section>
      )}
      {tab === "inspection" && (
        <section className="fm-surface">
          <div className="fm-section-heading">
            <div>
              <h2>影像巡检</h2>
              <p>
                {selected
                  ? `${selected.name} · ${selected.fieldName || "未绑定地块"}`
                  : "选择摄像头查看实时影像"}
              </p>
            </div>
            <button
              className="fm-text-button"
              onClick={() => setTab("devices")}
            >
              选择设备 →
            </button>
          </div>
          {error && <p className="fm-workspace__notice">{error}</p>}
          {video && selected && (
            <div className="fm-video">
              <VideoPlayer videoUrl={video} />
            </div>
          )}
          {!selected && (
            <div className="fm-empty fm-empty--small">
              {cameraError ||
                (scopedCameras.length
                  ? "请先选择摄像头"
                  : "当前农场暂无可用视频源")}
            </div>
          )}
          {selected && (
            <>
              <div className="fm-actions">
                <button onClick={() => void capture()} disabled={busy}>
                  {busy ? "正在处理…" : "抓拍现场"}
                </button>
                <input ref={frameInput} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={(event) => {
                  const file = event.currentTarget.files?.[0];
                  event.currentTarget.value = "";
                  if (file) void capture(file);
                }} />
                <button type="button" disabled={busy} onClick={() => frameInput.current?.click()}><Upload size={16} /> 上传该摄像头的截图</button>
                <button
                  onClick={() =>
                    navigate(
                      `/fields?mode=monitor&cameraId=${encodeURIComponent(selected.cameraId)}`,
                    )
                  }
                >
                  <MapPin size={16} />
                  地图定位
                </button>
                <button
                  className="fm-primary"
                  onClick={() => {
                    setDraft(
                      `请结合当前${selected.name}、地块与现场图片，分析可见异常及可能原因，说明证据和下一步建议。`,
                    );
                    open();
                  }}
                >
                  <Sparkles size={16} />
                  交给 Agent 分析
                </button>
              </div>
              {!bindingFields.some((field) => field.fieldId === selected.fieldId) && <div><p>展示用地块关联不能作为成熟度依据，请确认真实归属。</p><label>选择摄像头所属地块 <select aria-label="选择摄像头所属地块" value={bindingField} onChange={(e) => setBindingField(e.target.value)}><option value="">请选择真实地块</option>{bindingFields.map((field) => <option key={field.fieldId} value={field.fieldId}>{field.name}</option>)}</select></label>{!bindingFields.length && <div><p>暂无可绑定的真实地块。可填写现场真实信息，新建后再确认绑定。</p><label>真实地块名称 <input value={newFieldName} onChange={(e) => setNewFieldName(e.target.value)} /></label><label>作物（可选） <input value={newFieldCrop} onChange={(e) => setNewFieldCrop(e.target.value)} /></label><button type="button" disabled={!newFieldName.trim()} onClick={async () => { try { const response = await req.post<{name:string;crop?:string},{data?:DiagnosisField;msg?:string}>(`/api/farms/${farm.id}/fields`, {name:newFieldName.trim(), ...(newFieldCrop.trim() ? {crop:newFieldCrop.trim()} : {})}, {Authorization:`Bearer ${token}`}); if (!response.data?.fieldId) throw new Error(response.msg || "创建地块失败"); setBindingFields((items) => [...items, response.data!]); setBindingField(response.data.fieldId); setBindingMessage("真实地块已创建，请确认摄像头绑定。"); } catch (e) { setBindingMessage(e instanceof Error ? e.message : "创建地块失败"); } }}>新建真实地块</button></div>}<label>监测区名称（可选） <input value={areaName} onChange={(e) => setAreaName(e.target.value)} /></label><button type="button" disabled={!bindingField} onClick={async () => { try { await req.put(`/api/farms/${farm.id}/camera-bindings`, {cameraId: selected.cameraId, fieldId: bindingField, ...(areaName.trim() ? {areaName:areaName.trim()} : {})}, {Authorization: `Bearer ${token}`}); const field = bindingFields.find((item) => item.fieldId === bindingField); const updated = {...selected, fieldId: bindingField, fieldName: field?.name, bindingStatus: "BOUND"}; setSelected(updated); setCameras((items) => items.map((item) => item.cameraId === updated.cameraId ? updated : item)); setBindingMessage("已保存摄像头归属"); } catch (e) { setBindingMessage(e instanceof Error ? e.message : "绑定失败"); } }}>确认绑定</button><p role="status">{bindingMessage}</p></div>}
              <button type="button" onClick={() => { setContext({ selectedDevice: selectedCameraContext(selected) }); setDraft(`请判断摄像头 ${selected.name}（${selected.cameraId}）当前画面中可见果实的成熟度，说明依据和不确定性。`); open(); }}>让 Agent 判断此摄像头成熟度</button>
              <div><h3>该摄像头的成熟度记录</h3>{maturityRuns.flatMap((run) => (run.parcels || []).flatMap((parcel) => (parcel.observations || []).filter((observation) => observation.cameraId === selected.cameraId).map((observation, i) => <div key={`${run.runId}-${i}`}><b>{stageLabel(observation.stage)}</b> · {observation.capturedAt || run.observedAt}<p>{observation.reason}</p>{observation.imageUrl && <img src={observation.imageUrl} alt={`${selected.name}历史观测画面`} style={{maxWidth: 180}} />}</div>)))}</div>
              {images.length > 0 && (
                <div className="fm-capture">
                  <img src={images[0].url} alt={images[0].name} />
                  <span>{images[0].name}</span>
                </div>
              )}
            </>
          )}
        </section>
      )}
    </main>
  );
}



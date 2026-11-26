import { getLatestMaturity, getMaturityRuns, stageLabel, type MaturityReport } from "@/features/maturity/service";
import { includeUnobservedFields } from "@/features/maturity/geometry";
import { useCallback, useEffect, useState } from "react";
import { Map as MapIcon, Layers, Sparkles, X } from "lucide-react";
import { useSearchParams } from "react-router";
import MapContainer from "@/views/dashboard/Map";
import {
  listDiagnosisFields,
  type DiagnosisField,
} from "@/features/diagnosis/service";
import {
  toFarmStore,
  type CanonicalFarmMapData,
} from "@/features/farm/mapData";
import type { FarmCamera } from "@/features/farm/cameras";
import {
  loadAgriculturalContext,
  type AgriculturalDiagnosisSummary,
} from "@/features/agricultural/context";
import type { OperationsTaskRaw } from "@/types/operations";
import { useFarmStore, type FarmStoreState } from "@/store/farm";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import "./spatial-monitor.css";

export default function SpatialWorkspace() {
  const farm = useAIContextStore((s) => s.context.currentFarm);
  const currentField = useAIContextStore((s) => s.context.currentField);
  const setContext = useAIContextStore((s) => s.setContext);
  const open = useCopilotStore((s) => s.open);
  const setDraft = useCopilotStore((s) => s.setDraft);
  const setFarm = useFarmStore((s) => s.setFarm);
  const [params] = useSearchParams();
  const requestedCameraId = params.get("cameraId");
  const token = permanence.token.useToken();
  const [data, setData] = useState<FarmStoreState>();
  const [fields, setFields] = useState<DiagnosisField[]>([]);
  const [cameras, setCameras] = useState<FarmCamera[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [layer, setLayer] = useState<"monitor" | "crop" | "maturity">("monitor");
  const [maturity, setMaturity] = useState<MaturityReport>();
  const [maturityHistory, setMaturityHistory] = useState<MaturityReport[]>([]);
  const [maturityIndex, setMaturityIndex] = useState(0);
  useEffect(() => { let active = true; setMaturity(undefined); if (farm.id > 0) { getLatestMaturity(farm.id).then((report) => { if (active) setMaturity(report); }).catch(() => {}); getMaturityRuns(farm.id).then((runs) => { if (active && Array.isArray(runs)) setMaturityHistory(runs); }).catch(() => {}); } return () => { active = false; }; }, [farm.id]);
  const [info, setInfo] = useState<
    "yield" | "phenology" | "growth" | "nitrogen"
  >("yield");
  const [drawer, setDrawer] = useState(false);
  const [diagnoses, setDiagnoses] = useState<AgriculturalDiagnosisSummary[]>();
  const [tasks, setTasks] = useState<OperationsTaskRaw[]>();
  const [recordsLoading, setRecordsLoading] = useState(false);
  const [recordErrors, setRecordErrors] = useState<string[]>([]);
  useEffect(() => {
    setContext({ currentPage: "/fields", pageKey: "/fields" });
  }, [setContext]);
  useEffect(() => {
    let active = true;
    setData(undefined);
    setFields([]);
    setCameras([]);
    setErrors([]);
    setDrawer(false);
    if (farm.id <= 0) return;
    setLoading(true);
    const headers = { Authorization: `Bearer ${token}` };
    Promise.allSettled([
      req.get<{ data: CanonicalFarmMapData }>(
        `/api/farms/${farm.id}/map-data`,
        headers,
      ),
      listDiagnosisFields(farm.id),
      req.get<{ data: FarmCamera[] }>(`/api/farms/${farm.id}/cameras`, headers),
    ]).then(([map, fieldList, cameraList]) => {
      if (!active) return;
      const problems: string[] = [];
      if (map.status === "fulfilled" && map.value.data) {
        const result = toFarmStore(map.value.data);
        setData(result);
        setFarm(result);
      } else problems.push("地图数据暂不可用");
      if (fieldList.status === "fulfilled") setFields(fieldList.value);
      else problems.push("地块列表暂不可用");
      if (
        cameraList.status === "fulfilled" &&
        Array.isArray(cameraList.value.data)
      )
        setCameras(cameraList.value.data);
      else problems.push("摄像头数据暂不可用");
      setErrors(problems);
      setLoading(false);
    });
    return () => {
      active = false;
    };
  }, [farm.id, token, setFarm]);
  const selectField = useCallback(
    (id: string) => {
      const field = fields.find((item) => item.fieldId === id);
      if (!field) return;
      setContext({
        currentField: {
          id: field.fieldId,
          name: field.name,
          area: field.area ?? 0,
          crop: field.crop ?? "",
        },
        currentCrop: field.crop
          ? { name: field.crop, growthStage: field.growthStage }
          : undefined,
        currentPhenology: field.growthStage
          ? { stage: field.growthStage, progress: 0 }
          : undefined,
        currentLocation:
          Number.isFinite(field.latitude) && Number.isFinite(field.longitude)
            ? {
                label: field.name,
                latitude: field.latitude!,
                longitude: field.longitude!,
              }
            : undefined,
        selectedDevice: undefined,
        selectedImages: [],
        selectedDiagnosis: undefined,
        agentRunId: undefined,
        taskId: undefined,
      });
      useCopilotStore.getState().setContext(undefined);
      setDrawer(true);
    },
    [fields, setContext],
  );
  useEffect(() => {
    const camera = cameras.find((item) => item.cameraId === requestedCameraId);
    if (camera?.fieldId) selectField(camera.fieldId);
  }, [cameras, requestedCameraId, selectField]);
  const selected = fields.find((field) => field.fieldId === currentField?.id);
  useEffect(() => {
    let active = true;
    setDiagnoses(undefined);
    setTasks(undefined);
    setRecordErrors([]);
    if (!drawer || !selected || farm.id <= 0) {
      setRecordsLoading(false);
      return;
    }
    setRecordsLoading(true);
    Promise.allSettled([
      loadAgriculturalContext(farm.id, token, {
        fieldId: selected.fieldId,
        diagnosisLimit: 3,
      }),
      req.get<{ data?: { items?: OperationsTaskRaw[] } }>(
        "/api/operations/tasks",
        { Authorization: `Bearer ${token}` },
      ),
    ]).then(([diagnosisResult, taskResult]) => {
      if (!active) return;
      const problems: string[] = [];
      if (
        diagnosisResult.status === "fulfilled" &&
        diagnosisResult.value.recentDiagnoses
      ) {
        const list = diagnosisResult.value.recentDiagnoses;
        if (
          list.status === "UNAVAILABLE" ||
          list.status === "CONFIGURED_UNAVAILABLE" ||
          list.status === "NOT_CONFIGURED" ||
          list.status === "NOT_IMPLEMENTED"
        )
          problems.push("诊断记录暂不可用");
        else
          setDiagnoses(
            list.items
              .filter((item) => item.fieldId === selected.fieldId)
              .slice(0, 3),
          );
      } else problems.push("诊断记录暂不可用");
      if (
        taskResult.status === "fulfilled" &&
        Array.isArray(taskResult.value.data?.items)
      )
        setTasks(
          taskResult.value.data.items
            .filter((item) => item.fieldId === selected.fieldId)
            .sort((a, b) =>
              String(b.updatedAt || b.createdAt || "").localeCompare(
                String(a.updatedAt || a.createdAt || ""),
              ),
            )
            .slice(0, 3),
        );
      else problems.push("农事记录暂不可用");
      setRecordErrors(problems);
      setRecordsLoading(false);
    });
    return () => {
      active = false;
    };
  }, [drawer, selected, farm.id, token]);
  const center = data?.center.split(",").map(Number);
  const validCenter =
    center?.length === 2 &&
    center.every(Number.isFinite) &&
    center.some((v) => v !== 0);
  const mapAvailable = Boolean(
    data && validCenter && (import.meta.env.VITE_GAODE_API_KEY || layer === "maturity"),
  );
  const maturityParcels = includeUnobservedFields(maturity?.parcels ?? [], fields);
  const selectedMaturityParcel = maturityParcels[maturityIndex];
  const otherErrors = errors.filter((error) => error !== "地图数据暂不可用");
  return (
    <main className="fm-workspace">
      <header className="fm-workspace__header">
        <div>
          <p className="fm-workspace__eyebrow">FARM SPACE</p>
          <h1>地块空间</h1>
          <p>在地图上查看地块、观测点与设备分布</p>
        </div>
      </header>
      <section className="fm-surface fm-map-section">
        <div className="fm-section-heading">
          <div>
            <h2>
              <MapIcon size={18} /> 农场空间地图
            </h2>
            <p>选择地图上的地块标记或从列表中打开详情</p>
          </div>
          <div className="fm-map-tools">
            <Layers size={16} />
            <select
              aria-label="地图图层"
              value={layer}
              onChange={(e) => setLayer(e.target.value as "monitor" | "crop" | "maturity")}
            >
              <option value="monitor">边界与设备</option>
              <option value="crop">作物观测点</option>
              <option value="maturity">成熟度观测</option>
            </select>
            {layer === "crop" && (
              <select
                aria-label="观测指标"
                value={info}
                onChange={(e) => setInfo(e.target.value as typeof info)}
              >
                <option value="yield">产量</option>
                <option value="phenology">物候</option>
                <option value="growth">长势</option>
                <option value="nitrogen">氮素</option>
              </select>
            )}
          </div>
        </div>
        {otherErrors.length > 0 && (
          <p className="fm-workspace__notice">{otherErrors.join("；")}</p>
        )}
        <div
          className={`fm-spatial-body${mapAvailable ? "" : " fm-spatial-body--unavailable"}`}
        >
          <div
            className={`fm-map-canvas${mapAvailable ? "" : " fm-map-canvas--unavailable"}`}
          >
            {loading ? (
              <div className="fm-empty">正在加载地图…</div>
            ) : mapAvailable && data && center ? (
              <MapContainer
                farmId={farm.id}
                center={center as [number, number]}
                zoom={data.zoom}
                crops={data.crops}
                farmLocations={data.locations}
                cameras={cameras}
                fields={fields}
                onFieldSelect={selectField}
                infoKey={info}
                modeKey={layer === "maturity" ? "monitor" : layer}
                maturityParcels={layer === "maturity" ? maturityParcels : undefined}
                demoMaturityGeometry={layer === "maturity" && maturity?.topology === "DEMO" && (maturity.mode === "FARM" || fields.length > 0)}
                selectedMaturity={maturityIndex}
                onMaturitySelect={setMaturityIndex}
                slider={{
                  value: { left: 0, right: 100 },
                  scale: { min: 0, max: 100 },
                  decimal: false,
                }}
              />
            ) : (
              <div className="fm-empty">
                {farm.id <= 0
                  ? "请先选择农场"
                  : errors.includes("地图数据暂不可用")
                    ? "地图数据暂不可用。"
                    : !validCenter
                      ? "地图坐标不可用，请检查农场地图配置。"
                      : "地图服务未配置，暂无法显示地图。"}
              </div>
            )}
          </div>
          <aside className="fm-field-list">
            {layer === "maturity" && <>
              <h3>成熟度分布</h3>
              {maturityHistory.length > 0 && <select aria-label="选择成熟度历史报告" value={maturity?.runId || ""} onChange={(event) => {
                const report = maturityHistory.find((item) => item.runId === event.target.value);
                if (report) {
                  setMaturity(report);
                  setMaturityIndex(0);
                }
              }}>
                {maturityHistory.map((item) => <option key={item.runId} value={item.runId}>{item.observedAt || "时间未知"} · {item.mode || "观测"}</option>)}
              </select>}
              <p>{maturity?.observedAt}</p>
              <p>{maturity?.topology === "DEMO" ? "演示分区仅供展示，成熟度来自实际画面。" : "具有边界的区域按面展示，其余区域显示位置点。"}</p>
              {maturity && maturityParcels.map((parcel, index) => <button type="button" key={`${parcel.fieldId || parcel.name}-${index}`} className={maturityIndex === index ? "is-selected" : ""} onClick={() => setMaturityIndex(index)}>
                <strong>{parcel.name || "未归属画面"}</strong>
                <span>{stageLabel(parcel.stage)} · {parcel.reason}</span>
              </button>)}
              {maturity && selectedMaturityParcel?.observations?.some((observation) => observation.imageUrl) &&
                <button type="button" onClick={() => useCopilotStore.getState().openAnalysis({ runId: maturity.runId, farmId: farm.id, fieldId: selectedMaturityParcel.fieldId })}>打开所选区域的影像证据</button>}
              {!maturity && <p>暂无成熟度报告</p>}
            </>}
            <h3>
              地块 <small>{fields.length}</small>
            </h3>
            {fields.length ? (
              fields.map((field) => (
                <button
                  key={field.fieldId}
                  className={
                    currentField?.id === field.fieldId ? "is-selected" : ""
                  }
                  onClick={() => selectField(field.fieldId)}
                >
                  <strong>{field.name}</strong>
                  <span>
                    {field.crop || "作物未标注"} ·{" "}
                    {field.growthStage || "物候未标注"}
                  </span>
                </button>
              ))
            ) : (
              <p>{loading ? "正在读取地块…" : "暂无可用地块数据"}</p>
            )}
          </aside>
        </div>
        {drawer && selected && (
          <aside
            className={`fm-field-drawer${mapAvailable ? "" : " fm-field-drawer--inline"}`}
          >
            <div className="fm-section-heading">
              <div>
                <span className="fm-workspace__eyebrow">地块详情</span>
                <h2>{selected.name}</h2>
              </div>
              <button
                className="fm-icon-button"
                aria-label="关闭地块详情"
                onClick={() => setDrawer(false)}
              >
                <X size={18} />
              </button>
            </div>
            <div className="fm-detail-grid">
              <div>
                <span>作物</span>
                <strong>{selected.crop || "未标注"}</strong>
              </div>
              <div>
                <span>物候期</span>
                <strong>{selected.growthStage || "未标注"}</strong>
              </div>
              <div>
                <span>面积</span>
                <strong>
                  {selected.area != null ? selected.area : "未记录"}
                </strong>
              </div>
              <div>
                <span>关联摄像头</span>
                <strong>
                  {
                    cameras.filter(
                      (camera) => camera.fieldId === selected.fieldId,
                    ).length
                  }{" "}
                  台
                </strong>
              </div>
            </div>
            <div className="fm-field-records">
              <h3>近期诊断与农事</h3>
              {recordsLoading ? (
                <p>正在读取记录…</p>
              ) : (
                <>
                  {recordErrors.map((error) => (
                    <p key={error}>{error}</p>
                  ))}
                  {diagnoses?.map((item) => (
                    <p key={item.diagnosisId}>
                      <strong>诊断</strong>{" "}
                      {item.summary || item.status || "诊断记录"}{" "}
                      <small>{item.createdAt || ""}</small>
                    </p>
                  ))}
                  {tasks?.map((item, index) => (
                    <p
                      key={
                        typeof item.taskId === "string" ? item.taskId : index
                      }
                    >
                      <strong>农事</strong>{" "}
                      {typeof item.title === "string"
                        ? item.title
                        : "未命名任务"}{" "}
                      <small>
                        {typeof item.updatedAt === "string"
                          ? item.updatedAt
                          : typeof item.createdAt === "string"
                            ? item.createdAt
                            : ""}
                      </small>
                    </p>
                  ))}
                  {diagnoses && tasks && !diagnoses.length && !tasks.length && (
                    <p>当前地块暂无诊断或农事记录。</p>
                  )}
                  {diagnoses && !diagnoses.length && !tasks && (
                    <p>当前地块暂无诊断记录。</p>
                  )}
                  {tasks && !tasks.length && !diagnoses && (
                    <p>当前地块暂无农事记录。</p>
                  )}
                </>
              )}
            </div>
            <button
              className="fm-primary"
              onClick={() => {
                setDraft(
                  `请结合当前地块“${selected.name}”的监测数据、风险和最近农事记录，分析现状并给出建议。`,
                );
                open();
              }}
            >
              <Sparkles size={16} /> 让 Agent 分析这个地块
            </button>
          </aside>
        )}
      </section>
    </main>
  );
}


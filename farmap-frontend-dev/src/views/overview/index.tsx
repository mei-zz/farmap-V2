import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Card, Flex, Tag, Typography } from "antd";
import { useNavigate } from "react-router";
import { createUnavailableOverviewViewModel, loadOverviewViewModel } from "@/features/overview/adapter";
import HomepageCommandCenter from "@/features/overview/components/HomepageCommandCenter";
import type { OverviewDecisionItem, OverviewOperationTask, OverviewRiskPriority, OverviewViewModel } from "@/types/overview";
import { normalizeOverviewEvidenceSources, openOverviewCopilot, type OverviewCopilotContext } from "@/features/overview/copilot/context";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import StatusBadge from "@/components/commercial/StatusBadge";
import VideoPlayer from "@/views/monitor/VideoPlayer";
import { getMonitorAccessToken, getMonitorPreview } from "@/views/monitor/monitorService";
import { cameraBindingLabel, selectedCameraContext, type FarmCamera } from "@/features/farm/cameras";

const { Text, Title } = Typography;

function copilotWeather(overview: OverviewViewModel) {
  return {
    summary: overview.weather.summary,
    temperature: overview.weather.temperature,
    precipitation: `未来 24 小时 ${overview.weather.rainfallForecast}`,
    updatedAt: overview.weather.updatedAt,
    rainfall14d: overview.currentWeather?.rainfall14d,
    rainfallForecast24h: overview.currentWeather?.rainfallForecast24h,
    rainfallProbability24h: overview.currentWeather?.rainfallProbability24h,
    rainfallForecast: overview.currentWeather?.rainfallForecast,
    rainfallForecastDays: overview.currentWeather?.rainfallForecastDays,
    forecast: overview.currentWeather?.forecast,
  };
}

type MonitorPreviewState = {
  status: "idle" | "loading" | "ready" | "error";
  previewUrl?: string;
  error?: string;
};

const MAX_OVERVIEW_CAMERAS = 4;

function OverviewMonitorCard({ overview }: { overview: OverviewViewModel }) {
  const navigate = useNavigate();
  const setContext = useAIContextStore((state) => state.setContext);
  const cameras = overview.cameras;
  const [selectedCameraIds, setSelectedCameraIds] = useState<string[]>([]);
  const [activeCameraId, setActiveCameraId] = useState<string>("");
  const [viewMode, setViewMode] = useState<"grid" | "single">("grid");
  const [previewStates, setPreviewStates] = useState<Record<string, MonitorPreviewState>>({});
  const previewCache = useRef<Record<string, string>>({});

  const selectedCameras = cameras.filter((camera) => selectedCameraIds.includes(camera.cameraId));
  const activeCamera = selectedCameras.find((camera) => camera.cameraId === activeCameraId) ?? selectedCameras[0];
  const visibleCameras = viewMode === "single" && activeCamera ? [activeCamera] : selectedCameras;
  const readyCount = selectedCameras.filter((camera) => previewStates[camera.cameraId]?.status === "ready").length;
  const loadingCount = selectedCameras.filter((camera) => previewStates[camera.cameraId]?.status === "loading").length;
  const hasError = selectedCameras.some((camera) => previewStates[camera.cameraId]?.status === "error");

  useEffect(() => {
    const next = cameras.slice(0, 2).map((camera) => camera.cameraId);
    setSelectedCameraIds(next);
    setActiveCameraId(next[0] ?? "");
    setPreviewStates({});
    previewCache.current = {};
  }, [overview.farm.id, cameras]);

  useEffect(() => {
    let active = true;
    const camerasToLoad = cameras.filter((camera) => selectedCameraIds.includes(camera.cameraId));
    if (overview.mode !== "REAL" || !camerasToLoad.length) {
      setPreviewStates({});
      return () => { active = false; };
    }

    camerasToLoad.forEach((camera) => {
      const cachedUrl = previewCache.current[camera.cameraId];
      if (cachedUrl) {
        setPreviewStates((current) => ({ ...current, [camera.cameraId]: { status: "ready", previewUrl: cachedUrl } }));
        return;
      }

      setPreviewStates((current) => ({ ...current, [camera.cameraId]: { status: "loading" } }));
      void getMonitorAccessToken()
        .then((token) => getMonitorPreview(token.accessToken, camera.serial))
        .then((preview) => {
          if (!active) return;
          previewCache.current[camera.cameraId] = preview.previewUrl;
          setPreviewStates((current) => ({ ...current, [camera.cameraId]: { status: "ready", previewUrl: preview.previewUrl } }));
        })
        .catch((reason: unknown) => {
          if (!active) return;
          setPreviewStates((current) => ({ ...current, [camera.cameraId]: { status: "error", error: reason instanceof Error ? reason.message : "实时摄像头暂时不可用" } }));
        });
    });

    return () => { active = false; };
  }, [cameras, overview.mode, selectedCameraIds]);

  useEffect(() => {
    if (activeCamera || !selectedCameras[0]) return;
    setActiveCameraId(selectedCameras[0].cameraId);
  }, [activeCamera, selectedCameras]);

  useEffect(() => {
    if (!activeCamera) return;
    const activeState = previewStates[activeCamera.cameraId];
    setContext({
      selectedDevice: selectedCameraContext(activeCamera, activeState?.status === "ready"),
      currentLocation: { label: cameraBindingLabel(activeCamera), longitude: activeCamera.longitude, latitude: activeCamera.latitude },
    });
  }, [activeCamera, previewStates, setContext]);

  const toggleCamera = (camera: FarmCamera) => {
    setSelectedCameraIds((current) => {
      if (current.includes(camera.cameraId)) {
        if (current.length === 1) return current;
        const next = current.filter((id) => id !== camera.cameraId);
        if (activeCameraId === camera.cameraId) setActiveCameraId(next[0]);
        return next;
      }
      if (current.length >= MAX_OVERVIEW_CAMERAS) return current;
      setActiveCameraId(camera.cameraId);
      return [...current, camera.cameraId];
    });
  };

  const statusTone = readyCount > 0 ? "healthy" : loadingCount > 0 ? "running" : hasError ? "attention" : "pending";
  const statusLabel = readyCount > 0 ? `${readyCount}/${selectedCameras.length} 在线` : loadingCount > 0 ? "连接中" : hasError ? "部分异常" : "未接入";

  return <Card
    variant="borderless"
    className="fm-supporting-card fm-supporting-card--monitor"
    title={<Flex align="center" gap={8}><span>监控画面</span><span className="fm-monitor-card__subtitle">当前农场 · {cameras.length} 路</span></Flex>}
    extra={<Flex align="center" gap={6}><StatusBadge tone={statusTone} label={statusLabel} /><Button type="link" size="small" onClick={() => navigate("/farm/devices")}>设备管理</Button></Flex>}
  >
    <div className="fm-monitor-card__toolbar">
      <div><b>当前监控</b><span>已选 {selectedCameras.length}/{MAX_OVERVIEW_CAMERAS} 路 · 可点击选择不同视角</span></div>
      <div className="fm-monitor-card__view-switch" role="group" aria-label="监控布局">
        <button type="button" className={viewMode === "grid" ? "is-active" : ""} onClick={() => setViewMode("grid")}>多画面</button>
        <button type="button" className={viewMode === "single" ? "is-active" : ""} onClick={() => setViewMode("single")}>主画面</button>
      </div>
    </div>
    <div className={`fm-monitor-card__views${viewMode === "single" ? " fm-monitor-card__views--single" : ""}`}>
      {visibleCameras.length === 0 && <div className="fm-monitor-card__empty">当前农场暂无已绑定的实时摄像头，请到设备与监控查看归属状态。</div>}
      {visibleCameras.map((camera) => {
        const preview = previewStates[camera.cameraId];
        const isActive = activeCamera?.cameraId === camera.cameraId;
        return <button type="button" className={`fm-monitor-tile${isActive ? " is-active" : ""}`} key={camera.cameraId} onClick={() => setActiveCameraId(camera.cameraId)} aria-label={`查看 ${camera.name}`}>
          <div className={`fm-monitor-tile__viewport${preview?.previewUrl ? " is-live" : ""}`}>
            {preview?.previewUrl ? <VideoPlayer videoUrl={preview.previewUrl} style={{ display: "block" }} /> : <div className="fm-monitor-tile__placeholder"><span>{camera.serial}</span><b>{preview?.status === "loading" ? "正在连接实时画面" : preview?.error || "暂无实时画面"}</b></div>}
            <span className={`fm-monitor-tile__live fm-monitor-tile__live--${preview?.status ?? "idle"}`}><i />{preview?.status === "ready" ? "LIVE" : preview?.status === "loading" ? "连接中" : preview?.status === "error" ? "异常" : "待连接"}</span>
          </div>
          <div className="fm-monitor-tile__meta"><span><b>{camera.name}</b><small>{camera.serial} · {cameraBindingLabel(camera)}</small></span><span className="fm-monitor-tile__focus">{isActive ? "主画面" : "查看"}</span></div>
        </button>;
      })}
    </div>
    <div className="fm-monitor-picker">
      <Flex align="center" justify="space-between" className="fm-monitor-picker__header"><span><b>选择监控</b><small>最多同时查看 {MAX_OVERVIEW_CAMERAS} 路</small></span><Tag>{readyCount} 路实时</Tag></Flex>
      <div className="fm-monitor-picker__list" role="list" aria-label="摄像头列表">
        {cameras.map((camera) => {
          const selected = selectedCameraIds.includes(camera.cameraId);
          const disabled = !selected && selectedCameraIds.length >= MAX_OVERVIEW_CAMERAS;
          return <button type="button" role="listitem" key={camera.cameraId} className={`fm-monitor-picker__option${selected ? " is-selected" : ""}${activeCamera?.cameraId === camera.cameraId ? " is-active" : ""}`} onClick={() => toggleCamera(camera)} disabled={disabled} aria-pressed={selected}>
            <span className="fm-monitor-picker__check">{selected ? "✓" : ""}</span><span><b>{camera.name}</b><small>{camera.serial}</small></span>
          </button>;
        })}
      </div>
    </div>
    <span className="fm-supporting-card__note">{readyCount ? `画面正常 · 当前 ${readyCount} 路已接入，可在设备与监控查看当前农场全部 ${cameras.length} 路摄像头` : cameras.length ? "正在连接当前农场已绑定的真实摄像头" : "当前农场没有已绑定的实时摄像头"}</span>
  </Card>;
}

export default function Overview() {
  const navigate = useNavigate();
  const openCopilot = useCopilotStore((state) => state.open);
  const setCopilotDraft = useCopilotStore((state) => state.setDraft);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const setContext = useAIContextStore((state) => state.setContext);
  const currentFarmId = useAIContextStore((state) => state.context.currentFarm.id);
  const [overview, setOverview] = useState<OverviewViewModel>(() => createUnavailableOverviewViewModel());
  const [loadError, setLoadError] = useState<string | undefined>();
  const activeFarmId = currentFarmId > 0 ? currentFarmId : undefined;
  useEffect(() => {
    let active = true;
    loadOverviewViewModel(activeFarmId).then((value) => { if (active) { setOverview(value); setLoadError(undefined); } }).catch((error: unknown) => { if (active) setLoadError(error instanceof Error ? error.message : "真实农场数据不可用"); });
    return () => { active = false; };
  }, [activeFarmId]);
  const riskItems = overview.riskPriorities.filter((item) => item.signalType === "RISK");
  const [selectedRiskId, setSelectedRiskId] = useState<string | undefined>(riskItems[0]?.id);
  useEffect(() => {
    if (!selectedRiskId && riskItems[0]) setSelectedRiskId(riskItems[0].id);
  }, [riskItems, selectedRiskId]);

  const setPrimaryFieldContext = useCallback(() => {
    const primaryCamera = overview.cameras[0];
    setContext({
      currentFarm: { id: overview.farm.id, name: overview.farm.name, address: overview.farm.address },
      currentField: { id: overview.primaryField.id, name: overview.primaryField.name, area: overview.primaryField.area, crop: overview.primaryField.crop },
      currentCrop: { name: overview.primaryField.crop, variety: overview.primaryField.variety, growthStage: overview.primaryField.growthStage },
      currentWeather: copilotWeather(overview),
      currentPhenology: { stage: overview.primaryField.growthStage, progress: overview.primaryField.phenologyProgress },
      selectedDiagnosis: { id: overview.diagnosis.id, title: overview.diagnosis.title, severity: overview.diagnosis.severity, confidence: overview.diagnosis.confidence, summary: overview.diagnosis.summary },
      currentLocation: primaryCamera
        ? { label: cameraBindingLabel(primaryCamera), latitude: primaryCamera.latitude, longitude: primaryCamera.longitude }
        : { label: `${overview.primaryField.id} 地块中心`, latitude: overview.primaryField.latitude, longitude: overview.primaryField.longitude },
      selectedDevice: primaryCamera ? selectedCameraContext(primaryCamera, false) : undefined,
      selectedImages: [],
      currentPage: "/overview",
      sourceModes: overview.mode === "REAL" ? { field: "MYSQL", weather: overview.currentWeather?.source ?? overview.currentWeather?.provider ?? "OPEN_METEO", gis: "MYSQL", camera: overview.cameras.length ? "HIKVISION_CLOUD" : "UNAVAILABLE", knowledge: "MYSQL", historical: "READY_EMPTY" } : { field: overview.mode, weather: overview.mode, gis: overview.mode, camera: overview.mode, knowledge: overview.mode, historical: overview.mode },
    });
  }, [overview, setContext]);

  useEffect(() => {
    setPrimaryFieldContext();
  }, [setPrimaryFieldContext]);

  const openDiagnosis = (diagnosisId?: string) => {
    setPrimaryFieldContext();
    if (diagnosisId && diagnosisId !== "UNAVAILABLE") navigate(`/diagnosis/${encodeURIComponent(diagnosisId)}`);
  };

  const handleDecisionPrimaryAction = useCallback((item: OverviewDecisionItem) => {
    if (item.fieldId === overview.primaryField.id) setPrimaryFieldContext();
    navigate(item.nextAction.path);
  }, [navigate, overview.primaryField.id, setPrimaryFieldContext]);

  const handleDecisionAskAI = useCallback((item: OverviewDecisionItem) => {
    const copilotContext: OverviewCopilotContext = {
      contextType: "DECISION",
      mode: overview.mode,
      page: "/overview",
      farm: { id: overview.farm.id, name: overview.farm.name },
      field: item.fieldId ? { id: item.fieldId, name: item.fieldName ?? item.fieldId, crop: item.crop, area: item.fieldArea } : undefined,
      weather: copilotWeather(overview),
      phenology: item.fieldGrowthStage && item.fieldPhenologyProgress !== undefined ? { stage: item.fieldGrowthStage, progress: item.fieldPhenologyProgress } : undefined,
      title: item.title,
      summary: item.summary,
      risk: item.kind === "RISK" ? { id: item.id, title: item.title, priority: item.priority, confidenceLabel: item.confidenceLabel, affectedArea: item.impact?.value } : undefined,
      evidence: { ...normalizeOverviewEvidenceSources(item.evidenceSources, overview.mode), count: overview.mode === "UNAVAILABLE" ? undefined : item.evidenceCount },
      freshness: item.freshness,
    };
    openOverviewCopilot(copilotContext, { setAIContext: setContext, setCopilotContext, setDraft: setCopilotDraft, open: openCopilot });
  }, [openCopilot, overview, setContext, setCopilotContext, setCopilotDraft]);

  const handleRiskSelect = useCallback((risk: OverviewRiskPriority) => {
    setSelectedRiskId(risk.id);
    setCopilotContext(undefined);
    setCopilotDraft("");
    if (risk.fieldId === overview.primaryField.id) {
      setPrimaryFieldContext();
      return;
    }
    if (risk.fieldId && risk.fieldName && risk.crop && risk.fieldArea !== undefined) {
      const center = risk.mapReference?.center;
      setContext({
        currentPage: "/overview",
        currentField: { id: risk.fieldId, name: risk.fieldName, area: risk.fieldArea, crop: risk.crop },
        currentCrop: { name: risk.crop, growthStage: risk.fieldGrowthStage },
        currentLocation: center ? { label: `${risk.fieldId} 地块中心`, latitude: center[1], longitude: center[0] } : undefined,
        selectedDiagnosis: undefined,
        selectedDevice: undefined,
      });
      return;
    }
    setContext({
      currentPage: "/overview",
      currentField: undefined,
      currentCrop: undefined,
      currentLocation: undefined,
      selectedDiagnosis: undefined,
      selectedDevice: undefined,
    });
  }, [overview.primaryField.id, setContext, setCopilotContext, setCopilotDraft, setPrimaryFieldContext]);

  const handleOperationOpen = useCallback((task: OverviewOperationTask) => {
    if (task.fieldId === overview.primaryField.id) setPrimaryFieldContext();
    navigate("/operations/tasks");
  }, [navigate, overview.primaryField.id, setPrimaryFieldContext]);

  const openNewDiagnosis = useCallback(() => {
    setPrimaryFieldContext();
    navigate(`/diagnosis/new?farmId=${overview.farm.id}&fieldId=${encodeURIComponent(overview.primaryField.id)}`);
  }, [navigate, overview.farm.id, overview.primaryField.id, setPrimaryFieldContext]);

  return (
    <div className="fm-page fm-overview-page">
      <Flex className="fm-page-header" justify="space-between" align="flex-end" gap={20} wrap>
        <div><div className="fm-eyebrow">SMART FARM / OVERVIEW</div><Title level={2}>农场智能总览</Title><Text type="secondary">基于已验证的农场、地块、天气与 AI 事实，为你提供可追溯的农情决策入口。</Text>{loadError && <div className="fm-overview-data-warning">真实数据加载失败：{loadError}。当前页面不会使用演示数据替代。</div>}</div>
      </Flex>

      <HomepageCommandCenter
        overview={overview}
        monitor={<OverviewMonitorCard overview={overview} />}
        onStartDiagnosis={openNewDiagnosis}
        onOpenDiagnosis={openDiagnosis}
        onOpenAgentRuns={() => navigate("/analysis")}
        onOpenDigitalFarm={() => navigate("/farm/map")}
        onOpenOperations={() => navigate("/operations/tasks")}
        onDecisionPrimaryAction={handleDecisionPrimaryAction}
        onDecisionAskAI={handleDecisionAskAI}
        onRiskSelect={handleRiskSelect}
        selectedRiskId={selectedRiskId}
        onOperationOpen={handleOperationOpen}
      />
    </div>
  );
}

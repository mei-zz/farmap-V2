import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Divider,
  Flex,
  Input,
  Select,
  Spin,
  Tag,
  Typography,
  Upload,
  type UploadFile,
  type UploadProps,
} from "antd";
import {
  CheckCircleFilled,
  CheckOutlined,
  CloudOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EnvironmentOutlined,
  FileImageOutlined,
  HistoryOutlined,
  LoadingOutlined,
  RobotOutlined,
  SendOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { useNavigate, useSearchParams } from "react-router";
import { useAIContextStore } from "@/store/aiContext";
import { permanence } from "@/utils/permanence";
import {
  createDiagnosisJob,
  DiagnosisApiError,
  type DiagnosisJob,
  listDiagnosisFields,
  pollDiagnosisJob,
  uploadDiagnosisImages,
  type DiagnosisField,
} from "@/features/diagnosis/service";
import { createAgentRun, AgentRuntimeUnavailableError } from "@/features/agent/service";
import { loadAgriculturalContext, loadCurrentWeather, type AgriculturalContext, type CurrentWeatherContext } from "@/features/agricultural/context";
import { cameraBindingLabel, cameraSourceLabel, loadFarmCameras, type FarmCamera } from "@/features/farm/cameras";
import { cropDisplayName, growthStageDisplayName } from "@/features/farm/labels";

const { Paragraph, Text, Title } = Typography;

type StartPhase = "READY" | "UPLOADING" | "DIAGNOSING" | "CREATING_AGENT";

type CapturedDiagnosisImage = {
  id: string;
  url: string;
  name: string;
  cameraId: string;
  cameraName: string;
  fieldName?: string;
  pointId?: string;
  capturedAt: string;
};

const MAX_IMAGE_COUNT = 5;
const MAX_IMAGE_SIZE = 10 * 1024 * 1024;
const ACTIVE_DIAGNOSIS_JOB_KEY = "farmap.active-diagnosis-job";

function statusLabel(status?: string) {
  if (status === "READY") return "已接入";
  if (status === "EMPTY") return "暂无记录";
  if (status === "CONFIGURED_UNAVAILABLE") return "服务不可用";
  if (status === "NOT_CONFIGURED") return "未配置";
  return "待确认";
}

function weatherContext(value?: CurrentWeatherContext) {
  if (!value) return undefined;
  return {
    summary: value.condition ?? value.reason,
    temperature: value.temperature,
    observedAt: value.observedAt ?? value.retrievedAt,
    rainfall14d: value.rainfall14d,
  };
}

function phaseText(phase: StartPhase) {
  if (phase === "UPLOADING") return "正在上传现场图片…";
  if (phase === "DIAGNOSING") return "后台生成 Diagnosis…";
  if (phase === "CREATING_AGENT") return "正在创建 Agent Run…";
  return "开始 AI 调查";
}

function errorMessage(error: unknown) {
  if (error instanceof DiagnosisApiError || error instanceof AgentRuntimeUnavailableError) return error.message;
  return error instanceof Error ? error.message : "AI 调查未完成，请稍后重试。";
}

function sourceState(status: string | undefined, loading: boolean) {
  if (loading) return { label: "读取中", color: "processing" as const };
  if (status === "READY") return { label: "已接入", color: "green" as const };
  if (status === "EMPTY") return { label: "暂无记录", color: "blue" as const };
  return { label: statusLabel(status), color: "orange" as const };
}

type ActiveDiagnosisJob = {
  jobId: string;
  farmId: number;
  fieldId: string;
  goal: string;
  imageUrls: string[];
  imageNames: string[];
};

function saveActiveDiagnosisJob(job: ActiveDiagnosisJob) {
  sessionStorage.setItem(ACTIVE_DIAGNOSIS_JOB_KEY, JSON.stringify(job));
}

function readActiveDiagnosisJob(): ActiveDiagnosisJob | undefined {
  try {
    const raw = sessionStorage.getItem(ACTIVE_DIAGNOSIS_JOB_KEY);
    if (!raw) return undefined;
    const parsed = JSON.parse(raw) as Partial<ActiveDiagnosisJob>;
    if (typeof parsed.jobId !== "string" || typeof parsed.fieldId !== "string" || typeof parsed.farmId !== "number") return undefined;
    return {
      jobId: parsed.jobId,
      farmId: parsed.farmId,
      fieldId: parsed.fieldId,
      goal: typeof parsed.goal === "string" ? parsed.goal : "调查当前地块风险并生成可执行方案。",
      imageUrls: Array.isArray(parsed.imageUrls) ? parsed.imageUrls.filter((url): url is string => typeof url === "string") : [],
      imageNames: Array.isArray(parsed.imageNames) ? parsed.imageNames.filter((name): name is string => typeof name === "string") : [],
    };
  } catch {
    return undefined;
  }
}

function clearActiveDiagnosisJob() {
  sessionStorage.removeItem(ACTIVE_DIAGNOSIS_JOB_KEY);
}

export default function DiagnosisStart() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const currentFarm = useAIContextStore((state) => state.context.currentFarm);
  const setContext = useAIContextStore((state) => state.setContext);
  const storedUser = permanence.user.useUserStore();
  const token = permanence.token.useToken();
  const requestedFarmId = Number(searchParams.get("farmId"));
  const activeFarmId = requestedFarmId > 0
    ? requestedFarmId
    : currentFarm.id > 0 ? currentFarm.id : storedUser?.farms?.[0]?.id ?? -1;
  const farmName = activeFarmId === currentFarm.id
    ? currentFarm.name
    : storedUser?.farms?.find((farm) => farm.id === activeFarmId)?.name ?? currentFarm.name;
  const requestedFieldId = searchParams.get("fieldId") || undefined;
  const requestedGoal = searchParams.get("goal") || undefined;

  const [fields, setFields] = useState<DiagnosisField[]>([]);
  const [selectedFieldId, setSelectedFieldId] = useState<string>();
  const [cameras, setCameras] = useState<FarmCamera[]>([]);
  const [selectedCameraId, setSelectedCameraId] = useState<string>();
  const [capturedImages, setCapturedImages] = useState<CapturedDiagnosisImage[]>([]);
  const [weather, setWeather] = useState<CurrentWeatherContext>();
  const [agricultural, setAgricultural] = useState<AgriculturalContext>();
  const [fieldsLoading, setFieldsLoading] = useState(false);
  const [supportLoading, setSupportLoading] = useState(false);
  const [fieldError, setFieldError] = useState("");
  const [cameraError, setCameraError] = useState("");
  const [camerasLoading, setCamerasLoading] = useState(false);
  const [weatherError, setWeatherError] = useState("");
  const [historyError, setHistoryError] = useState("");
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [query, setQuery] = useState(() => requestedGoal || "结合当前地块、天气与历史农情，调查需要优先处理的风险并生成可执行方案。");
  const [phase, setPhase] = useState<StartPhase>("READY");
  const [error, setError] = useState("");
  const [pendingDiagnosisId, setPendingDiagnosisId] = useState<string>();
  const [pendingRecommendationId, setPendingRecommendationId] = useState<string>();
  const [diagnosisJobMessage, setDiagnosisJobMessage] = useState("");
  const recoveryAttemptedRef = useRef(false);

  const selectedField = useMemo(() => fields.find((field) => field.fieldId === selectedFieldId), [fields, selectedFieldId]);
  const availableCameras = useMemo(() => {
    if (!selectedField) return cameras;
    const bound = cameras.filter((camera) => camera.fieldId === selectedField.fieldId);
    return bound.length ? bound : cameras;
  }, [cameras, selectedField]);
  const hasDirectFieldCameras = Boolean(selectedField && cameras.some((camera) => camera.fieldId === selectedField.fieldId));
  const selectedCamera = useMemo(() => availableCameras.find((camera) => camera.cameraId === selectedCameraId), [availableCameras, selectedCameraId]);
  const imageCount = fileList.length + capturedImages.length;
  const historicalStatus = agricultural
    ? [agricultural.cropObservations.status, agricultural.historicalTemperature.status, agricultural.recentDiagnoses.status].some((status) => status === "READY")
      ? "READY"
      : "EMPTY"
    : undefined;
  const weatherState = sourceState(weather?.status, supportLoading && !weather && !weatherError);
  const historyState = sourceState(historicalStatus, supportLoading && !agricultural && !historyError);

  useEffect(() => {
    if (requestedGoal) setQuery(requestedGoal);
  }, [requestedGoal]);

  useEffect(() => {
    if (activeFarmId <= 0) {
      setFields([]);
      setSelectedFieldId(undefined);
      setCameras([]);
      setSelectedCameraId(undefined);
      setCapturedImages([]);
      setWeather(undefined);
      setAgricultural(undefined);
      setFieldsLoading(false);
      setSupportLoading(false);
      return;
    }
    let active = true;
    setFieldsLoading(true);
    setSupportLoading(true);
    setFieldError("");
    setCameraError("");
    setWeatherError("");
    setHistoryError("");
    setFields([]);
    setSelectedFieldId(undefined);
    setCameras([]);
    setSelectedCameraId(undefined);
    setCapturedImages([]);
    setFileList([]);
    setWeather(undefined);
    setAgricultural(undefined);

    void listDiagnosisFields(activeFarmId)
      .then((nextFields) => {
        if (!active) return;
        setFields(nextFields);
        setSelectedFieldId(nextFields.find((field) => field.fieldId === requestedFieldId)?.fieldId ?? nextFields[0]?.fieldId);
      })
      .catch((nextError: unknown) => { if (active) setFieldError(errorMessage(nextError)); })
      .finally(() => { if (active) setFieldsLoading(false); });

    setCamerasLoading(true);
    void loadFarmCameras(activeFarmId, token)
      .then((nextCameras) => { if (active) setCameras(nextCameras); })
      .catch((nextError: unknown) => { if (active) setCameraError(errorMessage(nextError)); })
      .finally(() => { if (active) setCamerasLoading(false); });

    void loadCurrentWeather(activeFarmId, token)
      .then((nextWeather) => {
        if (!active) return;
        setWeather(nextWeather);
        setContext({ currentWeather: { summary: nextWeather.condition ?? nextWeather.reason ?? "天气数据不可用", temperature: nextWeather.temperature ? `${nextWeather.temperature}°C` : "—", precipitation: nextWeather.rainfallForecast24h !== undefined ? `未来24小时 ${nextWeather.rainfallForecast24h.toFixed(1)} mm` : "暂无降雨信息", updatedAt: nextWeather.observedAt ?? nextWeather.retrievedAt ?? new Date().toISOString(), rainfall14d: nextWeather.rainfall14d, rainfallForecast24h: nextWeather.rainfallForecast24h, rainfallProbability24h: nextWeather.rainfallProbability24h, rainfallForecast: nextWeather.rainfallForecast, rainfallForecastDays: nextWeather.rainfallForecastDays, forecast: nextWeather.forecast } });
      })
      .catch((nextError: unknown) => { if (active) setWeatherError(errorMessage(nextError)); });

    void loadAgriculturalContext(activeFarmId, token, { temperatureLimit: 30, diagnosisLimit: 5 })
      .then((nextContext) => { if (active) setAgricultural(nextContext); })
      .catch((nextError: unknown) => { if (active) setHistoryError(errorMessage(nextError)); })
      .finally(() => { if (active) setSupportLoading(false); });

    return () => { active = false; };
  }, [activeFarmId, requestedFieldId, setContext, token]);

  useEffect(() => {
    if (!availableCameras.length) {
      setSelectedCameraId(undefined);
      return;
    }
    setSelectedCameraId((current) => availableCameras.some((camera) => camera.cameraId === current) ? current : availableCameras[0].cameraId);
  }, [availableCameras]);

  useEffect(() => {
    if (!selectedField || activeFarmId <= 0) return;
    setContext({
      currentFarm: { id: activeFarmId, name: farmName, address: currentFarm.address },
      currentField: { id: selectedField.fieldId, name: selectedField.name, area: Number(selectedField.area ?? 0), crop: selectedField.crop ?? "" },
      currentCrop: { name: selectedField.crop ?? "未标注作物", growthStage: selectedField.growthStage },
      currentPhenology: undefined,
      currentLocation: selectedField.latitude !== undefined && selectedField.longitude !== undefined
        ? { label: `${selectedField.name} 中心`, latitude: selectedField.latitude, longitude: selectedField.longitude }
        : undefined,
      selectedDiagnosis: undefined,
      agentRunId: undefined,
      taskId: undefined,
      selectedDevice: undefined,
    });
  }, [activeFarmId, currentFarm.address, farmName, selectedField, setContext]);

  const handleFieldChange = (fieldId: string) => {
    setSelectedFieldId(fieldId);
    setFileList([]);
    setCapturedImages([]);
    setSelectedCameraId(undefined);
    setError("");
  };

  const handleUploadChange: UploadProps["onChange"] = ({ fileList: nextFileList }) => {
    const validFiles = nextFileList.filter((file) => {
      const raw = file.originFileObj;
      return Boolean(raw && ["image/jpeg", "image/png"].includes(raw.type) && raw.size <= MAX_IMAGE_SIZE);
    });
    const remainingSlots = Math.max(0, MAX_IMAGE_COUNT - capturedImages.length);
    setFileList(validFiles.slice(0, remainingSlots));
    if (nextFileList.length > validFiles.length || validFiles.length > remainingSlots) setError("仅支持不超过 10MB 的 JPG/PNG 图片，且本次调查最多使用 5 张图片。");
    else setError("");
  };

  const launchAgent = useCallback(async (diagnosisId: string, recommendationId: string | undefined, goal: string) => {
    setPhase("CREATING_AGENT");
    setPendingDiagnosisId(diagnosisId);
    setPendingRecommendationId(recommendationId);
    const run = await createAgentRun(goal, undefined, "real", { diagnosisId, recommendationId, intent: goal });
    setContext({ agentRunId: run.runId });
    navigate(`/analysis/${encodeURIComponent(run.runId)}?diagnosisId=${encodeURIComponent(diagnosisId)}`);
  }, [navigate, setContext]);

  const finalizeDiagnosis = useCallback(async (job: DiagnosisJob, field: DiagnosisField, imageUrls: string[], imageNames: string[], goal: string) => {
    if (job.status === "FAILED") {
      clearActiveDiagnosisJob();
      throw new DiagnosisApiError(job.error || job.message || "实时分析失败");
    }
    const response = job.result;
    const diagnosisId = job.diagnosisId || response?.diagnosisId;
    if (!diagnosisId) throw new DiagnosisApiError("诊断服务未返回稳定 diagnosisId，已停止创建 Agent。");
    if (!response?.diagnosis) throw new DiagnosisApiError("诊断服务未返回结构化结果，已停止创建 Agent。");
    clearActiveDiagnosisJob();
    setContext({
      selectedImages: imageUrls.map((url, index) => ({ id: `diagnosis-image-${index + 1}`, name: imageNames[index] ?? `现场图片 ${index + 1}`, url })),
      selectedDiagnosis: { id: diagnosisId, title: response.diagnosis.title, severity: response.diagnosis.risk_level, confidence: Math.round(response.diagnosis.confidence * 100), summary: response.diagnosis.summary },
      currentField: { id: field.fieldId, name: field.name, area: Number(field.area ?? 0), crop: field.crop ?? "" },
      currentCrop: { name: field.crop ?? "未标注作物", growthStage: field.growthStage },
    });
    await launchAgent(diagnosisId, response.recommendations?.[0]?.id, goal);
  }, [launchAgent, setContext]);

  // A real RAG call can outlive the current route. Restore its job on return
  // instead of submitting the same Diagnosis a second time.
  useEffect(() => {
    if (recoveryAttemptedRef.current || !selectedField || activeFarmId <= 0 || phase !== "READY") return;
    recoveryAttemptedRef.current = true;
    const saved = readActiveDiagnosisJob();
    if (!saved || saved.farmId !== activeFarmId || saved.fieldId !== selectedField.fieldId) return;
    setPhase("DIAGNOSING");
    setDiagnosisJobMessage("正在恢复上次真实 Diagnosis 任务…");
    void pollDiagnosisJob(saved.jobId, (nextJob) => {
      setDiagnosisJobMessage(nextJob.message || "后台正在处理真实 Diagnosis…");
    }, 1500, {
      onError: (_, consecutiveErrors) => setDiagnosisJobMessage(`连接暂时中断，正在自动重试（第 ${consecutiveErrors} 次）；任务仍在后台运行。`),
    }).then((job) => finalizeDiagnosis(job, selectedField, saved.imageUrls, saved.imageNames, saved.goal))
      .catch((nextError: unknown) => {
        setPhase("READY");
        setDiagnosisJobMessage("");
        setError(errorMessage(nextError));
      });
  }, [activeFarmId, finalizeDiagnosis, phase, selectedField]);

  const startInvestigation = async () => {
    if (!selectedField || activeFarmId <= 0 || phase !== "READY") return;
    setError("");
    setPendingDiagnosisId(undefined);
    setPendingRecommendationId(undefined);
    try {
      let imageUrls: string[] = capturedImages.map((image) => image.url);
      let imageNames: string[] = capturedImages.map((image) => image.name);
      const files = fileList.map((file) => file.originFileObj).filter((file): file is NonNullable<typeof file> => Boolean(file));
      if (files.length) {
        setPhase("UPLOADING");
        imageUrls = [...imageUrls, ...(await uploadDiagnosisImages(files, {
          farmId: activeFarmId,
          fieldId: selectedField.fieldId,
          pointId: selectedCamera?.pointId,
          cameraId: selectedCamera?.cameraId,
        }))];
        imageNames = [...imageNames, ...files.map((file) => file.name)];
      }
      setPhase("DIAGNOSING");
      setDiagnosisJobMessage("任务已提交，后台正在检索真实证据…");
      const goal = query.trim() || "调查当前地块风险并生成可执行方案。";
      const job = await createDiagnosisJob({
        fieldId: selectedField.fieldId,
        farmId: activeFarmId,
        pointId: selectedCamera?.pointId,
        cameraId: selectedCamera?.cameraId,
        crop: selectedField.crop,
        growthStage: selectedField.growthStage,
        imageUrls,
        weather: weatherContext(weather),
        query: goal,
        topK: 5,
        enabledModalities: ["camera", "weather", "historical_case", "knowledge", "soil", "spatial"],
      });
      saveActiveDiagnosisJob({ jobId: job.jobId, farmId: activeFarmId, fieldId: selectedField.fieldId, goal, imageUrls, imageNames });
      setDiagnosisJobMessage(job.message || "后台正在处理真实 Diagnosis…");
      const completedJob = await pollDiagnosisJob(job.jobId, (nextJob) => {
        setDiagnosisJobMessage(nextJob.message || "后台正在处理真实 Diagnosis…");
      }, 1500, {
        onError: (_, consecutiveErrors) => setDiagnosisJobMessage(`连接暂时中断，正在自动重试（第 ${consecutiveErrors} 次）；任务仍在后台运行。`),
      });
      await finalizeDiagnosis(completedJob, selectedField, imageUrls, imageNames, goal);
    } catch (nextError: unknown) {
      setPhase("READY");
      setDiagnosisJobMessage("");
      setError(errorMessage(nextError));
    }
  };

  const retryAgent = async () => {
    if (!pendingDiagnosisId || !selectedField || phase !== "READY") return;
    setError("");
    try { await launchAgent(pendingDiagnosisId, pendingRecommendationId, query.trim() || `分析${selectedField.name}的农业风险并生成处理方案。`); }
    catch (nextError: unknown) { setPhase("READY"); setError(`Diagnosis 已保存，但 Agent Run 尚未创建：${errorMessage(nextError)}`); }
  };

  const fieldOptions = fields.map((field) => ({ value: field.fieldId, label: `${field.name} · ${cropDisplayName(field.crop) || "作物未标注"}` }));
  const readyToStart = Boolean(selectedField && !fieldsLoading && phase === "READY");

  return (
    <div className="fm-page fm-diagnosis-start">
      <Flex className="fm-page-header fm-diagnosis-start__header" justify="space-between" align="flex-start" gap={16} wrap>
        <div>
          <div className="fm-eyebrow">农业 AI 诊断</div>
          <Title level={2}>AI 农情诊断</Title>
          <Paragraph>先确认地块与分析输入，再由 AI 调查、检索证据并生成后续处理方案。</Paragraph>
        </div>
        <Flex gap={8} wrap><Tag color="blue">真实多模态检索</Tag><Tag icon={<EnvironmentOutlined />}>当前农场：{farmName || "未选择农场"}</Tag></Flex>
      </Flex>

      <Alert className="fm-diagnosis-start__flow-alert" type="info" showIcon icon={<RobotOutlined />} message="这一步负责发起任务，不直接生成一段不可追踪的文本" description="点击开始 AI 调查后，将先保存 diagnosisId，再创建 Agent Run，最后进入农业 AI 分析工作区。" />

      <div className="fm-diagnosis-start__grid">
        <main>
          <Card bordered={false} className="fm-diagnosis-start__card" title={<span><EnvironmentOutlined /> 分析对象</span>} extra={<Tag color={fieldsLoading ? "processing" : selectedField ? "green" : "orange"}>{fieldsLoading ? "读取地块" : selectedField ? "已选择地块" : "需要选择地块"}</Tag>}>
            <Text type="secondary">当前农场</Text>
            <div className="fm-diagnosis-start__farm-name">{farmName || "未选择农场"}</div>
            <Select aria-label="选择地块" value={selectedFieldId} loading={fieldsLoading} disabled={!fieldOptions.length || phase !== "READY"} options={fieldOptions} onChange={handleFieldChange} placeholder="请选择需要分析的地块" style={{ width: "100%" }} />
            {fieldError && <Alert className="fm-diagnosis-start__inline-alert" type="error" showIcon title="地块数据不可用" description={fieldError} />}
            {!fieldsLoading && !fieldError && !fields.length && <Alert className="fm-diagnosis-start__inline-alert" type="warning" showIcon title="当前农场没有可分析地块" description="请先在农场地块管理中建立真实地块，再发起 AI 诊断。" />}
            {selectedField && <div className="fm-diagnosis-start__field-summary"><div><small>地块</small><b>{selectedField.name}</b></div><div><small>作物</small><b>{cropDisplayName(selectedField.crop) || "未标注"}</b></div><div><small>生育期</small><b>{growthStageDisplayName(selectedField.growthStage) || "未标注"}</b></div><div><small>面积</small><b>{selectedField.area !== undefined ? `${selectedField.area} ha` : "—"}</b></div></div>}
          </Card>

          <Card bordered={false} className="fm-diagnosis-start__card" title={<span><FileImageOutlined /> 分析输入</span>}>
            <div className="fm-diagnosis-start__source-grid">
              <div className="fm-diagnosis-start__source-item"><CheckCircleFilled /><div><b>当前地块数据</b><small>{selectedField ? `${selectedField.name} · ${selectedField.sourceMode === "SYNTHETIC_PRESENTATION" ? "答辩演示数据（可追溯）" : "生产数据"}` : "选择地块后接入"}</small></div><Tag color={selectedField ? "green" : "orange"}>{selectedField ? "已接入" : "待选择"}</Tag></div>
              <div className="fm-diagnosis-start__source-item"><CloudOutlined /><div><b>当前天气</b><small>{weather?.condition || weather?.reason || (weatherError ? "读取失败" : "读取中")}</small></div><Tag color={weatherState.color}>{weatherState.label}</Tag></div>
              <div className="fm-diagnosis-start__source-item"><HistoryOutlined /><div><b>历史农情</b><small>{historyError ? "读取失败" : agricultural?.cropObservations?.totalObservations ? `${agricultural.cropObservations.totalObservations} 条作物观测` : "历史温度、作物观测与诊断记录"}</small></div><Tag color={historyState.color}>{historyState.label}</Tag></div>
            </div>
            <Divider />
            <Text strong>上传现场图片 <Text type="secondary">（可选，最多 5 张）</Text></Text>
            <Flex className="fm-diagnosis-start__upload-actions" gap={8} wrap>
              <Upload className="fm-diagnosis-start__upload" accept="image/jpeg,image/png" listType="picture" multiple maxCount={MAX_IMAGE_COUNT} beforeUpload={() => false} fileList={fileList} onChange={handleUploadChange} disabled={phase !== "READY" || imageCount >= MAX_IMAGE_COUNT}>
                <Button icon={<FileImageOutlined />} disabled={imageCount >= MAX_IMAGE_COUNT}>选择现场图片</Button>
              </Upload>
            </Flex>
            <div className="fm-diagnosis-start__camera-capture">
              <div className="fm-diagnosis-start__camera-capture-header">
                <div><b>现场摄像头</b><small>{selectedField ? (hasDirectFieldCameras ? "已按当前地块筛选" : "当前地块暂无直接绑定摄像头，显示本农场可用设备") : "选择地块后显示对应区域摄像头"}</small></div>
                <Tag color={camerasLoading ? "processing" : availableCameras.length ? "green" : "orange"}>{camerasLoading ? "读取中" : `${availableCameras.length} 台可用`}</Tag>
              </div>
              <Flex gap={8} align="center" wrap>
                <Select
                  aria-label="选择关联摄像头"
                  value={selectedCameraId}
                  loading={camerasLoading}
                  disabled={!availableCameras.length || phase !== "READY"}
                  onChange={setSelectedCameraId}
                  placeholder="选择当前区域摄像头"
                  style={{ flex: 1, minWidth: 260 }}
                  options={availableCameras.map((camera) => ({
                    value: camera.cameraId,
                    label: `${camera.name} · ${camera.serial}`,
                    title: `${camera.name} · ${camera.serial} · ${cameraBindingLabel(camera)}`,
                  }))}
                />
              </Flex>
              {selectedCamera && <div className="fm-diagnosis-start__camera-meta"><span>{selectedCamera.name} · {selectedCamera.serial}</span><span>{cameraBindingLabel(selectedCamera)}</span><span>{cameraSourceLabel(selectedCamera)}</span><Tag color={selectedCamera.status === "ONLINE" ? "green" : "blue"}>{selectedCamera.status === "ONLINE" ? "在线" : "已配置"}</Tag></div>}
              {cameraError && <Alert className="fm-diagnosis-start__inline-alert" type="warning" showIcon title="摄像头目录读取失败" description={cameraError} />}
              {!camerasLoading && !cameraError && !availableCameras.length && <Text type="secondary">当前农场没有可用摄像头，请先在设备与监控中完成设备映射。</Text>}
              {capturedImages.length > 0 && <div className="fm-diagnosis-start__captured-list">
                {capturedImages.map((image) => <div className="fm-diagnosis-start__captured-item" key={image.id}>
                  <img src={image.url} alt={image.name} />
                  <div><b>{image.name}</b><small>{image.fieldName || "当前地块"} · {image.pointId || "地图点已绑定"} · 已加入视觉 Evidence</small></div>
                  <Button type="text" danger icon={<DeleteOutlined />} aria-label={`移除${image.name}`} onClick={() => setCapturedImages((current) => current.filter((item) => item.id !== image.id))} disabled={phase !== "READY"} />
                </div>)}
              </div>}
            </div>
            <Text type="secondary">本地图片会进入视觉 Evidence 链路；摄像头选择用于确认当前区域的真实设备与空间上下文。当前已加入 {imageCount}/{MAX_IMAGE_COUNT} 张图片。</Text>
            <Input.TextArea className="fm-diagnosis-start__question" value={query} onChange={(event) => setQuery(event.target.value)} disabled={phase !== "READY"} autoSize={{ minRows: 3, maxRows: 6 }} placeholder="描述这次希望 AI 调查的问题" />
          </Card>

          <Card bordered={false} className="fm-diagnosis-start__agent-card" title={<span><RobotOutlined /> Agent 将使用</span>}>
            <div className="fm-diagnosis-start__agent-sources"><Tag>地块</Tag><Tag>天气</Tag><Tag>图像</Tag><Tag>历史记录</Tag><Tag>农业知识</Tag><Tag>历史案例</Tag></div>
            <Text type="secondary">Agent 会把诊断结果、Evidence 和推荐动作放在同一条运行链路里；需要写入 Operations 时仍会停在人工审批。</Text>
          </Card>

          {error && <Alert className="fm-diagnosis-start__error" type="error" showIcon icon={<WarningOutlined />} title={pendingDiagnosisId ? "Diagnosis 已保存，但 Agent 尚未创建" : "AI 调查未完成"} description={error} action={pendingDiagnosisId ? <Button size="small" onClick={() => void retryAgent()}>重试创建 Agent</Button> : undefined} />}
          {phase === "DIAGNOSING" && <Alert className="fm-diagnosis-start__progress" type="info" showIcon message="真实 Diagnosis 正在后台运行" description={diagnosisJobMessage || "页面可以保持打开；即使切换页面，任务也会继续执行。"} />}
          <Button className="fm-diagnosis-start__submit" size="large" type="primary" icon={phase === "READY" ? <SendOutlined /> : <LoadingOutlined />} disabled={!readyToStart} loading={phase !== "READY"} onClick={() => void startInvestigation()}>{phaseText(phase)}</Button>
        </main>

        <aside className="fm-diagnosis-start__side">
          <Card bordered={false} className="fm-diagnosis-start__card" title="任务链路">
            <div className="fm-diagnosis-start__timeline"><div className="is-active"><span>01</span><div><b>创建 Diagnosis</b><small>保存稳定 diagnosisId 与结构化证据</small></div></div><div><span>02</span><div><b>创建 Agent Run</b><small>让 Agent 读取 Diagnosis 并规划调查</small></div></div><div><span>03</span><div><b>进入分析工作区</b><small>查看 Evidence、Recommendation 与审批</small></div></div></div>
          </Card>
          <Card bordered={false} className="fm-diagnosis-start__card" title="发起前检查">
            <div className="fm-diagnosis-start__check"><CheckOutlined /><span>必须选择一个真实地块</span></div>
            <div className="fm-diagnosis-start__check"><CheckOutlined /><span>天气与历史数据会按可用性进入上下文</span></div>
            <div className="fm-diagnosis-start__check"><CheckOutlined /><span>图片是可选输入，但会增加视觉证据</span></div>
            <div className="fm-diagnosis-start__check"><DatabaseOutlined /><span>Agent 的写操作必须人工确认</span></div>
          </Card>
          {supportLoading && <Card bordered={false} className="fm-diagnosis-start__loading"><Spin size="small" /> 正在读取当前农场上下文…</Card>}
        </aside>
      </div>
    </div>
  );
}

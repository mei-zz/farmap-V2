import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Card,
  Flex,
  Spin,
  Space,
  Tabs,
  Tag,
  Typography,
} from "antd";
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CalendarOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  FileSearchOutlined,
  PaperClipOutlined,
  RobotOutlined,
  SendOutlined,
  ToolOutlined,
} from "@ant-design/icons";
import { useNavigate, useParams } from "react-router";
import MapContainer from "@/views/dashboard/Map";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import FieldContextCard from "@/components/commercial/FieldContextCard";
import StatusBadge from "@/components/commercial/StatusBadge";
import EvidenceCard from "@/components/commercial/EvidenceCard";
import RecommendationCard from "@/components/commercial/RecommendationCard";
import { getDemoDiagnosisViewModel, toDiagnosisViewModel } from "@/features/diagnosis/adapter";
import { analyzeDiagnosis, DiagnosisApiError, getDiagnosis, getExpertReview, ragMode, submitExpertReview } from "@/features/diagnosis/service";
import type { DiagnosisAnalysisResponse } from "@/features/diagnosis/contract";
import { agentMode, createAgentRun } from "@/features/agent/service";
import { normalizeOverviewEvidenceSources, openOverviewCopilot } from "@/features/overview/copilot/context";
import type { DiagnosisViewModel } from "@/types/diagnosis";
import { growthStageDisplayName } from "@/features/farm/labels";

const { Text, Title } = Typography;

const demoViewModel = getDemoDiagnosisViewModel();
const evidenceCategories = ["监控图像", "气象数据", "土壤数据", "农业知识", "历史案例", "空间证据"] as const;

function modelLabel(model?: string) {
  if (model === "qwen-plus-2025-07-28") return "Qwen Plus（2025-07-28）";
  if (model === "qwen-max") return "Qwen Max";
  if (model === "qwen-mt-flash") return "Qwen MT Flash";
  if (model === "qwen3.6-plus") return "Qwen3.6-Plus";
  if (model === "qwen3-vl-32b-thinking") return "Qwen3-VL-32B Thinking";
  if (model === "qwen3-vl-235b-a22b-thinking") return "Qwen3-VL-235B Thinking";
  return model || "未调用";
}

export default function DiagnosisDetail() {
  const navigate = useNavigate();
  const { id: routeDiagnosisId } = useParams<{ id: string }>();
  const isPersistedDetail = Boolean(routeDiagnosisId && routeDiagnosisId !== "a12-demo");
  const setContext = useAIContextStore((state) => state.setContext);
  const context = useAIContextStore((state) => state.context);
  const openCopilot = useCopilotStore((state) => state.open);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const setCopilotDraft = useCopilotStore((state) => state.setDraft);
  const [activeTab, setActiveTab] = useState("report");
  const [evidenceFilter, setEvidenceFilter] = useState<(typeof evidenceCategories)[number] | "全部">("全部");
  const [reviewSubmitted, setReviewSubmitted] = useState(false);
  const [reviewSubmitting, setReviewSubmitting] = useState(false);
  const [reviewError, setReviewError] = useState("");
  const [realResult, setRealResult] = useState<DiagnosisAnalysisResponse>();
  const [realRagLoading, setRealRagLoading] = useState(ragMode === "real");
  const [realRagError, setRealRagError] = useState("");
  const [useDemoFallback, setUseDemoFallback] = useState(ragMode === "mock" || routeDiagnosisId === "a12-demo");
  const [persistedLoading, setPersistedLoading] = useState(isPersistedDetail);
  const [persistedError, setPersistedError] = useState("");
  const [selectedRecommendationId, setSelectedRecommendationId] = useState<string>();

  useEffect(() => {
    if (isPersistedDetail || !useDemoFallback) return;
    setContext({
      currentFarm: { id: demoViewModel.farm.id, name: demoViewModel.farm.name, address: demoViewModel.farm.address },
      currentField: {
        id: demoViewModel.field.id,
        name: demoViewModel.field.name,
        area: demoViewModel.field.area,
        crop: demoViewModel.field.crop,
      },
      currentCrop: {
        name: demoViewModel.field.crop,
        variety: demoViewModel.field.variety,
        growthStage: demoViewModel.field.growthStage,
      },
      currentWeather: {
        summary: demoViewModel.weather.summary,
        temperature: demoViewModel.weather.temperature,
        precipitation: `未来 24 小时 ${demoViewModel.weather.rainfallForecast}`,
        updatedAt: demoViewModel.weather.updatedAt,
      },
      currentPhenology: {
        stage: demoViewModel.field.growthStage,
        progress: demoViewModel.field.phenologyProgress,
      },
      selectedDiagnosis: {
        id: demoViewModel.diagnosis.id,
        title: demoViewModel.diagnosis.title,
        severity: demoViewModel.diagnosis.severity,
        confidence: demoViewModel.diagnosis.confidence,
        summary: demoViewModel.diagnosis.summary,
      },
      selectedImages: demoViewModel.images.map((url, index) => ({ id: `a12-camera-${index + 1}`, name: `A-12 Camera-${index + 3}`, url })),
      currentLocation: { label: "A-12 地块中心", latitude: 30.051, longitude: 103.832 },
      selectedDevice: { id: "CAM-A12-03", name: "Camera-03", status: "online", location: "A-12 东侧" },
      currentPage: "/diagnosis/a12-demo",
    });
  }, [isPersistedDetail, setContext, useDemoFallback]);

  const reviewId = realResult?.diagnosisId ?? realResult?.run_id ?? (isPersistedDetail ? routeDiagnosisId : useDemoFallback ? demoViewModel.diagnosis.id : undefined);

  useEffect(() => {
    if (!reviewId) return;
    void getExpertReview(reviewId).then((review) => setReviewSubmitted(Boolean(review && review.status && review.status !== "PENDING"))).catch(() => undefined);
  }, [reviewId]);

  const openAgentAnalysis = async (recommendationId?: string) => {
    if (agentMode !== "real" || !isPersistedDetail || routeDiagnosisId === "a12-demo") { navigate("/analysis/a12-demo"); return; }
    const diagnosisId = realResult?.diagnosisId ?? routeDiagnosisId;
    const resolvedRecommendationId = recommendationId ?? selectedRecommendationId ?? realResult?.recommendations?.[0]?.id;
    if (!diagnosisId || diagnosisId === "a12-demo") return;
    try {
      setSelectedRecommendationId(resolvedRecommendationId);
      const run = await createAgentRun("分析当前诊断的综合原因并生成处理方案", undefined, "real", { diagnosisId, recommendationId: resolvedRecommendationId, intent: "基于已持久化诊断和选定建议生成安全的后续行动" });
      const query = new URLSearchParams({ diagnosisId });
      if (resolvedRecommendationId) query.set("recommendationId", resolvedRecommendationId);
      navigate(`/analysis/${run.runId}?${query.toString()}`);
    } catch {
      setPersistedError("真实 Agent 暂不可用，未创建写入动作。");
    }
  };

  useEffect(() => {
    if (!isPersistedDetail || !routeDiagnosisId) return;
    setPersistedLoading(true);
    setPersistedError("");
    void getDiagnosis(routeDiagnosisId)
      .then((response) => setRealResult(response))
      .catch((error: unknown) => setPersistedError(error instanceof DiagnosisApiError ? error.message : "诊断详情暂不可用"))
      .finally(() => setPersistedLoading(false));
  }, [isPersistedDetail, routeDiagnosisId]);

  useEffect(() => {
    if (isPersistedDetail || ragMode !== "real" || useDemoFallback || realResult) return;

    const imageUrls = context.selectedImages
      .map((image) => image.url)
      .filter((url): url is string => Boolean(url));
    setRealRagLoading(true);
      analyzeDiagnosis({
      fieldId: context.currentField?.id ?? "",
      farmId: context.currentFarm.id,
      imageUrls,
      query: "分析叶片黄化的原因并生成有证据依据的农事建议",
      topK: 5,
    })
      .then((response) => {
        setRealResult(response);
        setContext({
          selectedDiagnosis: {
            id: response.diagnosisId ?? response.run_id,
            title: response.diagnosis.title,
            severity: response.diagnosis.risk_level,
            confidence: Math.round(response.diagnosis.confidence * 100),
            summary: response.diagnosis.summary,
          },
        });
      })
      .catch((error: unknown) => {
      setRealRagError(error instanceof DiagnosisApiError ? error.message : "实时分析暂不可用");
      })
      .finally(() => setRealRagLoading(false));
  }, [context, isPersistedDetail, realResult, setContext, useDemoFallback]);

  if (isPersistedDetail && persistedLoading) {
    return <div className="fm-page fm-diagnosis-page"><Card bordered={false}><Flex align="center" gap={12}><Spin />正在加载已保存的诊断快照…</Flex></Card></div>;
  }

  if (isPersistedDetail && persistedError) {
    return <div className="fm-page fm-diagnosis-page"><Alert type="warning" showIcon message="诊断详情暂不可用" description={persistedError} action={<Button onClick={() => navigate("/diagnosis/new")}>发起新诊断</Button>} /></div>;
  }

  if (!isPersistedDetail && ragMode === "real" && !realResult && !useDemoFallback) {
    return (
      <div className="fm-page fm-diagnosis-page">
        <Flex className="fm-page-header" justify="space-between" align="center" gap={16} wrap>
          <div>
            <div className="fm-eyebrow">真实多模态检索</div>
            <Title level={2}>{context.currentField?.name ?? "当前地块"}诊断</Title>
          </div>
          <Tag color="blue">真实模式</Tag>
        </Flex>
        {realRagLoading ? (
          <Card bordered={false}>
            <Flex align="center" gap={12}>
              <Spin />
              正在连接诊断服务…
            </Flex>
          </Card>
        ) : (
          <Alert
            type="warning"
            showIcon
            message="实时分析暂不可用"
            description={realRagError || "诊断服务未返回结构化结果。"}
            action={
              <Space>
                <Button onClick={() => navigate("/diagnosis/new")}>去上传图像</Button>
                {routeDiagnosisId === "a12-demo" && <Button onClick={() => setUseDemoFallback(true)}>使用演示数据继续</Button>}
              </Space>
            }
          />
        )}
      </div>
    );
  }

  const realFallback = {
    farm: { id: context.currentFarm.id, name: context.currentFarm.name, address: context.currentFarm.address, coordinates: [context.currentLocation?.longitude ?? 0, context.currentLocation?.latitude ?? 0] as [number, number] },
    field: { id: context.currentField?.id ?? "UNASSIGNED", name: context.currentField?.name ?? "未分配地块", crop: context.currentField?.crop ?? context.currentCrop?.name ?? "—", variety: context.currentCrop?.variety ?? "—", area: context.currentField?.area ?? 0, growthStage: context.currentPhenology?.stage ?? context.currentCrop?.growthStage ?? "—", phenologyProgress: context.currentPhenology?.progress ?? 0, health: 0, ndvi: 0, soilMoisture: 0, rainfall14d: 0, elevation: 0, slope: 0, soilType: "不可用", status: "attention" as const, statusLabel: "待复核", latitude: context.currentLocation?.latitude ?? 0, longitude: context.currentLocation?.longitude ?? 0 },
    weather: { summary: context.currentWeather?.summary ?? "天气数据不可用", temperature: context.currentWeather?.temperature ?? "—", rainfallForecast: context.currentWeather?.precipitation ?? "—", updatedAt: context.currentWeather?.updatedAt ?? new Date().toISOString() },
    map: { crops: [], locations: [] },
    images: context.selectedImages.map((image) => image.url).filter((url): url is string => Boolean(url)),
    createdAt: new Date().toISOString(),
  };
  const viewModel: DiagnosisViewModel = realResult ? toDiagnosisViewModel(realResult, realFallback) : demoViewModel;
  const displayDiagnosis = viewModel.diagnosis;
  const displayEvidence = viewModel.evidence;
  const displayRecommendations = viewModel.recommendations;
  const visibleEvidence = evidenceFilter === "全部"
    ? displayEvidence
    : displayEvidence.filter((item) => item.category === evidenceFilter);
  const diagnosisTone =
    displayDiagnosis.severity === "high"
      ? "risk"
      : displayDiagnosis.severity === "medium"
        ? "attention"
        : "healthy";

  const openDiagnosisCopilot = () => {
    const copilotMode = viewModel.mode === "EMPTY" ? "UNAVAILABLE" : viewModel.mode;
    const evidence = normalizeOverviewEvidenceSources(displayEvidence.map((item) => item.category), copilotMode);
    openOverviewCopilot({
      contextType: "DIAGNOSIS",
      mode: copilotMode,
      page: `/diagnosis/${routeDiagnosisId ?? "new"}`,
      farm: { id: viewModel.farm.id, name: viewModel.farm.name },
      field: { id: viewModel.field.id, name: viewModel.field.name, crop: viewModel.field.crop, area: viewModel.field.area },
      title: displayDiagnosis.title,
      summary: displayDiagnosis.summary,
      diagnosis: { id: displayDiagnosis.id, title: displayDiagnosis.title, severity: displayDiagnosis.severity, confidence: displayDiagnosis.confidence },
      risk: { id: displayDiagnosis.id, title: displayDiagnosis.title, priority: displayDiagnosis.severityLabel, confidenceLabel: `${displayDiagnosis.confidence}% 诊断置信度`, affectedArea: `${viewModel.field.area} ha` },
      evidence: { labels: evidence.labels, count: viewModel.citedEvidenceCount, historyUnavailable: evidence.historyUnavailable },
      freshness: { updatedAt: viewModel.freshness.updatedAt },
    }, { setAIContext: setContext, setCopilotContext, setDraft: setCopilotDraft, open: openCopilot });
  };

  const submitReview = async () => {
    setReviewSubmitting(true);
    setReviewError("");
    try {
      await submitExpertReview(displayDiagnosis.id, {
        decision: "IN_REVIEW",
        aiDiagnosis: displayDiagnosis,
        evidence: displayEvidence,
        recommendations: displayRecommendations,
      });
      setReviewSubmitted(true);
    } catch {
      setReviewError("专家复核服务暂不可用，诊断状态未改变。");
    } finally {
      setReviewSubmitting(false);
    }
  };

  const evidenceLabelForFinding = (findingId: string) => {
    const finding = viewModel.findings.find((item) => item.id === findingId);
    if (!finding) return "证据关联暂不可用";
    const labels = finding.evidenceIds
      .map((id) => displayEvidence.find((item) => item.id === id)?.category)
      .filter(Boolean) as string[];
    return labels.length ? labels.join(" · ") : "证据关联暂不可用";
  };

  const reportContent = (
    <div className="fm-diagnosis-report">
      <Card className="fm-conclusion-card" bordered={false}>
        <Flex align="flex-start" gap={16}>
          <div className="fm-conclusion-card__icon">⌁</div>
          <div className="fm-conclusion-card__main">
            <div className="fm-eyebrow">主要诊断</div>
            <Flex align="center" gap={10} wrap>
              <Title level={4}>{displayDiagnosis.title}</Title>
              <StatusBadge tone={diagnosisTone} />
            </Flex>
            <Text type="secondary">{displayDiagnosis.summary}</Text>
            <div className="fm-diagnosis-summary-facts">
              <span>严重程度 <b>{displayDiagnosis.severityLabel}</b></span>
              <span>影响范围 <b>{viewModel.field.area} ha</b></span>
              <span>作物 / 阶段 <b>{viewModel.field.crop} · {growthStageDisplayName(viewModel.field.growthStage)}</b></span>
            </div>
            <div className="fm-confidence">
              <Flex justify="space-between">
                <span>诊断置信度</span>
                <b>{displayDiagnosis.confidence}%</b>
              </Flex>
              <span className="fm-confidence__track">
                <i style={{ width: `${displayDiagnosis.confidence}%` }} />
              </span>
            </div>
            {viewModel.uncertainty.length > 0 && <div className="fm-diagnosis-uncertainty" role="note">
              <b>需要进一步确认</b>
              {viewModel.uncertainty.map((item) => <span key={item.id}><strong>{item.label}</strong> · {item.detail}</span>)}
            </div>}
          </div>
          <div className="fm-alternatives">
            <b>其他可能性</b>
            {displayDiagnosis.alternatives.map((item) => (
              <div key={item.label}>
                <span>{item.label}</span>
                <strong>{item.value}%</strong>
              </div>
            ))}
          </div>
        </Flex>
      </Card>

      <Card
        className="fm-evidence-section"
        bordered={false}
        title={
          <span>
            <FileSearchOutlined /> 证据工作区 · 核心证据
          </span>
        }
        extra={
          <Button type="link" size="small" onClick={() => setActiveTab("evidence")}>
            打开证据工作区 <ArrowRightOutlined />
          </Button>
        }>
        <div className="fm-evidence-filter">
          <Button type={evidenceFilter === "全部" ? "primary" : "default"} size="small" aria-pressed={evidenceFilter === "全部"} onClick={() => setEvidenceFilter("全部")}>
            已引用 {viewModel.citedEvidenceCount}
          </Button>
          {evidenceCategories.map((label) => (
            <Button key={label} type={evidenceFilter === label ? "primary" : "default"} size="small" aria-pressed={evidenceFilter === label} onClick={() => setEvidenceFilter(label)}>
              {label} {displayEvidence.filter((item) => item.category === label).length}
            </Button>
          ))}
        </div>
        <div className="fm-evidence-grid">
          {visibleEvidence.slice(0, 5).map((evidence, index) => (
            <EvidenceCard
              key={evidence.id}
              evidence={evidence}
              index={index}
              categoryLabel={viewModel.mode === "DEMO" && evidence.category === "历史案例" ? "历史案例（演示）" : evidence.category}
            />
          ))}
        </div>
        <div className="fm-evidence-relations">
          <Flex justify="space-between" align="center">
            <Title level={5}>发现与证据的对应关系</Title>
            <Button type="link" size="small" onClick={() => setActiveTab("evidence")}>
              查看全部证据 <ArrowRightOutlined />
            </Button>
          </Flex>
          {viewModel.findings.slice(0, 4).map((finding, index) => (
            <button key={finding.id} onClick={() => setActiveTab("evidence")} aria-label={`查看发现 ${index + 1} 的证据`}>
              <span className="fm-relation-number">{index + 1}</span>
              <span>{finding.text}</span>
              <Tag>
                {evidenceLabelForFinding(finding.id)}
              </Tag>
              <ArrowRightOutlined />
            </button>
          ))}
        </div>
      </Card>

      <details className="fm-agent-trace">
        <summary>
          <span className="fm-agent-trace__check">
            <CheckCircleFilled />
          </span>
          <span>
            <b>运行记录（技术详情）</b>
            <small>{viewModel.mode === "DEMO" ? "演示分析完成 · 证据与结果已记录" : "实时诊断完成 · 结构化结果已记录"}</small>
          </span>
          <span className="fm-agent-trace__action">展开查看⌄</span>
        </summary>
        <div className="fm-agent-trace__detail">
          检索、证据和结果摘要已记录；页面不展示模型内部私有推理。需要模型路由、证据 ID 或运行元数据时，请打开“技术详情”标签。
        </div>
      </details>
    </div>
  );

  const evidenceContent = (
    <Card bordered={false} title="证据工作区 · 证据链" className="fm-tab-card">
      <Text type="secondary" className="fm-evidence-tab-intro">按模态查看 AI 实际引用的图像、气象、土壤、知识、历史案例和空间证据；展开卡片可查看支持的 Finding。</Text>
      <div className="fm-evidence-filter fm-evidence-filter--tab">
        <Button type={evidenceFilter === "全部" ? "primary" : "default"} size="small" aria-pressed={evidenceFilter === "全部"} onClick={() => setEvidenceFilter("全部")}>全部 {viewModel.citedEvidenceCount}</Button>
        {evidenceCategories.map((label) => <Button key={label} type={evidenceFilter === label ? "primary" : "default"} size="small" aria-pressed={evidenceFilter === label} onClick={() => setEvidenceFilter(label)}>{label} {displayEvidence.filter((item) => item.category === label).length}</Button>)}
      </div>
      <div className="fm-evidence-grid fm-evidence-grid--expanded">
          {visibleEvidence.map((evidence, index) => (
            <EvidenceCard
              key={evidence.id}
              evidence={evidence}
              index={index}
              categoryLabel={viewModel.mode === "DEMO" && evidence.category === "历史案例" ? "历史案例（演示）" : evidence.category}
            />
          ))}
      </div>
    </Card>
  );
  const technicalContent = (
    <Card bordered={false} title="技术详情" className="fm-tab-card">
      {realResult && (
        <div className="fm-input-card" style={{ marginBottom: 16 }}>
          <Flex justify="space-between" align="center" wrap gap={8}>
            <Title level={5} style={{ margin: 0 }}>模型路由</Title>
            <Tag color={realResult.metadata.escalated ? "orange" : "blue"}>
              {realResult.metadata.escalated ? "已升级" : "自动路由"}
            </Tag>
          </Flex>
          <div><span>主模型</span><b>{modelLabel(realResult.metadata.primaryModel || realResult.metadata.model)}</b></div>
          <div><span>已选模型</span><b>{modelLabel(realResult.metadata.selectedModel || realResult.metadata.model)}</b></div>
          <div><span>路由原因</span><b>{realResult.metadata.routingReason || "任务策略"}</b></div>
          {realResult.metadata.escalatedModel && <div><span>升级模型</span><b>{modelLabel(realResult.metadata.escalatedModel)}</b></div>}
          {realResult.metadata.fallbackChain?.length ? <div><span>备用模型链</span><b>{realResult.metadata.fallbackChain.map(modelLabel).join(" → ")}</b></div> : null}
          <div><span>Token 数量</span><b>{realResult.metadata.totalTokens ?? "—"}</b></div>
          <div><span>运行模式</span><b>真实</b></div>
          <div><span>文本向量模型</span><b>{realResult.metadata.textEmbeddingModel || "备用模式"} · 本地 · {realResult.metadata.textEmbeddingDimension ?? 0}-维</b></div>
          <div><span>图像向量模型</span><b>{realResult.metadata.imageEmbeddingModel || "暂不可用"} · 本地 · {realResult.metadata.imageEmbeddingDimension ?? 0}-维</b></div>
          <div><span>Milvus</span><b>{realResult.metadata.milvusEndpoint || "—"} · {realResult.metadata.milvusCollection || "—"} · {realResult.metadata.milvusStatus || "—"}</b></div>
          <div><span>历史病例检索器</span><b>{realResult.metadata.historicalRetriever || "未接入"}</b></div>
          <div><span>处理耗时</span><b>检索 {realResult.metadata.retrievalLatencyMs ?? "—"} ms · 融合 {realResult.metadata.fusionLatencyMs ?? "—"} ms · 模型 {realResult.metadata.generationLatencyMs ?? "—"} ms · 总计 {realResult.metadata.totalLatencyMs ?? "—"} ms</b></div>
        </div>
      )}
      <div className="fm-technical-list">
        {viewModel.technical.toolNames.map((tool, index) => (
          <div key={tool}>
            <span>{index + 1}</span>
            <b>{tool}</b>
            <StatusBadge tone="completed" label="已完成" />
          </div>
        ))}
      </div>
      {!realResult && <Text type="secondary">演示模式仅展示流程级技术摘要；真实模型、检索分数和 Evidence ID 会在实时结果返回后显示。</Text>}
    </Card>
  );

  return (
    <div className="fm-page fm-diagnosis-page">
      <Flex className="fm-breadcrumb" align="center" gap={6}>
        <Button type="link" size="small" icon={<ArrowLeftOutlined />} onClick={() => navigate("/overview")}>
          AI 农情诊断
        </Button>
        <span>/</span>
        <Text type="secondary">{viewModel.field.name}</Text>
      </Flex>
      <Flex className="fm-page-header fm-diagnosis-header" justify="space-between" align="flex-start" gap={16} wrap>
        <div>
          <Flex align="center" gap={12} wrap>
            <Title level={2}>{viewModel.field.name} 诊断</Title>
            <StatusBadge tone="completed" label="诊断完成" />
            <Tag color={viewModel.mode === "DEMO" ? "gold" : "blue"}>{viewModel.mode === "DEMO" ? "流程演示" : "真实多模态检索"}</Tag>
            <Tag icon={<CalendarOutlined />}>{displayDiagnosis.createdAt}</Tag>
            <Tag icon={<PaperClipOutlined />}>{displayDiagnosis.sourceCount} 类数据源</Tag>
            <Tag icon={<FileSearchOutlined />}>{viewModel.citedEvidenceCount} 条已引用证据</Tag>
            {viewModel.retrievedEvidenceCount !== undefined && viewModel.retrievedEvidenceCount > viewModel.citedEvidenceCount && <Tag>{viewModel.retrievedEvidenceCount} 条检索结果</Tag>}
            <Tag color={reviewSubmitted ? "green" : "orange"} icon={<ClockCircleOutlined />}>
              {reviewSubmitted ? "已提交复核" : "专家待复核"}
            </Tag>
          </Flex>
            <Text type="secondary">基于当前地块的多模态证据形成可复核的农业诊断与下一步建议。</Text>
        </div>
        <Space wrap>
          <Button type={activeTab === "report" ? "primary" : "default"} onClick={() => setActiveTab("report")}>
            诊断报告
          </Button>
          <Button type={activeTab === "evidence" ? "primary" : "default"} onClick={() => setActiveTab("evidence")}>
            证据
          </Button>
          <Button type={activeTab === "technical" ? "primary" : "default"} onClick={() => setActiveTab("technical")}>
            技术详情
          </Button>
        </Space>
      </Flex>

      <div className="fm-diagnosis-grid">
        <aside className="fm-diagnosis-left">
          <Card bordered={false} title="地块基本信息">
            <div className="fm-diagnosis-map">
              <MapContainer
                center={viewModel.farm.coordinates}
                zoom={13}
                crops={viewModel.map.crops}
                farmLocations={viewModel.map.locations}
                infoKey="growth"
                modeKey="crop"
                slider={{ value: { left: 0, right: 100 }, scale: { min: 0, max: 100 }, decimal: false }}
                readOnly
              />
              <div className="fm-diagnosis-map__label">{viewModel.field.id}</div>
            </div>
            <FieldContextCard field={viewModel.field} />
            <div className="fm-input-card">
              <Title level={5}>多模态输入数据</Title>
              {viewModel.inputSources.map((source, index) => (
                <div key={source.id} role="listitem">
                  <span className={`fm-input-icon fm-input-icon--${index % 2 === 0 ? "blue" : "green"}`}>
                    <PaperClipOutlined />
                  </span>
                  <b>{source.label}</b>
                  <small>{source.available ? source.countLabel : "暂不可用"}</small>
                </div>
              ))}
            </div>
          </Card>
        </aside>
        <main className="fm-diagnosis-center">
          <Tabs
            activeKey={activeTab}
            onChange={setActiveTab}
            items={[
              { key: "report", label: "诊断报告", children: reportContent },
              { key: "evidence", label: "证据", children: evidenceContent },
              { key: "technical", label: "技术详情", children: technicalContent },
            ]}
          />
        </main>
        <aside className="fm-diagnosis-right">
          <Card
            bordered={false}
            title="专家复核"
            extra={<StatusBadge tone={reviewSubmitted ? "completed" : "pending"} label={reviewSubmitted ? "已提交" : "待复核"} />}
          >
              <Text type="secondary">建议由农业专家对诊断结果进行复核，以提高结论可信度。</Text>
            <div className="fm-review-status">
              <div className="fm-review-avatar">◌</div>
              <div>
                <small>当前状态</small>
                <b>{reviewSubmitted ? "已提交专家复核" : "待专家复核"}</b>
              </div>
            </div>
            <Button block type="primary" icon={<SendOutlined />} onClick={() => void submitReview()} loading={reviewSubmitting} disabled={reviewSubmitted}>
              {reviewSubmitted ? "已提交专家复核" : "提交专家复核"}
            </Button>
            {reviewError && <Text type="danger" className="fm-review-error" role="alert">{reviewError}</Text>}
          </Card>
          <Card bordered={false} title="农事建议" extra={<Text type="secondary">基于诊断结果生成</Text>}>
            {displayRecommendations.map((item) => (
              <div key={item.id} className="fm-diagnosis-recommendation">
                <RecommendationCard title={item.title} priority={item.priority} tone={item.tone} detail={item.detail} />
                <div className="fm-diagnosis-recommendation__evidence"><FileSearchOutlined /> {item.evidenceStatus === "LINKED" ? `支持证据 · ${item.evidenceIds.length} 条` : "逐条证据关联暂不可用（当前数据未提供）"}</div>
                {isPersistedDetail && <Button type="link" size="small" onClick={() => void openAgentAnalysis(item.id)}>让 Agent 处理此建议</Button>}
              </div>
            ))}
          </Card>
          <Card bordered={false} title="相关操作">
            <div className="fm-action-grid">
              <Button icon={<FileSearchOutlined />} onClick={() => setActiveTab("evidence")}>查看证据</Button>
              <Button icon={<CalendarOutlined />} onClick={() => navigate("/operations/tasks")}>查看运营任务</Button>
            </div>
            <Button className="fm-deep-analysis" block type="primary" icon={<ToolOutlined />} onClick={() => void openAgentAnalysis()}>
              让 AI 分析处理方案 <ArrowRightOutlined />
            </Button>
            <Button className="fm-deep-analysis" block icon={<RobotOutlined />} onClick={openDiagnosisCopilot}>
              Ask AI 解释诊断
            </Button>
          </Card>
        </aside>
      </div>
    </div>
  );
}

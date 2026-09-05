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
  FileAddOutlined,
  FileSearchOutlined,
  FolderAddOutlined,
  MoreOutlined,
  PaperClipOutlined,
  SendOutlined,
  ToolOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router";
import {
  demoAgentTools,
  demoDiagnosis,
  demoEvidence,
  demoFarm,
  demoFarmLocations,
  demoMapCrops,
  demoPrimaryField,
  demoRecommendations,
  demoWeather,
} from "@/demo";
import MapContainer from "@/views/dashboard/Map";
import { useAIContextStore } from "@/store/aiContext";
import FieldContextCard from "@/components/commercial/FieldContextCard";
import StatusBadge from "@/components/commercial/StatusBadge";
import EvidenceCard from "@/components/commercial/EvidenceCard";
import RecommendationCard from "@/components/commercial/RecommendationCard";
import { toDiagnosisViewModel } from "@/features/diagnosis/adapter";
import { analyzeDiagnosis, DiagnosisApiError, ragMode } from "@/features/diagnosis/service";
import type { DiagnosisAnalysisResponse } from "@/features/diagnosis/contract";

const { Text, Title } = Typography;

function modelLabel(model?: string) {
  if (model === "qwen3.6-plus") return "Qwen3.6-Plus";
  if (model === "qwen3-vl-32b-thinking") return "Qwen3-VL-32B Thinking";
  if (model === "qwen3-vl-235b-a22b-thinking") return "Qwen3-VL-235B Thinking";
  return model || "未调用";
}

export default function DiagnosisDetail() {
  const navigate = useNavigate();
  const setContext = useAIContextStore((state) => state.setContext);
  const context = useAIContextStore((state) => state.context);
  const [activeTab, setActiveTab] = useState("report");
  const [reviewSubmitted, setReviewSubmitted] = useState(false);
  const [realResult, setRealResult] = useState<DiagnosisAnalysisResponse>();
  const [realRagLoading, setRealRagLoading] = useState(ragMode === "real");
  const [realRagError, setRealRagError] = useState("");
  const [useDemoFallback, setUseDemoFallback] = useState(ragMode === "mock");

  useEffect(() => {
    setContext({
      currentFarm: { id: demoFarm.id, name: demoFarm.name, address: demoFarm.address },
      currentField: {
        id: demoPrimaryField.id,
        name: demoPrimaryField.name,
        area: demoPrimaryField.area,
        crop: demoPrimaryField.crop,
      },
      currentCrop: {
        name: demoPrimaryField.crop,
        variety: demoPrimaryField.variety,
        growthStage: demoPrimaryField.growthStage,
      },
      currentWeather: {
        summary: demoWeather.summary,
        temperature: demoWeather.temperature,
        precipitation: `未来 24 小时 ${demoWeather.rainfallForecast}`,
        updatedAt: demoWeather.updatedAt,
      },
      currentPhenology: {
        stage: demoPrimaryField.growthStage,
        progress: demoPrimaryField.phenologyProgress,
      },
      selectedDiagnosis: {
        id: demoDiagnosis.id,
        title: demoDiagnosis.title,
        severity: demoDiagnosis.severity,
        confidence: demoDiagnosis.confidence,
        summary: demoDiagnosis.summary,
      },
      currentPage: "/diagnosis/a12-demo",
    });
  }, [setContext]);

  useEffect(() => {
    if (ragMode !== "real" || useDemoFallback || realResult) return;

    const imageUrls = context.selectedImages
      .map((image) => image.url)
      .filter((url): url is string => Boolean(url));
    if (imageUrls.length === 0) {
      setRealRagLoading(false);
      setRealRagError("实时分析暂不可用：当前上下文没有可提交的摄像头图像。");
      return;
    }

    setRealRagLoading(true);
    analyzeDiagnosis({
      fieldId: context.currentField?.id ?? demoPrimaryField.id,
      farmId: context.currentFarm.id,
      crop: context.currentCrop?.name ?? demoPrimaryField.crop,
      growthStage: context.currentCrop?.growthStage ?? demoPrimaryField.growthStage,
      imageUrls,
      weather: {
        rainfall14d: demoPrimaryField.rainfall14d,
        summary: context.currentWeather?.summary ?? demoWeather.summary,
        temperature: context.currentWeather?.temperature ?? demoWeather.temperature,
        observedAt: context.currentWeather?.updatedAt ?? demoWeather.updatedAt,
      },
      query: "分析叶片黄化的原因并生成有证据依据的农事建议",
      topK: 5,
    })
      .then((response) => {
        setRealResult(response);
        setContext({
          selectedDiagnosis: {
            id: response.run_id,
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
  }, [context, realResult, setContext, useDemoFallback]);

  if (ragMode === "real" && !realResult && !useDemoFallback) {
    return (
      <div className="fm-page fm-diagnosis-page">
        <Flex className="fm-page-header" justify="space-between" align="center" gap={16} wrap>
          <div>
            <div className="fm-eyebrow">REAL MULTIMODAL RAG</div>
            <Title level={2}>A-12 地块诊断</Title>
          </div>
          <Tag color="blue">REAL MODE</Tag>
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
                <Button onClick={() => setUseDemoFallback(true)}>使用演示数据继续</Button>
              </Space>
            }
          />
        )}
      </div>
    );
  }

  const viewModel = realResult
    ? toDiagnosisViewModel(realResult, {
        fieldId: demoPrimaryField.id,
        createdAt: demoDiagnosis.createdAt,
      })
    : { diagnosis: demoDiagnosis, evidence: demoEvidence, recommendations: demoRecommendations };
  const displayDiagnosis = viewModel.diagnosis;
  const displayEvidence = viewModel.evidence;
  const displayRecommendations = viewModel.recommendations;
  const diagnosisTone =
    displayDiagnosis.severity === "high"
      ? "risk"
      : displayDiagnosis.severity === "medium"
        ? "attention"
        : "healthy";

  const reportContent = (
    <div className="fm-diagnosis-report">
      <Card className="fm-conclusion-card" bordered={false}>
        <Flex align="flex-start" gap={16}>
          <div className="fm-conclusion-card__icon">⌁</div>
          <div className="fm-conclusion-card__main">
            <Flex align="center" gap={10} wrap>
              <Title level={4}>{displayDiagnosis.title}</Title>
              <StatusBadge tone={diagnosisTone} />
            </Flex>
            <Text type="secondary">{displayDiagnosis.summary}</Text>
            <div className="fm-confidence">
              <Flex justify="space-between">
                <span>置信度</span>
                <b>{displayDiagnosis.confidence}%</b>
              </Flex>
              <span className="fm-confidence__track">
                <i style={{ width: `${displayDiagnosis.confidence}%` }} />
              </span>
            </div>
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
            <FileSearchOutlined /> 核心证据
          </span>
        }
        extra={
          <Button type="link" size="small" onClick={() => setActiveTab("evidence")}>
            按相关性排序 <ArrowRightOutlined />
          </Button>
        }>
        <div className="fm-evidence-filter">
          <Button type="primary" size="small">
            全部 {displayDiagnosis.evidenceCount}
          </Button>
          {["监控图像", "气象数据", "农业知识", "历史案例", "空间证据"].map((label) => (
            <Button key={label} size="small">
              {label} {displayEvidence.filter((item) => item.category === label).length}
            </Button>
          ))}
        </div>
        <div className="fm-evidence-grid">
          {displayEvidence.slice(0, 5).map((evidence, index) => (
            <EvidenceCard key={evidence.id} evidence={evidence} index={index} />
          ))}
        </div>
        <div className="fm-evidence-relations">
          <Flex justify="space-between" align="center">
            <Title level={5}>证据与结论的对应关系</Title>
            <Button type="link" size="small" onClick={() => setActiveTab("evidence")}>
              查看全部证据 <ArrowRightOutlined />
            </Button>
          </Flex>
          {displayEvidence.slice(0, 4).map((evidence, index) => (
            <button key={evidence.id} onClick={() => setActiveTab("evidence")}>
              <span className="fm-relation-number">{index + 1}</span>
              <span>{evidence.claim}</span>
              <Tag>
                {evidence.category} [{index + 1}]
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
            <b>Agent Trace</b>
            <small>
              {realResult
                ? `真实检索完成 · run_id ${realResult.run_id} · ${displayDiagnosis.evidenceCount} 条证据`
                : `演示分析完成 · 使用 7 个工具 · 检索 ${displayDiagnosis.evidenceCount} 条证据 · 耗时 2.34 s`}
            </small>
          </span>
          <span className="fm-agent-trace__action">查看运行详情⌄</span>
        </summary>
        <div className="fm-agent-trace__detail">
          Tool Call、Data Retrieval、Evidence、Result Summary 已记录；不展示模型内部推理。
        </div>
      </details>
    </div>
  );

  const evidenceContent = (
    <Card bordered={false} title="证据链" className="fm-tab-card">
      <div className="fm-evidence-grid fm-evidence-grid--expanded">
        {displayEvidence.map((evidence, index) => (
          <EvidenceCard key={evidence.id} evidence={evidence} index={index} />
        ))}
      </div>
    </Card>
  );
  const technicalContent = (
    <Card bordered={false} title="技术详情" className="fm-tab-card">
      {realResult && (
        <div className="fm-input-card" style={{ marginBottom: 16 }}>
          <Flex justify="space-between" align="center" wrap gap={8}>
            <Title level={5} style={{ margin: 0 }}>Model Routing</Title>
            <Tag color={realResult.metadata.escalated ? "orange" : "blue"}>
              {realResult.metadata.escalated ? "已升级" : "自动路由"}
            </Tag>
          </Flex>
          <div><span>Primary Model</span><b>{modelLabel(realResult.metadata.primaryModel || realResult.metadata.model)}</b></div>
          <div><span>Selected Model</span><b>{modelLabel(realResult.metadata.selectedModel || realResult.metadata.model)}</b></div>
          <div><span>Route</span><b>{realResult.metadata.routingReason || "task_policy"}</b></div>
          {realResult.metadata.escalatedModel && <div><span>Escalated to</span><b>{modelLabel(realResult.metadata.escalatedModel)}</b></div>}
          {realResult.metadata.fallbackChain?.length ? <div><span>Fallback</span><b>{realResult.metadata.fallbackChain.map(modelLabel).join(" → ")}</b></div> : null}
          <div><span>Tokens</span><b>{realResult.metadata.totalTokens ?? "—"}</b></div>
          <div><span>Runtime Mode</span><b>REAL</b></div>
          <div><span>Text Embedding</span><b>{realResult.metadata.textEmbeddingModel || "FALLBACK"} · LOCAL · {realResult.metadata.textEmbeddingDimension ?? 0}-d</b></div>
          <div><span>Image Embedding</span><b>{realResult.metadata.imageEmbeddingModel || "UNAVAILABLE"} · LOCAL · {realResult.metadata.imageEmbeddingDimension ?? 0}-d</b></div>
          <div><span>Milvus</span><b>{realResult.metadata.milvusEndpoint || "—"} · {realResult.metadata.milvusCollection || "—"} · {realResult.metadata.milvusStatus || "—"}</b></div>
          <div><span>Historical Retriever</span><b>{realResult.metadata.historicalRetriever || "BLOCKED"}</b></div>
          <div><span>Latency</span><b>retrieval {realResult.metadata.retrievalLatencyMs ?? "—"} ms · fusion {realResult.metadata.fusionLatencyMs ?? "—"} ms · model {realResult.metadata.generationLatencyMs ?? "—"} ms · total {realResult.metadata.totalLatencyMs ?? "—"} ms</b></div>
        </div>
      )}
      <div className="fm-technical-list">
        {demoAgentTools.map((tool, index) => (
          <div key={tool.id}>
            <span>{index + 1}</span>
            <b>{tool.name}</b>
            <StatusBadge tone="completed" label="已完成" />
          </div>
        ))}
      </div>
    </Card>
  );

  return (
    <div className="fm-page fm-diagnosis-page">
      <Flex className="fm-breadcrumb" align="center" gap={6}>
        <Button type="link" size="small" icon={<ArrowLeftOutlined />} onClick={() => navigate("/overview")}>
          AI 农情诊断
        </Button>
        <span>/</span>
        <Text type="secondary">A-12 地块</Text>
      </Flex>
      <Flex className="fm-page-header fm-diagnosis-header" justify="space-between" align="flex-start" gap={16} wrap>
        <div>
          <Flex align="center" gap={12} wrap>
            <Title level={2}>A-12 地块诊断</Title>
            <StatusBadge tone="completed" label="诊断完成" />
            {realResult && <Tag color="blue">REAL RAG</Tag>}
            <Tag icon={<CalendarOutlined />}>{displayDiagnosis.createdAt}</Tag>
            <Tag icon={<PaperClipOutlined />}>{displayDiagnosis.sourceCount} 类数据源</Tag>
            <Tag icon={<FileSearchOutlined />}>{displayDiagnosis.evidenceCount} 条证据</Tag>
            <Tag color={reviewSubmitted ? "green" : "orange"} icon={<ClockCircleOutlined />}>
              {reviewSubmitted ? "已提交复核" : "专家待复核"}
            </Tag>
          </Flex>
          <Text type="secondary">基于卫星影像、监控图像、气象数据、土壤数据和农业知识的多模态 AI 诊断。</Text>
        </div>
        <Space>
          <Button type={activeTab === "report" ? "primary" : "default"} onClick={() => setActiveTab("report")}>
            诊断报告
          </Button>
          <Button type={activeTab === "evidence" ? "primary" : "default"} onClick={() => setActiveTab("evidence")}>
            证据
          </Button>
          <Button type={activeTab === "technical" ? "primary" : "default"} onClick={() => setActiveTab("technical")}>
            技术详情
          </Button>
          <Button type="primary" icon={<FileAddOutlined />}>
            生成报告
          </Button>
          <Button icon={<MoreOutlined />} aria-label="更多操作" />
        </Space>
      </Flex>

      <div className="fm-diagnosis-grid">
        <aside className="fm-diagnosis-left">
          <Card bordered={false} title="地块基本信息" extra={<Button type="link" size="small">编辑</Button>}>
            <div className="fm-diagnosis-map">
              <MapContainer
                center={demoFarm.coordinates}
                zoom={13}
                crops={demoMapCrops}
                farmLocations={demoFarmLocations}
                infoKey="growth"
                modeKey="crop"
                slider={{ value: { left: 0, right: 100 }, scale: { min: 0, max: 100 }, decimal: false }}
                readOnly
              />
              <div className="fm-diagnosis-map__label">A-12</div>
            </div>
            <FieldContextCard field={demoPrimaryField} />
            <div className="fm-input-card">
              <Title level={5}>多模态输入数据</Title>
              {[
                ["监控图像", "8 张", "fm-input-icon--blue"],
                ["卫星影像", "2 景", "fm-input-icon--green"],
                ["气象数据", "14 天", "fm-input-icon--sky"],
                ["土壤数据", "1 组", "fm-input-icon--dark"],
                ["历史诊断", "3 条", "fm-input-icon--cyan"],
              ].map(([label, value, tone]) => (
                <div key={label}>
                  <span className={`fm-input-icon ${tone}`}>
                    <PaperClipOutlined />
                  </span>
                  <b>{label}</b>
                  <small>{value}</small>
                  <ArrowRightOutlined />
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
            <Button block type="primary" icon={<SendOutlined />} onClick={() => setReviewSubmitted(true)} disabled={reviewSubmitted}>
              提交专家复核
            </Button>
          </Card>
          <Card bordered={false} title="农事建议" extra={<Text type="secondary">基于诊断结果生成</Text>}>
            {displayRecommendations.map((item) => (
              <RecommendationCard key={item.title} {...item} />
            ))}
          </Card>
          <Card bordered={false} title="相关操作">
            <div className="fm-action-grid">
              <Button icon={<FileAddOutlined />}>生成完整诊断报告</Button>
              <Button icon={<CalendarOutlined />}>创建巡检任务</Button>
              <Button icon={<FolderAddOutlined />}>加入历史案例库</Button>
            </div>
            <Button className="fm-deep-analysis" block icon={<ToolOutlined />} onClick={() => navigate("/analysis/a12-demo")}>
              查看 Agent 深度分析 <ArrowRightOutlined />
            </Button>
          </Card>
        </aside>
      </div>
    </div>
  );
}

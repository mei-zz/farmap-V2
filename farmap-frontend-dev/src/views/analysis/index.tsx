import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Card, Flex, Space, Tag, Typography } from "antd";
import { ArrowLeftOutlined, ClockCircleOutlined, CloudOutlined, FileSearchOutlined, PlayCircleFilled, RobotOutlined } from "@ant-design/icons";
import { useNavigate, useParams } from "react-router";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import FieldContextCard from "@/components/commercial/FieldContextCard";
import { agentMode, AgentRuntimeUnavailableError, approveAgentAction, cancelAgentRun, createAgentRun, getAgentRun, pollAgentRun, rejectAgentAction } from "@/features/agent/service";
import type { AgentRun } from "@/features/agent/contract";
import { getDemoAgentWorkspaceViewModel, toAgentWorkspaceViewModel } from "@/features/agent-workspace/adapter";
import {
  ActionProposalPanel,
  AgentWorkspaceHeader,
  AnalysisProgress,
  ApprovalPanel,
  EvidenceUsedPanel,
  ExecutionResultPanel,
  FindingsPanel,
  MultimodalOverviewPanel,
  TechnicalDetailsPanel,
} from "@/features/agent-workspace/components/BusinessPanels";
import { normalizeOverviewEvidenceSources, openOverviewCopilot } from "@/features/overview/copilot/context";
import type { AgentWorkspaceFallback, AgentWorkspaceRuntimeState, AgentWorkspaceViewModel } from "@/types/agent-workspace";

const { Text, Title } = Typography;

function dataSourceTag(source: AgentWorkspaceViewModel["dataSources"][number]) {
  if (source.status === "AVAILABLE") return <Tag color="green">已接入</Tag>;
  if (source.status === "EMPTY") return <Tag color="gold">暂无结果</Tag>;
  return <Tag>暂不可用</Tag>;
}

const demoFallbackViewModel = getDemoAgentWorkspaceViewModel({ mode: "DEMO", runStatus: "pending", currentStep: -1, elapsedSeconds: 0 });

function buildFallback(context: ReturnType<typeof useAIContextStore.getState>["context"], realRoute = false): AgentWorkspaceFallback {
  const baseField = realRoute ? { ...demoFallbackViewModel.field, id: "UNASSIGNED", name: "当前地块", crop: "—", variety: "—", growthStage: "—" } : demoFallbackViewModel.field;
  const field = {
    ...baseField,
    id: context.currentField?.id ?? baseField.id,
    name: context.currentField?.name ?? baseField.name,
    area: context.currentField?.area ?? baseField.area,
    crop: context.currentField?.crop ?? context.currentCrop?.name ?? baseField.crop,
    variety: context.currentCrop?.variety ?? baseField.variety,
    growthStage: context.currentCrop?.growthStage ?? context.currentPhenology?.stage ?? baseField.growthStage,
  };
  return {
    farm: realRoute ? { id: 0, name: "当前农场", address: "—" } : { id: context.currentFarm.id, name: context.currentFarm.name, address: context.currentFarm.address },
    field,
    diagnosis: context.selectedDiagnosis ? { ...context.selectedDiagnosis } : realRoute ? undefined : demoFallbackViewModel.diagnosis,
    goal: context.selectedDiagnosis?.title ? `分析${field.name}的${context.selectedDiagnosis.title}并生成处理方案` : realRoute ? "正在加载真实 Diagnosis 上下文" : demoFallbackViewModel.goal,
    createdAt: context.currentWeather?.updatedAt ?? (realRoute ? new Date(0).toISOString() : demoFallbackViewModel.createdAt),
    updatedAt: context.currentWeather?.updatedAt ?? (realRoute ? new Date(0).toISOString() : demoFallbackViewModel.updatedAt),
    images: realRoute ? [] : context.selectedImages.map((image) => image.url).filter((url): url is string => Boolean(url)),
    weather: {
      summary: context.currentWeather?.summary ?? (realRoute ? "等待服务器返回天气上下文" : demoFallbackViewModel.weather.summary),
      temperature: context.currentWeather?.temperature ?? (realRoute ? "—" : demoFallbackViewModel.weather.temperature),
      updatedAt: context.currentWeather?.updatedAt ?? (realRoute ? new Date(0).toISOString() : demoFallbackViewModel.weather.updatedAt),
    },
    map: realRoute ? { crops: [], locations: [] } : demoFallbackViewModel.map,
  };
}

function RealRunLoading({ fallback, error, onRetry, onBack }: { fallback: AgentWorkspaceFallback; error?: string; onRetry: () => void; onBack: () => void }) {
  return (
    <div className="fm-page fm-agent-page fm-agent-command-center">
      <Flex className="fm-page-header" justify="space-between" align="flex-start" gap={16} wrap>
        <div>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={onBack}>返回 AI Diagnosis</Button>
          <div className="fm-eyebrow">多模态农业智能体</div>
          <Title level={2}>农业 Agent 调查台</Title>
          <Text type="secondary">正在同步 {fallback.field.name} 的实时步骤与检索证据。</Text>
        </div>
        <Tag color="blue">真实 Agent</Tag>
      </Flex>
      {error ? <Alert type="warning" showIcon message="Agent Runtime 暂不可用" description={error} action={<Space><Button onClick={onRetry}>重新发起分析</Button><Button onClick={onBack}>返回诊断</Button></Space>} /> : <Card bordered={false}><Flex align="center" gap={10}><ClockCircleOutlined /> 正在同步真实运行状态、分析步骤和 Evidence…</Flex></Card>}
    </div>
  );
}

export default function AgentWorkspace() {
  const navigate = useNavigate();
  const { runId: routeRunId } = useParams<{ runId: string }>();
  const aiContext = useAIContextStore((state) => state.context);
  const setContext = useAIContextStore((state) => state.setContext);
  const openCopilot = useCopilotStore((state) => state.open);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const setCopilotDraft = useCopilotStore((state) => state.setDraft);
  const isRealRunRoute = agentMode === "real" && Boolean(routeRunId && routeRunId !== "a12-demo");
  const fallback = useMemo(() => buildFallback(aiContext, isRealRunRoute), [aiContext, isRealRunRoute]);
  const initializedRouteRef = useRef<string | null>(null);
  const [runStatus, setRunStatus] = useState<AgentWorkspaceRuntimeState["runStatus"]>("pending");
  const [currentStep, setCurrentStep] = useState(-1);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [realRun, setRealRun] = useState<AgentRun | null>(null);
  const [runtimeError, setRuntimeError] = useState<string | null>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const elapsedRef = useRef(0);

  const demoViewModel = useMemo(() => getDemoAgentWorkspaceViewModel({ mode: "DEMO", runStatus, currentStep, elapsedSeconds }), [currentStep, elapsedSeconds, runStatus]);
  useEffect(() => {
    const routeKey = routeRunId || "a12-demo";
    if (initializedRouteRef.current === routeKey) return;
    initializedRouteRef.current = routeKey;
    const initialFallback = fallback;
    setContext({
      currentFarm: initialFallback.farm,
      currentField: { id: initialFallback.field.id, name: initialFallback.field.name, area: initialFallback.field.area, crop: initialFallback.field.crop },
      currentCrop: { name: initialFallback.field.crop, variety: initialFallback.field.variety, growthStage: initialFallback.field.growthStage },
      currentWeather: { summary: initialFallback.weather.summary, temperature: initialFallback.weather.temperature, precipitation: "当前天气上下文已接入", updatedAt: initialFallback.weather.updatedAt },
      currentPhenology: { stage: initialFallback.field.growthStage, progress: initialFallback.field.phenologyProgress },
      selectedDiagnosis: initialFallback.diagnosis ? { id: initialFallback.diagnosis.id ?? "agent-diagnosis", title: initialFallback.diagnosis.title, severity: initialFallback.diagnosis.severity ?? "medium", confidence: initialFallback.diagnosis.confidence, summary: initialFallback.diagnosis.summary } : undefined,
      agentRunId: routeRunId || undefined,
      selectedImages: initialFallback.images.map((url, index) => ({ id: `agent-image-${index + 1}`, name: `${initialFallback.field.name} 监控图像 ${index + 1}`, url })),
      currentPage: `/analysis/${routeRunId || "a12-demo"}`,
      pageKey: `/analysis/${routeRunId || "a12-demo"}`,
    });
  }, [fallback, routeRunId, setContext]);

  const applyRealRun = useCallback((next: AgentRun) => {
    setRealRun(next);
    setRuntimeError(null);
    setRunStatus(next.status === "COMPLETED" ? "completed" : next.status === "FAILED" || next.status === "CANCELLED" ? "failed" : "running");
    const runningIndex = next.steps.findIndex((step) => step.status === "RUNNING");
    setCurrentStep(runningIndex >= 0 ? runningIndex : next.steps.filter((step) => step.status === "COMPLETED").length - 1);
    setContext({
      currentPage: `/analysis/${next.runId}`,
      pageKey: `/analysis/${next.runId}`,
      agentRunId: next.runId,
      currentField: next.context.fieldId ? { id: next.context.fieldId, name: next.context.fieldName || next.context.fieldId, area: fallback.field.area, crop: next.context.crop || fallback.field.crop } : undefined,
      currentCrop: next.context.crop ? { name: next.context.crop, variety: next.context.variety, growthStage: next.context.phenologyStage } : undefined,
      selectedDiagnosis: next.context.diagnosis?.id ? { id: String(next.context.diagnosis.id), title: String(next.context.diagnosis.title || "Agent 诊断"), severity: next.context.diagnosis.severity === "high" || next.context.diagnosis.severity === "low" ? next.context.diagnosis.severity : "medium", confidence: typeof next.context.diagnosis.confidence === "number" ? next.context.diagnosis.confidence : undefined, summary: typeof next.context.diagnosis.summary === "string" ? next.context.diagnosis.summary : undefined, sourceRecommendationId: next.context.sourceRecommendationId } : undefined,
    });
  }, [fallback.field.area, fallback.field.crop, setContext]);

  useEffect(() => {
    if (!isRealRunRoute || !routeRunId) return;
    let cancelled = false;
    const sync = (next: AgentRun) => { if (!cancelled) applyRealRun(next); };
    void pollAgentRun(routeRunId, sync).catch((error: unknown) => { if (!cancelled) setRuntimeError(error instanceof AgentRuntimeUnavailableError ? error.message : "真实 Agent Run 无法同步"); });
    return () => { cancelled = true; };
  }, [applyRealRun, isRealRunRoute, routeRunId]);

  useEffect(() => () => { if (timerRef.current) clearInterval(timerRef.current); }, []);

  const startRun = async () => {
    if (timerRef.current) clearInterval(timerRef.current);
    elapsedRef.current = 0;
    setElapsedSeconds(0);
    setActionError("");
    setRuntimeError(null);
    if (agentMode === "real" && routeRunId !== "a12-demo") {
      if (isRealRunRoute && routeRunId) {
        try { applyRealRun(await getAgentRun(routeRunId)); } catch (error: unknown) { setRuntimeError(error instanceof AgentRuntimeUnavailableError ? error.message : "真实 Agent Run 无法同步"); }
        return;
      }
      setRunStatus("running");
      try {
        const diagnosisId = aiContext.selectedDiagnosis?.id;
        if (!diagnosisId || diagnosisId === "a12-demo") throw new AgentRuntimeUnavailableError("真实 Agent 需要已保存的 Diagnosis");
        const created = await createAgentRun(fallback.goal, undefined, "real", { diagnosisId, recommendationId: aiContext.selectedDiagnosis?.sourceRecommendationId, intent: fallback.goal });
        navigate(`/analysis/${created.runId}`);
      } catch (error: unknown) {
        setRunStatus("failed");
        setRuntimeError(error instanceof AgentRuntimeUnavailableError ? error.message : "Agent Runtime 暂不可用");
      }
      return;
    }
    setRunStatus("running");
    setCurrentStep(0);
    timerRef.current = setInterval(() => {
      elapsedRef.current += 1;
      setElapsedSeconds(elapsedRef.current);
      setCurrentStep((step) => {
        if (step >= 7) {
          if (timerRef.current) clearInterval(timerRef.current);
          setRunStatus("completed");
          return step;
        }
        return step + 1;
      });
    }, 1000);
  };

  const viewModel: AgentWorkspaceViewModel = realRun ? toAgentWorkspaceViewModel(realRun, fallback) : demoViewModel;

  const openAgentCopilot = () => {
    const mode = viewModel.mode === "DEMO" ? "DEMO" : viewModel.mode === "STALE" ? "STALE" : viewModel.mode === "UNAVAILABLE" || viewModel.mode === "EMPTY" ? "UNAVAILABLE" : "REAL";
    openOverviewCopilot({
      contextType: "AGENT",
      mode,
      page: `/analysis/${viewModel.runId ?? "a12-demo"}`,
      farm: { id: viewModel.farm.id, name: viewModel.farm.name },
      field: { id: viewModel.field.id, name: viewModel.field.name, crop: viewModel.field.crop, area: viewModel.field.area },
      weather: viewModel.weather,
      title: viewModel.goal,
      summary: viewModel.statusDetail,
      diagnosis: viewModel.diagnosis ? { id: viewModel.diagnosis.id, title: viewModel.diagnosis.title, severity: viewModel.diagnosis.severity, confidence: viewModel.diagnosis.confidence } : undefined,
      agent: { runId: viewModel.runId, status: viewModel.statusLabel, finding: viewModel.findings[0]?.text, proposedAction: viewModel.actions[0]?.title, approvalState: viewModel.approval.stateLabel },
      evidence: normalizeOverviewEvidenceSources(viewModel.evidence.map((item) => item.category), mode === "UNAVAILABLE" ? "UNAVAILABLE" : mode),
      freshness: { updatedAt: viewModel.freshness.updatedAt, stale: viewModel.freshness.stale },
    }, { setAIContext: setContext, setCopilotContext, setDraft: setCopilotDraft, open: openCopilot });
  };

  const handleCancel = async () => {
    if (!realRun || !viewModel.canCancel) return;
    setActionBusy(true);
    setActionError("");
    try { applyRealRun(await cancelAgentRun(realRun.runId)); } catch { setActionError("取消请求未完成，运行状态未改变。"); } finally { setActionBusy(false); }
  };

  const handleDecision = async (decision: "approve" | "reject") => {
    if (!realRun || !viewModel.approval.actionId || !viewModel.approval.canApprove) return;
    setActionBusy(true);
    setActionError("");
    try {
      const next = decision === "approve" ? await approveAgentAction(realRun.runId, viewModel.approval.actionId) : await rejectAgentAction(realRun.runId, viewModel.approval.actionId);
      // The approval write and the persisted AgentRun snapshot can cross at the
      // API boundary. Re-read the approved run before rendering the execution
      // result so the UI cannot briefly regress to the waiting state.
      if (decision === "approve" && next.status !== "COMPLETED") {
        applyRealRun(await pollAgentRun(realRun.runId, () => undefined, 500));
      } else {
        applyRealRun(next);
      }
    } catch { setActionError(`${decision === "approve" ? "批准" : "拒绝"}请求未完成，审批状态未改变。`); } finally { setActionBusy(false); }
  };

  if (isRealRunRoute && !realRun) return <RealRunLoading fallback={fallback} error={runtimeError ?? undefined} onRetry={startRun} onBack={() => navigate("/diagnosis/new")} />;

  return (
    <div className="fm-page fm-agent-page fm-agent-command-center">
      <AgentWorkspaceHeader viewModel={viewModel} onBackDiagnosis={() => navigate(`/diagnosis/${viewModel.diagnosis?.id ?? (isRealRunRoute ? "new" : "a12-demo")}`)} onStart={() => void startRun()} onCancel={() => void handleCancel()} onRetry={() => void startRun()} onAskAi={openAgentCopilot} />
      <MultimodalOverviewPanel viewModel={viewModel} />
      <div className="fm-agent-business-grid fm-agent-business-grid--focused">
        <main className="fm-agent-business-main">
          <Card bordered={false} className="fm-agent-goal-card"><Flex justify="space-between" align="flex-start" gap={14} wrap><div><div className="fm-eyebrow">农业问题</div><Title level={4}>{viewModel.goal}</Title><Text type="secondary">{viewModel.statusDetail}</Text></div>{viewModel.status === "READY" && <Button type="primary" icon={<PlayCircleFilled />} onClick={() => void startRun()}>开始分析</Button>}</Flex></Card>
          <AnalysisProgress steps={viewModel.steps} />
          <EvidenceUsedPanel viewModel={viewModel} />
          <FindingsPanel viewModel={viewModel} />
          <ActionProposalPanel viewModel={viewModel} />
        </main>
        <aside className="fm-agent-business-side">
          <Card bordered={false} className="fm-agent-context" title={<Flex justify="space-between" align="center"><span>调查对象</span><Tag color={viewModel.mode === "DEMO" ? "gold" : "blue"}>{viewModel.sourceLabel}</Tag></Flex>}>
            <FieldContextCard field={viewModel.field} compact />
            {viewModel.diagnosis && <div className="fm-agent-context-diagnosis"><small>来源 · AI 诊断</small><b>{viewModel.diagnosis.title}</b>{viewModel.diagnosis.confidence !== undefined && <span>{viewModel.diagnosis.confidence}% 诊断置信度</span>}</div>}
            <div className="fm-agent-data-sources"><Title level={5}>数据接入状态</Title>{viewModel.dataSources.map((source) => <div key={source.id}><FileSearchOutlined /><b>{source.label}</b>{dataSourceTag(source)}<span>{source.countLabel}</span></div>)}</div>
          </Card>
          {viewModel.approval.state === "REQUIRED" && <ApprovalPanel viewModel={viewModel} busy={actionBusy} error={actionError} onApprove={() => void handleDecision("approve")} onReject={() => void handleDecision("reject")} />}
          <details className="fm-agent-optional-operation"><summary>可选：转为农事任务</summary><ExecutionResultPanel viewModel={viewModel} onOpenOperations={() => navigate("/operations/tasks")} /></details>
           <Card bordered={false} className="fm-agent-copilot-card"><Flex align="center" gap={10}><span className="fm-ai-mark"><RobotOutlined /></span><div><Title level={5}>追问这次调查</Title><Text type="secondary">围绕发现、证据、不确定性和下一步继续提问。</Text></div></Flex><Button block onClick={openAgentCopilot}>继续询问农业 Agent</Button></Card>
          <TechnicalDetailsPanel viewModel={viewModel} />
        </aside>
      </div>
       <Flex className="fm-agent-footer" justify="space-between" align="center"><Text type="secondary"><CloudOutlined /> {viewModel.mode === "REAL" ? "真实 Agent 运行 · 只读工具自动执行，写操作需要审批" : "流程演示 · 不调用真实设备，不创建农事任务"}</Text><Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate("/overview")}>返回工作台</Button></Flex>
    </div>
  );
}

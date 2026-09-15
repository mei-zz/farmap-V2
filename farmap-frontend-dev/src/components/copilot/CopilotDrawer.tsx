import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Card, Divider, Drawer, Empty, Flex, Image, Input, Space, Tag, Typography, Upload } from "antd";
import { DeleteOutlined, FileImageOutlined, PlusOutlined, RobotOutlined, SendOutlined } from "@ant-design/icons";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { useNavigate } from "react-router";
import { AgentRuntimeUnavailableError, agentMode, approveAgentAction, cancelAgentRun, createAgentRun, getAgentRun, pollAgentRun, rejectAgentAction, streamAgentEvents } from "@/features/agent/service";
import type { AgentRun } from "@/features/agent/contract";
import { toAgentContext } from "@/features/agent/adapter";
import { createDiagnosisJob, pollDiagnosisJob, DiagnosisApiError, uploadDiagnosisImages } from "@/features/diagnosis/service";
import {
  loadCopilotDiagnosis,
  loadCopilotFarmStatus,
  loadCopilotFieldStatus,
  loadCopilotPriorities,
  generateCopilotAnswer,
  searchCopilotKnowledge,
  searchHistoricalCases,
  type CommandDiagnosisResult,
  type CommandInsightResult,
  type CommandPrioritiesResult,
  type HistoricalCaseSearchResult,
} from "@/features/copilot/commandService";
import { getOverviewCopilotContextLabel } from "@/features/overview/copilot/context";
import { clearCopilotConversation, createCopilotConversation, createCopilotRequest, listCopilotMemories, loadCopilotConversation, revokeCopilotMemory, routeCopilot, streamCopilotChat } from "@/features/copilot/service";
import type { CopilotMemoryView } from "@/features/copilot/contract";
import type { CopilotRouteResult } from "@/features/copilot/contract";
import { toCopilotContext, type AIContext } from "@/store/aiContext";
import { cropDisplayName, growthStageDisplayName } from "@/features/farm/labels";

const { Text, Title } = Typography;

function MarkdownMessage({ text }: { text: string }) {
  return (
    <div className="farmap-copilot__markdown-message">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          p: ({ children }) => <p style={{ margin: "0 0 8px" }}>{children}</p>,
          ul: ({ children }) => <ul style={{ margin: "0 0 8px", paddingLeft: 22 }}>{children}</ul>,
          ol: ({ children }) => <ol style={{ margin: "0 0 8px", paddingLeft: 22 }}>{children}</ol>,
          strong: ({ children }) => <strong>{children}</strong>,
          code: ({ children, ...props }) => (
            <code {...props} style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{children}</code>
          ),
          a: ({ children, ...props }) => (
            <a {...props} target="_blank" rel="noreferrer noopener">{children}</a>
          ),
          table: ({ children }) => (
            <div style={{ maxWidth: "100%", overflowX: "auto", marginBottom: 8 }}>
              <table>{children}</table>
            </div>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}

type DiagnosisProposalStatus = "READY" | "CREATING_DIAGNOSIS" | "RUNNING_DIAGNOSIS" | "CREATING_AGENT" | "FAILED";

type DiagnosisProposal = {
  farmId: number;
  farmName: string;
  fieldId: string;
  fieldName: string;
  crop?: string;
  growthStage?: string;
  goal: string;
  imageUrls: string[];
  diagnosisId?: string;
  recommendationId?: string;
  jobId?: string;
  progressMessage?: string;
  status: DiagnosisProposalStatus;
  error?: string;
};

type CommandBarResult =
  | { type: "PRIORITIES"; data: CommandPrioritiesResult }
  | { type: "HISTORICAL_CASES"; data: HistoricalCaseSearchResult }
  | { type: "FARM_STATUS"; data: CommandInsightResult }
  | { type: "FIELD_STATUS"; data: CommandInsightResult }
  | { type: "DIAGNOSIS"; data: CommandDiagnosisResult };

type ContextualAction = {
  label: string;
  prompt: string;
};

type CopilotAgentProgress = {
  runId: string;
  diagnosisId: string;
  farmName: string;
  fieldName: string;
  crop?: string;
  run?: AgentRun;
  error?: string;
};

type CopilotActivityStepStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "SKIPPED";

type CopilotActivityStep = {
  id: string;
  label: string;
  detail: string;
  status: CopilotActivityStepStatus;
  durationMs?: number;
};

type CopilotActivity = {
  requestId: string;
  query: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  startedAt: number;
  intent?: string;
  model?: string;
  steps: CopilotActivityStep[];
};

const COPILOT_ROUTE_TIMEOUT_MS = 12000;
const COPILOT_COMMAND_TIMEOUT_MS = 15000;
const COPILOT_MODEL_TIMEOUT_MS = 9000;
const ACTIVE_DIAGNOSIS_KEY = "farmap.copilot.active-diagnosis";

function withTimeout<T>(promise: Promise<T>, timeoutMs: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error(message)), timeoutMs);
    promise.then((value) => {
      window.clearTimeout(timer);
      resolve(value);
    }).catch((error) => {
      window.clearTimeout(timer);
      reject(error);
    });
  });
}

function diagnosisErrorMessage(error: unknown): string {
  if (error instanceof DiagnosisApiError || error instanceof AgentRuntimeUnavailableError) return error.message;
  return error instanceof Error ? error.message : "AI 调查未完成，请稍后重试。";
}

function weatherInput(context: AIContext) {
  if (!context.currentWeather) return undefined;
  return {
    summary: context.currentWeather.summary,
    temperature: context.currentWeather.temperature,
    observedAt: context.currentWeather.updatedAt,
    rainfall14d: context.currentWeather.rainfall14d,
  };
}

function buildDiagnosisProposal(result: CopilotRouteResult, context: AIContext, goal: string): DiagnosisProposal | undefined {
  const farmId = Number(result.resolvedContext?.farmId ?? context.currentFarm.id);
  const fieldId = result.resolvedContext?.fieldId ?? context.currentField?.id;
  if (!Number.isFinite(farmId) || farmId <= 0 || !fieldId) return undefined;
  return {
    farmId,
    farmName: context.currentFarm.name,
    fieldId,
    fieldName: context.currentField?.id === fieldId ? context.currentField.name : fieldId,
    crop: context.currentField?.id === fieldId ? context.currentCrop?.name ?? context.currentField.crop : undefined,
    growthStage: context.currentField?.id === fieldId ? context.currentCrop?.growthStage ?? context.currentPhenology?.stage : undefined,
    goal,
    imageUrls: context.selectedImages.map((image) => image.url).filter((url): url is string => Boolean(url)),
    status: "READY",
  };
}

function agentStatusLabel(status?: AgentRun["status"]): string {
  if (status === "COMPLETED") return "调查完成";
  if (status === "FAILED") return "调查失败";
  if (status === "CANCELLED") return "调查已取消";
  if (status === "WAITING_FOR_APPROVAL") return "等待人工确认";
  if (status === "RUNNING") return "正在调查";
  return "正在启动";
}

function agentStepGlyph(status: string): string {
  if (status === "COMPLETED") return "✓";
  if (status === "FAILED") return "!";
  if (status === "RUNNING") return "●";
  return "○";
}

function cropLabel(crop?: string): string | undefined {
  return crop ? cropDisplayName(crop) : undefined;
}

function evidenceModalityLabel(modality?: string): string {
  const value = (modality ?? "").toLowerCase();
  if (value.includes("image") || value.includes("camera") || value.includes("vision")) return "现场图像";
  if (value.includes("weather")) return "天气";
  if (value.includes("historical") || value.includes("case")) return "历史案例";
  if (value.includes("knowledge") || value.includes("rag")) return "农业知识 RAG";
  if (value.includes("soil")) return "土壤";
  if (value.includes("spatial") || value.includes("field") || value.includes("gis")) return "地块空间";
  return modality || "农业证据";
}

function normalizeKnowledgeQuery(query: string, crop?: string, growthStage?: string): string {
  let normalized = query
    .replace(/\bplum\b/gi, "脆李")
    .replace(/\bcitrus\b/gi, "柑橘")
    .replace(/\brice\b/gi, "水稻")
    .replace(/\borange\b/gi, "柑橘");
  const localizedCrop = cropLabel(crop);
  if (localizedCrop && !normalized.includes(localizedCrop)) normalized = `${localizedCrop}${growthStage || ""}：${normalized}`;
  return normalized;
}

export default function CopilotDrawer() {
  const context = useAIContextStore((state) => state.context);
  const setContext = useAIContextStore((state) => state.setContext);
  const isOpen = useCopilotStore((state) => state.isOpen);
  const close = useCopilotStore((state) => state.close);
  const draft = useCopilotStore((state) => state.draft);
  const copilotContext = useCopilotStore((state) => state.context);
  const setDraft = useCopilotStore((state) => state.setDraft);
  const messages = useCopilotStore((state) => state.messages);
  const addMessage = useCopilotStore((state) => state.addMessage);
  const upsertMessage = useCopilotStore((state) => state.upsertMessage);
  const setMessages = useCopilotStore((state) => state.setMessages);
  const clearMessages = useCopilotStore((state) => state.clearMessages);
  const navigate = useNavigate();
  const [sending, setSending] = useState(false);
  const [diagnosisProposal, setDiagnosisProposal] = useState<DiagnosisProposal>();
  const [diagnosisPageGoal, setDiagnosisPageGoal] = useState<string>();
  const [agentProgress, setAgentProgress] = useState<CopilotAgentProgress>();
  const [agentProgressRunId, setAgentProgressRunId] = useState<string>();
  const [commandBarResult, setCommandBarResult] = useState<CommandBarResult>();
  const [commandBarLoading, setCommandBarLoading] = useState<CommandBarResult["type"]>();
  const [activity, setActivity] = useState<CopilotActivity>();
  const [memories, setMemories] = useState<CopilotMemoryView[]>([]);
  const [memoryLoading, setMemoryLoading] = useState(false);
  const [imageUploading, setImageUploading] = useState(false);
  const [imageUploadError, setImageUploadError] = useState("");
  const requestSequence = useRef(0);
  const activeRequestId = useRef<string | undefined>(undefined);
  const activeDiagnosisJobId = useRef<string | undefined>(undefined);
  const activeStreamController = useRef<AbortController | undefined>(undefined);
  const activeChatController = useRef<AbortController | undefined>(undefined);
  const [agentStreamNonce, setAgentStreamNonce] = useState(0);

  const updateActivity = (requestId: string, updater: (current: CopilotActivity) => CopilotActivity) => {
    setActivity((current) => current?.requestId === requestId ? updater(current) : current);
  };

  const updateActivityStep = (requestId: string, stepId: string, patch: Partial<CopilotActivityStep>) => {
    updateActivity(requestId, (current) => ({
      ...current,
      steps: current.steps.map((step) => step.id === stepId ? { ...step, ...patch } : step),
    }));
  };

  useEffect(() => {
    if (!isOpen) return;
    void loadCopilotConversation().then((loaded) => {
      if (!loaded || loaded.recentTurns.length === 0) return;
      setMessages(loaded.recentTurns.filter((turn) => turn.role !== "SYSTEM_EVENT").map((turn) => ({ id: turn.turnId, role: turn.role.toLowerCase() as "user" | "assistant", content: turn.message })));
    }).catch(() => undefined);
  }, [isOpen, setMessages]);

  useEffect(() => {
    if (!isOpen) return;
    setMemoryLoading(true);
    void listCopilotMemories(context)
      .then((items) => setMemories(items.filter((item) => item.status === "ACTIVE")))
      .catch(() => setMemories([]))
      .finally(() => setMemoryLoading(false));
  }, [context, isOpen]);

  useEffect(() => {
    if (!isOpen || diagnosisProposal || typeof window === "undefined") return;
    const saved = window.sessionStorage.getItem(ACTIVE_DIAGNOSIS_KEY);
    if (!saved) return;
    try {
      const restored = JSON.parse(saved) as DiagnosisProposal;
      if (restored?.farmId && restored?.fieldId && restored?.goal) setDiagnosisProposal(restored);
    } catch {
      window.sessionStorage.removeItem(ACTIVE_DIAGNOSIS_KEY);
    }
  }, [diagnosisProposal, isOpen]);

  useEffect(() => {
    if (typeof window === "undefined") return;
    if (diagnosisProposal) window.sessionStorage.setItem(ACTIVE_DIAGNOSIS_KEY, JSON.stringify(diagnosisProposal));
    else window.sessionStorage.removeItem(ACTIVE_DIAGNOSIS_KEY);
  }, [diagnosisProposal]);

  useEffect(() => {
    const proposal = diagnosisProposal;
    if (!isOpen || !proposal?.jobId || proposal.status !== "RUNNING_DIAGNOSIS" || activeDiagnosisJobId.current === proposal.jobId) return;
    activeDiagnosisJobId.current = proposal.jobId;
    let active = true;
    void pollDiagnosisJob(proposal.jobId, (next) => {
      if (!active) return;
      setDiagnosisProposal((current) => current ? { ...current, progressMessage: next.message || next.stage || "诊断正在后台运行…" } : current);
    }, 1500, { onError: (_error, count) => setDiagnosisProposal((current) => current ? { ...current, progressMessage: `连接暂时中断，正在自动重试（第 ${count} 次）…` } : current) })
      .then((completed) => {
        if (!active) return;
        if (completed.status !== "COMPLETED" || !completed.result?.diagnosisId) throw new DiagnosisApiError(completed.error || completed.message || "诊断任务未完成");
        const response = completed.result;
        if (!response.diagnosis) throw new DiagnosisApiError("诊断任务未返回结构化结论");
        const diagnosisId = response.diagnosisId;
        if (!diagnosisId) throw new DiagnosisApiError("诊断任务未返回稳定 diagnosisId");
        setContext({ selectedDiagnosis: { id: diagnosisId, title: response.diagnosis.title, severity: response.diagnosis.risk_level, confidence: Math.round(response.diagnosis.confidence * 100), summary: response.diagnosis.summary } });
        setDiagnosisProposal((current) => current ? { ...current, diagnosisId, recommendationId: response.recommendations?.[0]?.id, status: "READY", progressMessage: "诊断已完成；正在恢复农业 Agent 调查。", error: undefined } : current);
      })
      .catch((error: unknown) => {
        if (active) setDiagnosisProposal((current) => current ? { ...current, status: "FAILED", error: diagnosisErrorMessage(error) } : current);
      })
      .finally(() => { if (activeDiagnosisJobId.current === proposal.jobId) activeDiagnosisJobId.current = undefined; });
    return () => { active = false; };
  }, [diagnosisProposal?.jobId, diagnosisProposal?.status, isOpen, setContext]);

  useEffect(() => {
    const runId = agentProgressRunId;
    if (!runId) return;
    let active = true;
    const controller = new AbortController();
    activeStreamController.current?.abort();
    activeStreamController.current = controller;
    const sync = () => getAgentRun(runId).then((run) => {
      if (!active) return;
      setAgentProgress((current) => current ? { ...current, run, error: undefined } : current);
      setContext({ agentRunId: run.runId });
    });
    const syncFinal = async (expectedStatus?: string) => {
      for (let attempt = 0; attempt < 5 && active; attempt += 1) {
        try {
          const run = await getAgentRun(runId);
          if (!active) return;
          setAgentProgress((current) => current ? { ...current, run, error: undefined } : current);
          setContext({ agentRunId: run.runId });
          if (!expectedStatus || run.status === expectedStatus) return;
        } catch { /* the terminal write may still be committing */ }
        await new Promise((resolve) => window.setTimeout(resolve, 80));
      }
    };
    const fallback = () => pollAgentRun(runId, (run) => {
      if (!active) return;
      setAgentProgress((current) => current ? { ...current, run, error: undefined } : current);
      setContext({ agentRunId: run.runId });
    });
    void (async () => {
      try {
        const result = await streamAgentEvents(runId, (event) => {
          if (!active) return;
          if (event.event === "done") {
            const expected = event.data && typeof event.data === "object" ? String((event.data as Record<string, unknown>).status ?? "") : undefined;
            void syncFinal(expected).catch(() => undefined);
          } else {
            void sync().catch(() => undefined);
          }
        }, controller.signal);
        if (active && !result.completed) await fallback();
      } catch (error: unknown) {
        if (!active || controller.signal.aborted) return;
        try { await fallback(); }
        catch (fallbackError: unknown) {
          if (!active) return;
          const finalError = fallbackError instanceof AgentRuntimeUnavailableError ? fallbackError : error;
          setAgentProgress((current) => current ? { ...current, error: finalError instanceof Error ? finalError.message : "Agent Run 状态暂不可用。" } : current);
        }
      }
    })();
    return () => { active = false; controller.abort(); if (activeStreamController.current === controller) activeStreamController.current = undefined; };
  }, [agentProgressRunId, agentStreamNonce, setContext]);

  const canonicalContext = useMemo(() => toCopilotContext(context), [context]);
  const pageLabel = canonicalContext.pageKey.includes("overview") ? "农场总览" : canonicalContext.pageKey.includes("farm/map") ? "数字农场" : canonicalContext.pageKey.includes("diagnosis") ? "AI 诊断" : canonicalContext.pageKey.includes("analysis") ? "农业 Agent" : canonicalContext.pageKey.includes("operations") ? "农事任务" : "当前页面";

  const contextualActions = useMemo<ContextualAction[]>(() => {
    const page = canonicalContext.pageKey.toLowerCase();
    if (page.includes("operations")) {
      return [
        { label: "解释这个任务", prompt: "请解释当前任务的状态、来源和还需要确认的信息" },
        { label: "查看来源诊断", prompt: "查看当前任务关联的来源诊断" },
        { label: "查看相关 Evidence", prompt: "查看这个任务关联的 Evidence" },
      ];
    }
    if (page.includes("diagnosis") && canonicalContext.diagnosisId) {
      return [
        { label: "解释诊断", prompt: "解释当前诊断的依据和不确定性" },
        { label: "查看 Evidence", prompt: "查看当前诊断的 Evidence" },
        { label: "查找相似病例", prompt: "找一下和这次情况最像的历史病例" },
        { label: "创建农业任务", prompt: "安排当前诊断的第一条建议" },
      ];
    }
    const crop = cropLabel(canonicalContext.crop);
    const stage = growthStageDisplayName(canonicalContext.growthStage);
      return [
        { label: "查看当前风险", prompt: "今天最需要处理什么？" },
        { label: "分析这个地块", prompt: "帮我分析当前地块有什么问题" },
        { label: "发起 AI 调查", prompt: "帮我分析当前地块有什么问题" },
        { label: "搜索农业知识", prompt: `${crop || "当前作物"}${stage || ""}常见管理问题是什么？` },
      { label: "查找历史病例", prompt: "找一下和这次情况最像的历史病例" },
      { label: "查看待处理任务", prompt: "有哪些任务还没完成？" },
    ];
  }, [canonicalContext]);

  const startModelAnswer = (requestId: string, content: string, routed: CopilotRouteResult, evidence: unknown, knowledgeCount?: number) => {
    const assistantId = `copilot-answer-${requestId}`;
    const appendAnswer = (answer: string) => {
      if (knowledgeCount === undefined) addMessage({ role: "assistant", content: answer });
      else upsertMessage({ id: assistantId, role: "assistant", content: answer, knowledgeCount });
    };
    updateActivityStep(requestId, "model", { status: "RUNNING", detail: "已拿到真实工具结果，正在生成有证据的简短总结。" });
    void withTimeout(generateCopilotAnswer({
      message: content,
      intent: routed.intent,
      conversationId: routed.conversationId,
      traceId: routed.traceId,
      context: {
        farmId: canonicalContext.farmId,
        fieldId: canonicalContext.fieldId,
        crop: canonicalContext.crop,
        growthStage: canonicalContext.growthStage,
        pageKey: canonicalContext.pageKey,
      },
      evidence,
    }), COPILOT_MODEL_TIMEOUT_MS, "模型总结超时").then((answer) => {
      if (activeRequestId.current !== requestId) return;
      if (answer.available === false) {
        appendAnswer(`${answer.answer}\n\n真实工具结果已保留；模型恢复后可重新生成中文总结。`);
        updateActivity(requestId, (current) => ({
          ...current,
          status: "COMPLETED",
          steps: current.steps.map((step) => step.id === "model" ? { ...step, status: "FAILED", detail: "模型服务当前无可用权限；真实数据工具已完成，未伪造模型回答。" } : step),
        }));
        return;
      }
      appendAnswer(answer.answer);
      updateActivity(requestId, (current) => ({
        ...current,
        status: "COMPLETED",
        model: answer.model,
        steps: current.steps.map((step) => step.id === "model" ? {
          ...step,
          status: "COMPLETED",
          detail: `${answer.model || "Copilot 模型"} 已完成有证据总结${answer.latencyMs ? ` · ${answer.latencyMs}ms` : ""}`,
          durationMs: answer.latencyMs,
        } : step),
      }));
    }).catch((error: unknown) => {
      if (activeRequestId.current !== requestId) return;
      updateActivity(requestId, (current) => ({
        ...current,
        status: "COMPLETED",
        steps: current.steps.map((step) => step.id === "model" ? {
          ...step,
          status: "FAILED",
          detail: error instanceof Error ? `${error.message}；已保留真实工具结果。` : "模型总结不可用；已保留真实工具结果。",
        } : step),
      }));
    });
  };

  const isLightweightQuestion = (content: string): boolean => {
    // The server remains the intent authority. This client hint only decides
    // whether to try the one-request chat transport; explicit commands keep
    // their existing structured handlers.
    if (/(安排|执行|启动\s*agent|帮我处理|落实|诊断|分析|病害|病斑|发黄|掉果)/i.test(content)) return true;
    return !/(任务|病例|案例|风险|优先|打开|导航|记住|忘记|调查|创建农业任务|相似历史)/i.test(content);
  };

  const startLightweightChat = async (requestId: string, content: string, isActive: () => boolean) => {
    let streamed = "";
    let routed: CopilotRouteResult | undefined;
    let knowledgeCount: number | undefined;
    let referenceImages: Array<{ refId: string; label: string; imageUrl: string; match?: string }> = [];
    const assistantId = `copilot-stream-${requestId}`;
    await streamCopilotChat({
      ...createCopilotRequest(content, context, copilotContext),
      imageUrls: context.selectedImages.map((image) => image.url).filter((url): url is string => Boolean(url)),
    }, (event) => {
      if (!isActive()) return;
      if (event.event === "status" && event.data && typeof event.data === "object") {
        const message = String((event.data as Record<string, unknown>).message ?? "正在处理…");
        updateActivity(requestId, (current) => ({
          ...current,
          steps: current.steps.map((step) => step.id === "route" ? { ...step, detail: message } : step),
        }));
      } else if (event.event === "route" && event.data && typeof event.data === "object") {
        routed = event.data as CopilotRouteResult;
        updateActivity(requestId, (current) => ({
          ...current,
          intent: routed?.intent,
          steps: current.steps.map((step) => step.id === "route" ? { ...step, status: "COMPLETED", detail: `${routed?.intent ?? "GENERAL_QA"} · 服务端已完成意图识别。` } : step),
        }));
      } else if (event.event === "diagnosis.job" && event.data && typeof event.data === "object") {
        const data = event.data as { jobId?: string; message?: string };
        updateActivityStep(requestId, "data", { status: "RUNNING", detail: data.message ?? `诊断任务已创建${data.jobId ? ` · ${data.jobId}` : ""}。` });
      } else if (event.event === "run.created" && event.data && typeof event.data === "object") {
        const data = event.data as { runId?: string; diagnosisId?: string; status?: string };
        if (!data.runId || !data.diagnosisId) return;
        setContext({ agentRunId: data.runId });
        setAgentProgress({
          runId: data.runId,
          diagnosisId: data.diagnosisId,
          farmName: canonicalContext.farmName,
          fieldName: canonicalContext.fieldName || "当前地块",
          crop: canonicalContext.crop,
        });
        setAgentProgressRunId(data.runId);
        updateActivity(requestId, (current) => ({
          ...current,
          steps: current.steps.map((step) => step.id === "data"
            ? { ...step, status: "COMPLETED", detail: `Diagnosis 已保存，Agent Run 已创建${data.status ? ` · ${data.status}` : ""}。` }
            : step.id === "model" ? { ...step, status: "SKIPPED", detail: "本次请求转入 Agent 实时调查。" } : step),
        }));
      } else if (event.event === "knowledge" && event.data && typeof event.data === "object") {
        const data = event.data as { count?: number; items?: Array<{ title?: string; source?: string; content?: string; score?: number; chunkId?: string }> };
        const items = (data.items ?? []).map((item) => ({ title: item.title ?? "农业知识片段", source: item.source, content: item.content, score: item.score, chunkId: item.chunkId }));
        knowledgeCount = data.count ?? items.length;
        updateActivityStep(requestId, "data", { status: "COMPLETED", detail: `已检索到 ${data.count ?? items.length} 条知识依据。` });
      } else if (event.event === "reference.images" && event.data && typeof event.data === "object") {
        const data = event.data as { items?: Array<{ refId?: string; label?: string; imageUrl?: string; match?: string }> };
        referenceImages = (data.items ?? [])
          .filter((item): item is { refId: string; label: string; imageUrl: string; match?: string } => Boolean(item.refId && item.label && item.imageUrl))
          .map((item) => ({ refId: item.refId, label: item.label, imageUrl: item.imageUrl, match: item.match }));
      } else if (event.event === "content.delta" && event.data && typeof event.data === "object") {
        const delta = String((event.data as Record<string, unknown>).delta ?? "");
        if (!delta) return;
        streamed += delta;
        upsertMessage({ id: assistantId, role: "assistant", content: streamed, knowledgeCount, referenceImages });
        updateActivityStep(requestId, "model", { status: "RUNNING", detail: "正在逐字生成回答…" });
      } else if (event.event === "error" && event.data && typeof event.data === "object") {
        const message = String((event.data as Record<string, unknown>).message ?? "Copilot 暂时不可用，请稍后重试。");
        upsertMessage({ id: assistantId, role: "assistant", content: message });
        updateActivity(requestId, (current) => ({
          ...current,
          status: "FAILED",
          steps: current.steps.map((step) => step.status === "RUNNING" ? { ...step, status: "FAILED", detail: message } : step),
        }));
      } else if (event.event === "done") {
        if (streamed || referenceImages.length > 0) {
          upsertMessage({ id: assistantId, role: "assistant", content: streamed, knowledgeCount, referenceImages });
        }
        const doneStatus = event.data && typeof event.data === "object"
          ? String((event.data as Record<string, unknown>).status ?? "COMPLETED")
          : "COMPLETED";
        updateActivity(requestId, (current) => ({
          ...current,
          status: current.status === "CANCELLED" || doneStatus === "FAILED" ? current.status === "CANCELLED" ? current.status : "FAILED" : "COMPLETED",
          steps: current.steps.map((step) => step.status === "FAILED" ? step : step.id === "data" && step.status === "PENDING" ? { ...step, status: "SKIPPED", detail: "本次问答没有返回额外数据卡片。" } : step.id === "model" ? { ...step, status: streamed ? "COMPLETED" : "SKIPPED", detail: doneStatus === "RUN_CREATED" ? "本次请求转入 Agent 实时调查。" : streamed ? "回答已完成。" : "没有生成可展示的回答。" } : step),
        }));
      }
    }, activeChatController.current?.signal);
    if (isActive() && !streamed && routed?.clarificationQuestion) {
      upsertMessage({ id: assistantId, role: "assistant", content: routed.clarificationQuestion });
    }
  };

  const handleSend = async () => {
    const content = draft.trim();
    if (!content) return;

    const asksToAnalyzeCurrentField = content.includes("分析当前地块") || content.includes("分析这个地块");
    if (asksToAnalyzeCurrentField && !canonicalContext.fieldId) {
      addMessage({ role: "user", content });
      addMessage({ role: "assistant", content: "请先选择需要分析的地块；农业 Agent 不会猜测分析对象。" });
      setDraft("");
      return;
    }

    setDiagnosisProposal(undefined);
    setDiagnosisPageGoal(undefined);
    setCommandBarResult(undefined);
    addMessage({ role: "user", content });
    setDraft("");
    if (sending) {
      setActivity((current) => current ? {
        ...current,
        status: "CANCELLED",
        steps: current.steps.map((step) => step.status === "RUNNING" ? { ...step, status: "SKIPPED", detail: "已被新的用户请求替代。" } : step),
      } : current);
    }
    setSending(true);
    activeChatController.current?.abort();
    const chatController = new AbortController();
    activeChatController.current = chatController;
    requestSequence.current += 1;
    const requestId = `copilot-${Date.now()}-${requestSequence.current}`;
    const sequence = requestSequence.current;
    activeRequestId.current = requestId;
    setActivity({
      requestId,
      query: content,
      status: "RUNNING",
      startedAt: Date.now(),
      steps: [
        { id: "accepted", label: "接收请求", detail: "请求已进入农业 Agent 执行队列。", status: "COMPLETED" },
        { id: "route", label: "解析意图与上下文", detail: "正在读取当前农场、地块和页面上下文。", status: "RUNNING" },
        { id: "data", label: "调用真实数据工具", detail: "等待意图路由完成。", status: "PENDING" },
        { id: "model", label: "生成有证据的回答", detail: "将在真实工具结果返回后执行。", status: "PENDING" },
      ],
    });
    const isActive = () => activeRequestId.current === requestId && requestSequence.current === sequence;
    try {
      if (isLightweightQuestion(content)) {
        await startLightweightChat(requestId, content, isActive);
        return;
      }
      const routed = await withTimeout(routeCopilot(content, context, copilotContext), COPILOT_ROUTE_TIMEOUT_MS, "Copilot 路由超时，请重试。");
      if (!isActive()) return;
      addMessage({ role: "assistant", content: formatRouteMessage(routed) });
      updateActivity(requestId, (current) => ({
        ...current,
        intent: routed.intent,
        steps: current.steps.map((step) => step.id === "route" ? { ...step, status: "COMPLETED", detail: `${routed.intent} · ${routed.resolutionStatus}${routed.traceId ? ` · trace ${routed.traceId.slice(0, 12)}` : ""}。` } : step.id === "data" ? { ...step, status: routed.resolutionStatus === "RESOLVED" ? "RUNNING" : "SKIPPED", detail: routed.resolutionStatus === "RESOLVED" ? "已开始读取真实业务数据。" : "未执行数据查询。" } : step),
      }));
      if (routed.intent === "DIAGNOSIS_REQUEST") {
        if (routed.resolutionStatus === "RESOLVED" && routed.capability === "DIAGNOSIS_CREATE") {
          const proposal = buildDiagnosisProposal(routed, context, content);
          if (proposal) {
            setDiagnosisProposal(proposal);
            // A diagnosis request is an explicit, read-only investigation command.
            // Start it in the drawer immediately instead of forcing a second page
            // and another confirmation click. Operations writes still require the
            // Agent approval boundary.
            void startDiagnosis(proposal);
          }
          else setDiagnosisPageGoal(content);
        } else {
          setDiagnosisPageGoal(content);
        }
      }
      if (routed.intent === "GENERAL_QA" && routed.resolutionStatus === "RESOLVED") {
        const contextEvidence = {
          source: "CopilotContext",
          farm: canonicalContext.farmName,
          field: canonicalContext.fieldName,
          crop: cropLabel(canonicalContext.crop),
          growthStage: growthStageDisplayName(canonicalContext.growthStage),
          weather: canonicalContext.weather ? {
            summary: canonicalContext.weather.summary,
            temperature: canonicalContext.weather.temperature,
            precipitation: canonicalContext.weather.precipitation,
            updatedAt: canonicalContext.weather.updatedAt,
          } : undefined,
          page: pageLabel,
        };
        updateActivityStep(requestId, "data", {
          status: "COMPLETED",
          label: "读取当前页面上下文",
          detail: "已读取统一 CopilotContext；无需启动复杂诊断任务。",
        });
        updateActivityStep(requestId, "model", {
          label: "快速模型回答",
          detail: "正在调用低成本问答模型；失败时自动切换到可用备用模型。",
        });
        startModelAnswer(requestId, content, routed, contextEvidence);
      }
      if (routed.intent === "PRIORITIES_QUERY" && routed.resolutionStatus === "RESOLVED") {
        setCommandBarLoading("PRIORITIES");
        try {
          const data = await withTimeout(loadCopilotPriorities(Number(routed.resolvedContext?.farmId ?? context.currentFarm.id)), COPILOT_COMMAND_TIMEOUT_MS, "优先事项查询超时");
          if (!isActive()) return;
          setCommandBarResult({ type: "PRIORITIES", data });
          updateActivityStep(requestId, "data", { status: "COMPLETED", detail: `已读取 Diagnosis、Operations 和农场上下文 · ${data.items.length} 项结果。` });
          startModelAnswer(requestId, content, routed, data);
        } catch (error: unknown) {
          if (!isActive()) return;
          setCommandBarResult({ type: "PRIORITIES", data: { status: "UNAVAILABLE", reason: error instanceof Error ? error.message : "优先事项数据暂不可用。", items: [], sources: { diagnoses: "UNAVAILABLE", operations: "UNAVAILABLE" } } });
          updateActivity(requestId, (current) => ({ ...current, status: "FAILED", steps: current.steps.map((step) => step.id === "data" ? { ...step, status: "FAILED", detail: error instanceof Error ? error.message : "真实数据工具不可用。" } : step) }));
        } finally {
          if (isActive()) setCommandBarLoading(undefined);
        }
      }
      if (routed.intent === "HISTORICAL_CASE_QUERY" && routed.resolutionStatus === "RESOLVED") {
        const imageUrls = context.selectedImages.map((image) => image.url).filter((url): url is string => Boolean(url));
        if (!imageUrls.length) {
          setCommandBarResult({ type: "HISTORICAL_CASES", data: { status: "IMAGE_REQUIRED", reason: "当前历史病例索引基于现场图片，需要先提供一张现场图片。", items: [] } });
          updateActivity(requestId, (current) => ({ ...current, status: "COMPLETED", steps: current.steps.map((step) => step.id === "data" ? { ...step, status: "SKIPPED", detail: "缺少现场图片，未执行历史病例检索。" } : step.id === "model" ? { ...step, status: "SKIPPED", detail: "没有可供模型总结的历史病例结果。" } : step) }));
        } else {
          setCommandBarLoading("HISTORICAL_CASES");
          try {
            const data = await withTimeout(searchHistoricalCases(imageUrls, 3), COPILOT_COMMAND_TIMEOUT_MS, "历史病例检索超时");
            if (!isActive()) return;
            setCommandBarResult({ type: "HISTORICAL_CASES", data });
            updateActivityStep(requestId, "data", { status: "COMPLETED", detail: `已检索历史病例索引 · ${data.items.length} 条结果。` });
            startModelAnswer(requestId, content, routed, data);
          } catch (error: unknown) {
            if (!isActive()) return;
            setCommandBarResult({ type: "HISTORICAL_CASES", data: { status: "UNAVAILABLE", reason: error instanceof Error ? error.message : "历史病例检索暂不可用。", items: [] } });
            updateActivity(requestId, (current) => ({ ...current, status: "FAILED", steps: current.steps.map((step) => step.id === "data" ? { ...step, status: "FAILED", detail: error instanceof Error ? error.message : "历史病例工具不可用。" } : step) }));
          } finally {
            if (isActive()) setCommandBarLoading(undefined);
          }
        }
      }
      if (routed.intent === "FARM_STATUS_QUERY" && routed.resolutionStatus === "RESOLVED") {
        setCommandBarLoading("FARM_STATUS");
        try {
          const data = await withTimeout(loadCopilotFarmStatus(Number(routed.resolvedContext?.farmId ?? context.currentFarm.id)), COPILOT_COMMAND_TIMEOUT_MS, "农场状态查询超时");
          if (!isActive()) return;
          setCommandBarResult({ type: "FARM_STATUS", data });
          updateActivityStep(requestId, "data", { status: "COMPLETED", detail: "已汇总地块、天气、历史诊断和农事任务。" });
          startModelAnswer(requestId, content, routed, data);
        } finally {
          if (isActive()) setCommandBarLoading(undefined);
        }
      }
      if (routed.intent === "FIELD_STATUS_QUERY" && routed.resolutionStatus === "RESOLVED") {
        const fieldId = routed.resolvedContext?.fieldId ?? context.currentField?.id;
        if (fieldId) {
          setCommandBarLoading("FIELD_STATUS");
          try {
            const data = await withTimeout(loadCopilotFieldStatus(Number(routed.resolvedContext?.farmId ?? context.currentFarm.id), fieldId), COPILOT_COMMAND_TIMEOUT_MS, "地块状态查询超时");
            if (!isActive()) return;
            setCommandBarResult({ type: "FIELD_STATUS", data });
          updateActivityStep(requestId, "data", { status: "COMPLETED", detail: `已读取地块、天气和近期诊断。` });
            startModelAnswer(requestId, content, routed, data);
          } finally {
            if (isActive()) setCommandBarLoading(undefined);
          }
        }
      }
      if (routed.intent === "KNOWLEDGE_QUERY" && routed.resolutionStatus === "RESOLVED") {
        try {
          const data = await withTimeout(searchCopilotKnowledge(normalizeKnowledgeQuery(content, canonicalContext.crop, canonicalContext.growthStage), 4), COPILOT_COMMAND_TIMEOUT_MS, "知识库查询超时");
          if (!isActive()) return;
          updateActivityStep(requestId, "data", { status: "COMPLETED", detail: `已完成知识库检索 · ${data.items.length} 条匹配片段。` });
          startModelAnswer(requestId, content, routed, data, data.items.length);
        } finally {
          if (isActive()) setCommandBarLoading(undefined);
        }
      }
      if (routed.intent === "DIAGNOSIS_QUERY" && routed.resolutionStatus === "RESOLVED") {
        setCommandBarLoading("DIAGNOSIS");
        try {
          const data = await withTimeout(loadCopilotDiagnosis(Number(routed.resolvedContext?.farmId ?? context.currentFarm.id), routed.resolvedContext?.fieldId ?? context.currentField?.id, routed.resolvedContext?.diagnosisId), COPILOT_COMMAND_TIMEOUT_MS, "Diagnosis 查询超时");
          if (!isActive()) return;
          setCommandBarResult({ type: "DIAGNOSIS", data });
          updateActivityStep(requestId, "data", { status: "COMPLETED", detail: `已读取已保存诊断与证据。` });
          startModelAnswer(requestId, content, routed, data);
        } finally {
          if (isActive()) setCommandBarLoading(undefined);
        }
      }
      if (routed.intent === "ACTION_REQUEST" && routed.capability === "AGENT_START" && routed.resolutionStatus === "RESOLVED" && routed.resolvedContext?.diagnosisId && routed.resolvedContext.recommendationId && agentMode === "real") {
        const run = await createAgentRun(content, toAgentContext(context), "real", {
          diagnosisId: routed.resolvedContext.diagnosisId,
          recommendationId: routed.resolvedContext.recommendationId,
          intent: routed.intent,
        });
        navigate(`/analysis/${run.runId}`);
      }
      if (isActive()) {
        setActivity((current) => {
          if (!current || current.requestId !== requestId || current.status !== "RUNNING") return current;
          if (current.steps.some((step) => step.status === "RUNNING")) return current;
          return {
            ...current,
            status: "COMPLETED",
            steps: current.steps.map((step) => step.status === "PENDING" ? { ...step, status: "SKIPPED", detail: "本次请求不需要执行该阶段。" } : step),
          };
        });
      }
    } catch (error: unknown) {
      if (isActive()) {
        addMessage({ role: "assistant", content: `农业 Agent 未能完成本次请求：${error instanceof Error ? error.message : "服务暂不可用"}。未执行任何农事写入。` });
        updateActivity(requestId, (current) => ({ ...current, status: "FAILED", steps: current.steps.map((step) => step.status === "RUNNING" ? { ...step, status: "FAILED", detail: error instanceof Error ? error.message : "服务暂不可用。" } : step) }));
      }
    } finally {
      if (isActive()) {
        setSending(false);
        if (activeChatController.current === chatController) activeChatController.current = undefined;
      }
    }
  };

  const openDiagnosisPage = (goal: string, proposal?: DiagnosisProposal) => {
    const params = new URLSearchParams();
    if (proposal?.farmId || context.currentFarm.id > 0) params.set("farmId", String(proposal?.farmId ?? context.currentFarm.id));
    if (proposal?.fieldId || context.currentField?.id) params.set("fieldId", proposal?.fieldId ?? context.currentField?.id ?? "");
    params.set("goal", goal);
    close();
    navigate(`/diagnosis/new?${params.toString()}`);
  };

  const openCommandItem = (path: string) => {
    close();
    navigate(path);
  };

  const startDiagnosis = async (proposalInput?: DiagnosisProposal) => {
    const proposal = proposalInput ?? diagnosisProposal;
    if (!proposal || ["CREATING_DIAGNOSIS", "RUNNING_DIAGNOSIS", "CREATING_AGENT"].includes(proposal.status)) return;
    setDiagnosisProposal((current) => current ? { ...current, status: "CREATING_DIAGNOSIS", progressMessage: "正在创建后台诊断任务…", error: undefined } : current);
    let diagnosisId = proposal.diagnosisId;
    let recommendationId = proposal.recommendationId;
    try {
      if (!diagnosisId) {
        const job = await createDiagnosisJob({
          fieldId: proposal.fieldId,
          farmId: proposal.farmId,
          crop: proposal.crop,
          growthStage: proposal.growthStage,
          imageUrls: proposal.imageUrls,
          weather: weatherInput(context),
          query: proposal.goal,
          topK: 5,
          enabledModalities: ["camera", "weather", "historical_case", "knowledge", "soil", "spatial"],
        });
        activeDiagnosisJobId.current = job.jobId;
        setDiagnosisProposal((current) => current ? { ...current, jobId: job.jobId, status: "RUNNING_DIAGNOSIS", progressMessage: job.message || "正在汇集地块、天气与知识证据…" } : current);
        const completed = await pollDiagnosisJob(job.jobId, (next) => {
          setDiagnosisProposal((current) => current ? { ...current, jobId: next.jobId, status: "RUNNING_DIAGNOSIS", progressMessage: next.message || next.stage || "诊断正在后台运行…" } : current);
        }, 1500, { onError: (_error, count) => setDiagnosisProposal((current) => current ? { ...current, progressMessage: `连接暂时中断，正在自动重试（第 ${count} 次）…` } : current) });
        if (completed.status !== "COMPLETED" || !completed.result) {
          throw new DiagnosisApiError(completed.error || completed.message || "诊断任务未完成");
        }
        const response = completed.result;
        diagnosisId = response.diagnosisId;
        recommendationId = response.recommendations?.[0]?.id;
        if (!diagnosisId) throw new DiagnosisApiError("诊断服务未返回稳定 diagnosisId，已停止创建 Agent。");
        setContext({
          selectedDiagnosis: {
            id: diagnosisId,
            title: response.diagnosis.title,
            severity: response.diagnosis.risk_level,
            confidence: Math.round(response.diagnosis.confidence * 100),
            summary: response.diagnosis.summary,
          },
        });
        setDiagnosisProposal((current) => current ? { ...current, diagnosisId, recommendationId } : current);
        activeDiagnosisJobId.current = undefined;
      }
      setDiagnosisProposal((current) => current ? { ...current, status: "CREATING_AGENT", progressMessage: "Diagnosis 已保存，正在创建 Agent Run…" } : current);
      const run = await createAgentRun(proposal.goal, undefined, "real", { diagnosisId, recommendationId, intent: proposal.goal });
      addMessage({ role: "assistant", content: `多模态 AI 调查已启动\n${proposal.fieldName} · ${cropLabel(proposal.crop) ?? "当前作物"}\n正在分析图片、地块和天气，并检索农业知识与历史案例。` });
      setContext({ agentRunId: run.runId });
      setDiagnosisProposal(undefined);
      setAgentProgress({ runId: run.runId, diagnosisId, farmName: proposal.farmName, fieldName: proposal.fieldName, crop: proposal.crop });
      setAgentProgressRunId(run.runId);
    } catch (error: unknown) {
      setDiagnosisProposal((current) => current ? { ...current, diagnosisId, status: "FAILED", error: diagnosisErrorMessage(error) } : current);
    }
  };

  const uploadCopilotImage = async (file: File) => {
    if (!file.type.startsWith("image/")) {
      setImageUploadError("仅支持 JPG、PNG 等现场图片。");
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setImageUploadError("单张图片不能超过 10MB。");
      return;
    }
    if (context.selectedImages.length >= 5) {
      setImageUploadError("一次调查最多使用 5 张现场图片。");
      return;
    }
    setImageUploading(true);
    setImageUploadError("");
    try {
      const urls = await uploadDiagnosisImages([file], {
        farmId: canonicalContext.farmId > 0 ? canonicalContext.farmId : undefined,
        fieldId: canonicalContext.fieldId,
        pointId: canonicalContext.pointId,
        cameraId: context.selectedDevice?.id,
      });
      const uploaded = urls.map((url, index) => ({ id: `copilot-image-${Date.now()}-${index}`, name: file.name, url }));
      setContext({ selectedImages: [...context.selectedImages, ...uploaded].slice(0, 5) });
      setDiagnosisProposal((current) => current ? { ...current, imageUrls: [...current.imageUrls, ...urls].slice(0, 5) } : current);
      addMessage({ role: "assistant", content: `已接入现场图片“${file.name}”，下一次 AI 调查会把它作为视觉证据。` });
    } catch (error: unknown) {
      setImageUploadError(error instanceof Error ? error.message : "现场图片上传失败，请重试。");
    } finally {
      setImageUploading(false);
    }
  };

  function formatRouteMessage(result: CopilotRouteResult): string {
    if (result.intent === "MEMORY_WRITE_REQUEST" && result.resolutionStatus === "RESOLVED") return result.memoryStatus === "ACTIVE" ? "已记住这项偏好；它不会改变农业 Evidence、授权或人工审批。" : "这项长期偏好已处理。";
    if (result.intent === "MEMORY_FORGET_REQUEST" && result.resolutionStatus === "RESOLVED") return "已撤销这项长期偏好；后续新对话不会再加载它。";
    if (result.intent === "MEMORY_QUERY" && result.resolutionStatus === "RESOLVED") return result.relevantLongTermMemories?.length ? `当前账号可用的长期偏好：${result.relevantLongTermMemories.map((memory) => memory.displayText || `${memory.key}=${memory.value}`).join("；")}` : "当前上下文没有可用的长期偏好。";
    if (result.requiresClarification) return result.clarificationQuestion || "请明确要查询的对象或希望执行的动作。";
    if (result.resolutionStatus === "UNSUPPORTED") return result.clarificationQuestion || "当前请求不属于农业 Agent 支持范围。";
    if (result.resolutionStatus === "FORBIDDEN") return "当前账号无权访问该上下文，未继续处理。";
    if (result.resolutionStatus !== "RESOLVED") return result.clarificationQuestion || "当前上下文不可用，请重新选择对象。";
    const target = result.resolvedContext?.fieldId ? `地块 ${result.resolvedContext.fieldId}` : result.resolvedContext?.diagnosisId ? `诊断 ${result.resolvedContext.diagnosisId}` : "当前上下文";
    if (result.intent === "ACTION_REQUEST") return `${target} 已识别为 Agent 动作请求；下一步仍需经过 Evidence Sufficiency 和人工批准，不会直接创建 Operations 任务。`;
    if (result.intent === "DIAGNOSIS_REQUEST") return result.resolutionStatus === "RESOLVED"
      ? `${target} 已确认，正在启动多模态 AI 调查。你可以留在这里查看每一步和最终证据。`
      : `${target} 需要先确认分析地块。`;
    if (result.intent === "GENERAL_QA") return "我已读取当前页面上下文，正在用快速问答模型直接回答；复杂诊断仍会转交农业 Agent。";
    if (result.intent === "PRIORITIES_QUERY") return "我会读取当前农场真实的诊断与 Operations 数据，整理今天最需要处理的事项。";
    if (result.intent === "HISTORICAL_CASE_QUERY") return "我会使用当前现场图片检索专家确认的历史病例；没有图片时不会生成文本猜测。";
    if (result.intent === "FARM_STATUS_QUERY") return "我会读取当前农场的真实地块、天气、诊断和 Operations 上下文。";
    if (result.intent === "FIELD_STATUS_QUERY") return "我会读取当前地块的真实上下文、天气和近期农情记录。";
    if (result.intent === "KNOWLEDGE_QUERY") return "我会检索当前农业知识库，并把匹配到的知识来源和证据一起展示。";
    if (result.intent === "DIAGNOSIS_QUERY") return "我会读取最近一次已保存的 Diagnosis 及其 Evidence，不在聊天窗口临时生成诊断。";
    return `已识别为 ${result.intent}，路由到 ${result.capability}（${target}）。`;
  }

  function openAgentWorkspace(progress: CopilotAgentProgress) {
    close();
    navigate(`/analysis/${encodeURIComponent(progress.runId)}?diagnosisId=${encodeURIComponent(progress.diagnosisId)}`);
  }

  const reviewAgentAction = async (actionId: string, approved: boolean) => {
    if (!agentProgress) return;
    try {
      const next = approved
        ? await approveAgentAction(agentProgress.runId, actionId)
        : await rejectAgentAction(agentProgress.runId, actionId);
      setAgentProgress((current) => current ? { ...current, run: next, error: undefined } : current);
      setAgentStreamNonce((value) => value + 1);
    } catch (error: unknown) {
      setAgentProgress((current) => current ? { ...current, error: error instanceof Error ? error.message : "审批操作失败，请重试。" } : current);
    }
  };

  const cancelCopilotRequest = () => {
    if (!sending) return;
    requestSequence.current += 1;
    activeRequestId.current = undefined;
    activeChatController.current?.abort();
    const currentRun = agentProgress?.run;
    if (currentRun && ["CREATED", "PLANNING", "RUNNING"].includes(currentRun.status)) {
      void cancelAgentRun(currentRun.runId).then((run) => setAgentProgress((current) => current ? { ...current, run } : current)).catch(() => undefined);
    }
    setSending(false);
    setCommandBarLoading(undefined);
    setActivity((current) => current ? {
      ...current,
      status: "CANCELLED",
      steps: current.steps.map((step) => step.status === "RUNNING" ? { ...step, status: "SKIPPED", detail: "已由用户停止；未执行写入操作。" } : step),
    } : current);
  };

  function renderActivity() {
    if (!activity) return null;
    const statusLabel = activity.status === "RUNNING" ? "执行中" : activity.status === "COMPLETED" ? "已完成" : activity.status === "CANCELLED" ? "已停止" : "部分失败";
    const statusColor = activity.status === "RUNNING" ? "blue" : activity.status === "COMPLETED" ? "green" : activity.status === "CANCELLED" ? "default" : "red";
    return <Card className="farmap-copilot__activity" size="small" title={<Flex align="center" justify="space-between" gap={8}><span>Agent 路由与工具轨迹</span><Tag color={statusColor}>{statusLabel}</Tag></Flex>} extra={activity.status === "RUNNING" ? <Button type="link" size="small" danger onClick={cancelCopilotRequest}>停止</Button> : undefined}>
      <Text className="farmap-copilot__activity-query">“{activity.query}”</Text>
      <div className="farmap-copilot__activity-list">
        {activity.steps.map((step) => <div className={`farmap-copilot__activity-row farmap-copilot__activity-row--${step.status.toLowerCase()}`} key={step.id}>
          <span className="farmap-copilot__activity-glyph">{step.status === "COMPLETED" ? "✓" : step.status === "FAILED" ? "!" : step.status === "RUNNING" ? "●" : step.status === "SKIPPED" ? "–" : "○"}</span>
          <div><b>{step.label}</b><small>{step.detail}</small></div>
        </div>)}
      </div>
      <Text type="secondary" className="farmap-copilot__activity-note">显示的是可审计的路由、数据工具、RAG 和模型阶段，不展示模型私有逐字思维链。</Text>
    </Card>;
  }

  function renderAgentProgress() {
    if (!agentProgress) return null;
    const run = agentProgress.run;
    const diagnosis = run?.context.diagnosis ?? {};
    const severity = typeof diagnosis.severity === "string" ? diagnosis.severity : undefined;
    const steps = run?.steps ?? [];
    const isComplete = run?.status === "COMPLETED";
    const evidence = run?.evidence ?? [];
    const actions = run?.actions ?? [];
    const pendingActions = actions.filter((action) => action.approvalState === "REQUIRED");
    const diagnosisSummary = typeof diagnosis.summary === "string" ? diagnosis.summary : undefined;
    const modalities = Array.from(new Set(evidence.map((item) => evidenceModalityLabel(item.modality))));
    return <Card className="farmap-copilot__agent-progress" size="small" title={<Flex align="center" justify="space-between" gap={8}><span>多模态农业 Agent</span><Tag color={isComplete ? "green" : run?.status === "FAILED" ? "red" : "blue"}>{agentStatusLabel(run?.status)}</Tag></Flex>}>
      <Flex wrap gap={6}>
        <Tag color="green">{agentProgress.farmName}</Tag>
        <Tag color="blue">{agentProgress.fieldName}</Tag>
        {agentProgress.crop && <Tag>{cropLabel(agentProgress.crop)}</Tag>}
        {context.selectedImages.length > 0 && <Tag color="purple">现场图像 {context.selectedImages.length} 张</Tag>}
      </Flex>
      <div className="farmap-copilot__agent-phase-title">Agent 实时执行</div>
      <div className="farmap-copilot__agent-progress-list">
        {steps.length ? steps.map((step) => <div className={`farmap-copilot__agent-progress-row farmap-copilot__agent-progress-row--${step.status.toLowerCase()}`} key={step.id}>
          <span>{agentStepGlyph(step.status)}</span><b>{step.title}</b><small>{step.status === "RUNNING" ? "进行中" : step.status === "COMPLETED" ? "已完成" : step.status === "FAILED" ? "失败" : "等待中"}</small>
        </div>) : <div className="farmap-copilot__agent-progress-row farmap-copilot__agent-progress-row--running"><span>●</span><b>Agent Run</b><small>已创建，正在同步执行步骤</small></div>}
      </div>
      {isComplete && <Flex wrap gap={8} className="farmap-copilot__agent-summary">
        {severity && <Tag color={severity === "high" ? "red" : severity === "medium" ? "orange" : "green"}>风险等级 · {severity.toUpperCase()}</Tag>}
        <Tag>证据 · {evidence.length} 条</Tag>
        <Tag>建议 · {actions.length} 项</Tag>
      </Flex>}
      {isComplete && diagnosisSummary && <div className="farmap-copilot__agent-finding"><b>Agent 结论</b><span>{diagnosisSummary}</span></div>}
      {isComplete && modalities.length > 0 && <div className="farmap-copilot__agent-modalities"><b>本次实际使用</b><Flex wrap gap={6}>{modalities.map((item) => <Tag key={item}>{item}</Tag>)}</Flex></div>}
      {isComplete && evidence.length > 0 && <div className="farmap-copilot__agent-evidence"><b>多模态 RAG 命中</b>{evidence.slice(0, 4).map((item) => <div key={item.id}><span>{evidenceModalityLabel(item.modality)}</span><div><strong>{item.title}</strong><small>{item.summary}{item.score !== undefined ? ` · 相关度 ${Math.round(item.score * 100)}%` : ""}</small></div></div>)}</div>}
      {isComplete && actions.length > 0 && <div className="farmap-copilot__agent-action"><b>建议下一步</b><span>{actions[0].title}</span><small>{actions[0].detail}</small></div>}
      {pendingActions.length > 0 && <div className="farmap-copilot__agent-action">
        <b>需要人工批准</b>
        {pendingActions.map((action) => <Flex align="center" justify="space-between" gap={8} key={action.id}>
          <div><span>{action.title}</span><small>{action.detail}</small></div>
          <Flex gap={6}>
            <Button size="small" type="primary" onClick={() => void reviewAgentAction(action.id, true)}>批准执行</Button>
            <Button size="small" danger onClick={() => void reviewAgentAction(action.id, false)}>驳回</Button>
          </Flex>
        </Flex>)}
      </div>}
      {agentProgress.error && <Alert className="farmap-copilot__agent-progress-error" type="warning" showIcon message="Agent 状态同步暂不可用" description={agentProgress.error} />}
      <Button type={isComplete ? "primary" : "default"} block className="farmap-copilot__agent-progress-action" onClick={() => openAgentWorkspace(agentProgress)}>
        {isComplete ? "展开完整证据与模型信息" : "打开完整调查台（可选）"}
      </Button>
    </Card>;
  }

  function renderCommandBarResult() {
    if (commandBarLoading === "PRIORITIES") {
      return <Card className="farmap-copilot__command-result" size="small" title="今日优先事项"><Text type="secondary">正在读取诊断、任务与地块上下文…</Text></Card>;
    }
    if (commandBarLoading === "HISTORICAL_CASES") {
      return <Card className="farmap-copilot__command-result" size="small" title="相似历史病例"><Text type="secondary">正在检索专家确认的历史病例…</Text></Card>;
    }
    if (commandBarLoading === "FARM_STATUS") {
      return <Card className="farmap-copilot__command-result" size="small" title="当前农场状态"><Text type="secondary">正在读取地块、天气、诊断和任务上下文…</Text></Card>;
    }
    if (commandBarLoading === "FIELD_STATUS") {
      return <Card className="farmap-copilot__command-result" size="small" title="当前地块状态"><Text type="secondary">正在读取地块、天气和近期农情…</Text></Card>;
    }
    if (commandBarLoading === "DIAGNOSIS") {
      return <Card className="farmap-copilot__command-result" size="small" title="最近一次诊断"><Text type="secondary">正在读取已保存 Diagnosis 和 Evidence…</Text></Card>;
    }
    if (!commandBarResult) return null;
    if (commandBarResult.type === "PRIORITIES") {
      const data = commandBarResult.data;
      return <Card className="farmap-copilot__command-result" size="small" title="今日优先事项">
        {data.status === "READY" ? <>
          <Text type="secondary">基于当前农场真实诊断与未完成任务</Text>
          <Flex vertical gap={8} className="farmap-copilot__command-list">
            {data.items.map((item) => <div key={`${item.kind}-${item.id}`} className="farmap-copilot__command-item">
              <Flex align="center" justify="space-between" gap={8}>
                <Text strong>{item.title}</Text>
                <Tag color={item.priority === "HIGH" ? "red" : "orange"}>{item.priority === "HIGH" ? "高" : "中"}</Tag>
              </Flex>
              <span>{item.summary}</span>
              <Button type="link" size="small" onClick={() => openCommandItem(item.path)}>{item.kind === "DIAGNOSIS" ? "查看诊断" : "查看任务"}</Button>
            </div>)}
          </Flex>
          <Button type="link" size="small" onClick={() => openCommandItem("/overview")}>查看全部</Button>
        </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "当前没有可验证的优先事项" : "优先事项数据暂不可用"} description={data.reason} />}
      </Card>;
    }
    if (commandBarResult.type === "FARM_STATUS" || commandBarResult.type === "FIELD_STATUS") {
      const data = commandBarResult.data;
      return <Card className="farmap-copilot__command-result" size="small" title={data.title}>
        {data.status === "READY" ? <>
          {data.summary && <Text type="secondary">{data.summary}</Text>}
          <div className="farmap-copilot__evidence-heading">依据 / 证据</div>
          <Flex vertical gap={6} className="farmap-copilot__evidence-list">
            {data.evidence.map((item, index) => <div className="farmap-copilot__evidence-row" key={`${item.label}-${index}`}><span>{item.label}</span><b>{item.value}</b>{item.source && <Tag>{item.source}</Tag>}</div>)}
          </Flex>
          <div className="farmap-copilot__command-meta">来源 · {data.sources.join(" · ") || "暂不可用"}{data.updatedAt ? ` · 更新于 ${data.updatedAt}` : ""}</div>
        </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "当前没有可验证的农情信号" : "农情数据暂不可用"} description={data.reason} />}
      </Card>;
    }
    if (commandBarResult.type === "DIAGNOSIS") {
      const data = commandBarResult.data;
      return <Card className="farmap-copilot__command-result" size="small" title={data.title || "最近一次诊断"}>
        {data.status === "READY" ? <>
          <Flex wrap gap={6}>{data.riskLevel && <Tag color={data.riskLevel === "high" ? "red" : data.riskLevel === "medium" ? "orange" : "green"}>风险 · {data.riskLevel}</Tag>}{data.confidence !== undefined && <Tag>置信度 · {Math.round(data.confidence * 100)}%</Tag>}</Flex>
          {data.summary && <Text className="farmap-copilot__diagnosis-goal">{data.summary}</Text>}
          <div className="farmap-copilot__evidence-heading">依据 / 证据</div>
          <Flex vertical gap={6} className="farmap-copilot__evidence-list">
            {data.evidence.map((item, index) => <div className="farmap-copilot__evidence-row" key={`${item.label}-${index}`}><span>{item.label}</span><b>{item.value}</b>{item.source && <Tag>{item.source}</Tag>}</div>)}
          </Flex>
          <div className="farmap-copilot__command-meta">来源 · {data.sources.join(" · ") || "Diagnosis"}{data.updatedAt ? ` · 更新于 ${data.updatedAt}` : ""}</div>
          {data.diagnosisId && <Button type="link" size="small" onClick={() => openCommandItem(`/diagnosis/${encodeURIComponent(data.diagnosisId as string)}`)}>查看完整 Evidence</Button>}
        </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "当前没有已保存的诊断" : "诊断数据暂不可用"} description={data.reason} />}
      </Card>;
    }
    const data = commandBarResult.data;
    if (data.status === "IMAGE_REQUIRED") {
      return <Card className="farmap-copilot__command-result" size="small" title="相似历史病例">
        <Alert type="info" showIcon message="需要现场图片" description={data.reason} />
        <Button className="farmap-copilot__command-action" onClick={() => openDiagnosisPage("查找与当前情况最相似的历史病例")}>打开诊断页添加图片</Button>
      </Card>;
    }
    return <Card className="farmap-copilot__command-result" size="small" title="相似历史病例">
      {data.status === "READY" ? <>
        <Text type="secondary">来自真实历史病例索引 · 返回 {data.items.length} 条</Text>
        <Flex vertical gap={8} className="farmap-copilot__command-list">
          {data.items.map((item, index) => <div key={item.caseId ?? `case-${index}`} className="farmap-copilot__command-item">
            <Flex align="center" justify="space-between" gap={8}>
              <Text strong>{item.caseId ?? "未命名病例"}</Text>
              {item.similarityScore !== undefined && <Tag color="purple">匹配度 {item.similarityScore}%</Tag>}
            </Flex>
            <span>{[item.treeSpecies, item.diseaseType, item.pestType, item.nutritionStatus].filter(Boolean).join(" · ") || "专家案例，待结合当前证据核验"}</span>
            {item.updatedAt && <span className="farmap-copilot__command-meta">更新时间 · {item.updatedAt}</span>}
          </div>)}
        </Flex>
      </> : <Alert type={data.status === "EMPTY" ? "info" : "warning"} showIcon message={data.status === "EMPTY" ? "没有返回可验证的相似病例" : "历史病例检索暂不可用"} description={data.reason} />}
    </Card>;
  }

  return (
    <Drawer
      title={
        <Flex align="center" justify="space-between">
          <Space>
            <RobotOutlined />
            <span>FarMap 多模态农业 Agent</span>
          </Space>
          <Button
            type="text"
            size="small"
            icon={<DeleteOutlined />}
            onClick={() => { cancelCopilotRequest(); clearMessages(); setActivity(undefined); }}
            aria-label="清空农业 Agent 对话"
          />
          <Button
            type="text"
            size="small"
            icon={<PlusOutlined />}
            onClick={() => { cancelCopilotRequest(); clearCopilotConversation(); clearMessages(); setActivity(undefined); void createCopilotConversation(); }}
            aria-label="新建农业 Agent 对话"
          />
        </Flex>
      }
      placement="right"
      width={520}
      open={isOpen}
      onClose={close}
      destroyOnClose={false}
    >
      <details className="farmap-copilot__context farmap-copilot__context--compact">
        <summary>
          <div><Text strong>当前分析对象</Text><span>{[canonicalContext.farmId > 0 ? canonicalContext.farmName : "未选择农场", canonicalContext.fieldName || "未选择地块", cropLabel(canonicalContext.crop), growthStageDisplayName(canonicalContext.growthStage)].filter(Boolean).join(" · ")}</span></div>
          <Flex wrap gap={5}><Tag color="purple">{pageLabel}</Tag>{canonicalContext.crop && <Tag>{cropLabel(canonicalContext.crop)}</Tag>}{canonicalContext.weather && <Tag>{canonicalContext.weather.summary} · {canonicalContext.weather.temperature}</Tag>}</Flex>
        </summary>
        <div className="farmap-copilot__context-grid">
          <div className="farmap-copilot__context-cell"><span>农场</span><b>{canonicalContext.farmId > 0 ? canonicalContext.farmName : "未选择"}</b></div>
          <div className="farmap-copilot__context-cell"><span>地块</span><b>{canonicalContext.fieldName || "未选择"}</b></div>
          <div className="farmap-copilot__context-cell"><span>地图点</span><b>{canonicalContext.pointId || "未选择"}</b></div>
          <div className="farmap-copilot__context-cell"><span>摄像头</span><b>{context.selectedDevice?.name || "未选择"}</b></div>
          <div className="farmap-copilot__context-cell"><span>生育期</span><b>{growthStageDisplayName(canonicalContext.growthStage) || "—"}</b></div>
          <div className="farmap-copilot__context-cell"><span>现场图片</span><b>{context.selectedImages.length ? `${context.selectedImages.length} 张` : "未添加"}</b></div>
        </div>
      </details>

      <div className="farmap-copilot__actions">
        <Flex align="center" justify="space-between" gap={8}>
          <Text strong>我可以帮你</Text>
          <Text type="secondary">快捷操作</Text>
        </Flex>
        <Flex wrap gap={6} className="farmap-copilot__action-list">
          {contextualActions.map((action) => <Button key={action.label} size="small" onClick={() => setDraft(action.prompt)}>{action.label}</Button>)}
        </Flex>
      </div>

      {(copilotContext || memories.length > 0) && <details className="farmap-copilot__secondary-context">
        <summary>对话上下文与长期记忆 <Tag>{memories.length} 条偏好</Tag></summary>
      {copilotContext && (
        <div className="farmap-copilot__discussion">
          <Flex align="center" justify="space-between" gap={8}>
            <Text strong>当前讨论</Text>
            <Tag color="green">{getOverviewCopilotContextLabel(copilotContext.contextType)}</Tag>
          </Flex>
          <b className="farmap-copilot__discussion-title">{copilotContext.title}</b>
          {copilotContext.field && <span className="farmap-copilot__discussion-line">{copilotContext.field.name}{copilotContext.field.crop ? ` · ${copilotContext.field.crop}` : ""}{copilotContext.field.area !== undefined ? ` · ${copilotContext.field.area} ha` : ""}</span>}
          {copilotContext.risk && <span className="farmap-copilot__discussion-line">关联风险 · {copilotContext.risk.title}{copilotContext.risk.confidenceLabel ? ` · ${copilotContext.risk.confidenceLabel}` : ""}</span>}
          {copilotContext.diagnosis && <span className="farmap-copilot__discussion-line">诊断 · {copilotContext.diagnosis.title}{copilotContext.diagnosis.confidence !== undefined ? ` · ${copilotContext.diagnosis.confidence}% 诊断置信度` : ""}</span>}
          {copilotContext.recommendation && <span className="farmap-copilot__discussion-line">建议动作 · {copilotContext.recommendation.recommendedAction ?? copilotContext.recommendation.title}</span>}
          {copilotContext.operation && <span className="farmap-copilot__discussion-line">任务状态 · {copilotContext.operation.status} · 来源 {copilotContext.operation.source ?? "未关联"}</span>}
          {copilotContext.agent && <span className="farmap-copilot__discussion-line">分析进度 · {copilotContext.agent.status}{copilotContext.agent.approvalState ? ` · ${copilotContext.agent.approvalState}` : ""}</span>}
          {copilotContext.agent?.finding && <span className="farmap-copilot__discussion-line">当前发现 · {copilotContext.agent.finding}</span>}
          {copilotContext.agent?.proposedAction && <span className="farmap-copilot__discussion-line">行动提案 · {copilotContext.agent.proposedAction}</span>}
          <span className="farmap-copilot__discussion-evidence">证据 · {copilotContext.evidence.labels.length ? copilotContext.evidence.labels.join(" · ") : "来源暂不可用"}{copilotContext.evidence.count !== undefined ? ` · ${copilotContext.evidence.count} 条` : ""}{copilotContext.evidence.historyUnavailable ? " · 历史案例暂不可用" : ""}</span>
          {copilotContext.freshness?.updatedAt && <span className="farmap-copilot__discussion-freshness">{copilotContext.mode === "DEMO" ? "演示上下文" : copilotContext.freshness.stale ? "数据更新时间较旧" : "数据更新时间"} · {copilotContext.freshness.updatedAt}</span>}
        </div>
      )}

      <div className="farmap-copilot__memory">
        <Flex align="center" justify="space-between" gap={8}>
          <Text strong>长期记忆</Text>
          <Tag>{memoryLoading ? "读取中…" : `${memories.length} 条有效偏好`}</Tag>
        </Flex>
        {memories.length === 0 ? (
          <Text type="secondary">你可以说“记住我更关注低农药风险”，后续对话会使用这项偏好，也可以随时撤销。</Text>
        ) : (
          <Flex vertical gap={6} className="farmap-copilot__memory-list">
            {memories.slice(0, 4).map((memory) => (
              <div className="farmap-copilot__memory-row" key={memory.memoryId}>
                <span>{memory.displayText || `${memory.key}：${memory.value}`}</span>
                <Button type="link" size="small" onClick={() => void revokeCopilotMemory(memory.memoryId).then(() => setMemories((current) => current.filter((item) => item.memoryId !== memory.memoryId))).catch(() => undefined)}>撤销</Button>
              </div>
            ))}
          </Flex>
        )}
      </div>
      </details>}

      {renderActivity()}

      {diagnosisProposal && (
        <Card className="farmap-copilot__diagnosis-proposal" size="small" title="多模态调查正在准备">
          <Flex wrap gap={6}>
            <Tag color="green">{diagnosisProposal.farmName}</Tag>
            <Tag color="blue">{diagnosisProposal.fieldName}</Tag>
            {diagnosisProposal.crop && <Tag>{cropLabel(diagnosisProposal.crop)}</Tag>}
          </Flex>
          <Text className="farmap-copilot__diagnosis-goal">调查目标：{diagnosisProposal.goal}</Text>
          <div className="farmap-copilot__diagnosis-sources">
            <Text type="secondary">可用数据</Text>
            <span>✓ 地块</span>
            <span>{context.currentWeather ? "✓" : "○"} 天气</span>
            <span>✓ 历史农情</span>
            <span>● 农业知识检索</span>
            <span>● 历史案例检索</span>
          </div>
          <Text type="secondary">现场图片：{diagnosisProposal.imageUrls.length ? `已附带 ${diagnosisProposal.imageUrls.length} 张` : "未提供"}</Text>
          {diagnosisProposal.error && <Alert className="farmap-copilot__diagnosis-error" type="error" showIcon message={diagnosisProposal.diagnosisId ? "Diagnosis 已保存，但 Agent 尚未创建" : "AI 调查未完成"} description={diagnosisProposal.error} />}
          {diagnosisProposal.progressMessage && !diagnosisProposal.error && (
            <Alert type="info" showIcon message={diagnosisProposal.progressMessage} description="任务在后台继续运行，可以关闭 Agent 面板或浏览其他页面。" />
          )}
          <Flex wrap gap={8} className="farmap-copilot__diagnosis-actions">
            <Button type="primary" loading={["CREATING_DIAGNOSIS", "RUNNING_DIAGNOSIS", "CREATING_AGENT"].includes(diagnosisProposal.status)} onClick={() => void startDiagnosis()}>
              {diagnosisProposal.diagnosisId ? "重试创建 Agent" : "立即开始"}
            </Button>
            <Button disabled={["CREATING_DIAGNOSIS", "RUNNING_DIAGNOSIS", "CREATING_AGENT"].includes(diagnosisProposal.status)} onClick={() => openDiagnosisPage(diagnosisProposal.goal, diagnosisProposal)}>
              添加图片 / 高级设置
            </Button>
          </Flex>
        </Card>
      )}

      {diagnosisPageGoal && !diagnosisProposal && (
        <Card className="farmap-copilot__diagnosis-proposal" size="small" title="需要确认分析对象">
          <Text type="secondary">农业 Agent 没有拿到可确认的地块上下文，请先在高级设置中选择地块。</Text>
          <Button className="farmap-copilot__diagnosis-actions" onClick={() => openDiagnosisPage(diagnosisPageGoal)}>打开高级调查设置</Button>
        </Card>
      )}

      {renderAgentProgress()}

      {renderCommandBarResult()}

      <Divider />

      <Flex vertical gap={12} className="farmap-copilot__messages">
        {messages.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有对话" />
        ) : (
          <>{messages.length > 6 && <Text type="secondary" className="farmap-copilot__older-messages">已收起前 {messages.length - 6} 条对话，保留最近上下文。</Text>}{messages.slice(-6).map((message) => (
            <div
              key={message.id}
              className={`farmap-copilot__message farmap-copilot__message--${message.role}`}
            >
              <MarkdownMessage text={message.content} />
              {message.role === "assistant" && message.knowledgeCount !== undefined && <small className="farmap-copilot__knowledge-note">根据当前知识库找到 {message.knowledgeCount} 条相关知识</small>}
              {message.role === "assistant" && message.referenceImages && message.referenceImages.length > 0 && (
                <div
                  className="farmap-copilot__reference-images"
                  style={{ display: "flex", gap: 8, overflowX: "auto", maxWidth: "100%", paddingTop: 8, paddingBottom: 2 }}
                >
                  <Image.PreviewGroup>
                    {message.referenceImages.map((image) => (
                      <div key={image.refId} data-reference-image="true" style={{ flex: "0 0 92px", minWidth: 0 }}>
                        <Image
                          src={image.imageUrl}
                          alt={image.label}
                          width={92}
                          height={68}
                          fallback="data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs="
                          onError={(event) => {
                            event.currentTarget.closest("[data-reference-image='true']")?.setAttribute("hidden", "true");
                          }}
                          style={{ objectFit: "cover", borderRadius: 6, border: "1px solid #d9e8df" }}
                        />
                        <small style={{ display: "block", marginTop: 3, color: "#6b7280", lineHeight: 1.35, wordBreak: "break-all" }}>{image.label}</small>
                      </div>
                    ))}
                  </Image.PreviewGroup>
                </div>
              )}
            </div>
          ))}</>
        )}
      </Flex>

      <div className="farmap-copilot__composer">
        <Title level={5} style={{ marginTop: 0 }}>
          询问当前农情
        </Title>
        <Flex wrap gap={6} className="farmap-copilot__quick-commands">
          <Button size="small" onClick={() => setDraft("我的柑橘现在该做什么？")}>我的柑橘现在该做什么？</Button>
          <Button size="small" onClick={() => setDraft("叶片发黄掉果怎么办？")}>叶片发黄掉果怎么办？</Button>
          <Button size="small" onClick={() => setDraft("帮我安排本周农事")}>帮我安排本周农事</Button>
        </Flex>
        <Flex align="center" gap={8} className="farmap-copilot__image-input">
          <Upload
            accept="image/jpeg,image/png,image/webp"
            showUploadList={false}
            beforeUpload={(file) => { void uploadCopilotImage(file); return Upload.LIST_IGNORE; }}
            disabled={imageUploading || context.selectedImages.length >= 5}
          >
            <Button icon={<FileImageOutlined />} loading={imageUploading}>添加现场图片</Button>
          </Upload>
          <Text type="secondary">{context.selectedImages.length ? `已接入 ${context.selectedImages.length}/5 张视觉证据` : "可选：摄像头截图或手机照片"}</Text>
        </Flex>
        {imageUploadError && <Alert type="warning" showIcon message={imageUploadError} closable onClose={() => setImageUploadError("")} />}
        <Input.TextArea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              void handleSend();
            }
          }}
          placeholder="例如：A-12 地块近期需要关注什么？"
          autoSize={{ minRows: 3, maxRows: 5 }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          style={{ marginTop: 8 }}
          onClick={() => void handleSend()}
          disabled={!draft.trim()}
        >
          {sending ? "执行中…" : "发送"}
        </Button>
      </div>
    </Drawer>
  );
}

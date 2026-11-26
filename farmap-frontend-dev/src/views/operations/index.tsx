import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { openOverviewCopilot, normalizeOverviewEvidenceSources } from "@/features/overview/copilot/context";
import { getRealOperationsViewModel, getUnavailableOperationsViewModel, selectOperationsTask } from "@/features/operations/adapter";
import OperationsCenter from "@/features/operations/components/OperationsCenter";
import type { OperationsOutcome, OperationsTaskRaw, OperationsTaskViewModel, OperationsViewModel } from "@/types/operations";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import { loadGuidanceContext, type FarmGuidanceContext } from "@/features/agricultural/context";
import { listDiagnosisFields } from "@/features/diagnosis/service";

type OperationsTasksResult = { code: number; data?: { items?: OperationsTaskRaw[]; count?: number } };
type OperationsOutcomeResult = { code: number; data?: OperationsOutcome };

const guidanceLabels: Record<string, string> = { body: "树体管理", fertite: "水肥管理", pest: "病虫管理", park: "清园操作" };

function GuidanceContent({ guidance }: { guidance: FarmGuidanceContext }) {
  if (guidance.status !== "READY" || guidance.entries.length === 0) return null;
  const groups = new Map<string, FarmGuidanceContext["entries"]>();
  guidance.entries.forEach((entry) => {
    const key = entry.guidanceType ?? "other";
    groups.set(key, [...(groups.get(key) ?? []), entry]);
  });
  return <section aria-label="月度农事指导"><h3>{guidance.month + 1} 月农事指导</h3><div className="fm-current-guidance__grid">{[...groups.entries()].map(([type, entries]) => <details key={type} className="fm-current-guidance__group">
    <summary>{guidanceLabels[type] ?? "农业指导"}<span>{entries.length} 条</span></summary>
    <div>{entries.map((entry) => <p key={entry.id ?? `${type}-${entry.text.slice(0, 14)}`}><b>{entry.formula ? "配方推荐：" : "操作建议："}</b>{entry.text}</p>)}</div>
  </details>)}</div></section>;
}

export default function Operations() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const showRecommendations = params.get("view") === "recommendations";
  const token = permanence.token.useToken();
  const currentFarm = useAIContextStore((state) => state.context.currentFarm);
  const fieldId = useAIContextStore((state) => state.context.currentField?.id);
  const farm = useMemo(() => ({ ...currentFarm, type: "" }), [currentFarm]);
  const [viewModel, setViewModel] = useState<OperationsViewModel>(() => getUnavailableOperationsViewModel(farm, "正在读取 Operations 任务…"));
  const [guidance, setGuidance] = useState<FarmGuidanceContext>();
  const setAIContext = useAIContextStore((state) => state.setContext);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const setDraft = useCopilotStore((state) => state.setDraft);
  const openCopilot = useCopilotStore((state) => state.open);
  const referenceDate = useAIContextStore((state) => state.context.referenceDate);
  const monthValue = referenceDate ? Number(referenceDate.slice(5, 7)) : 0;
  const month = monthValue >= 1 && monthValue <= 12 ? monthValue - 1 : new Date().getMonth();

  useEffect(() => {
    let cancelled = false;
    setViewModel(getUnavailableOperationsViewModel(farm, farm.id > 0 ? "正在读取当前范围的任务。" : "请先选择农场。"));
    if (farm.id <= 0) return () => { cancelled = true; };
    Promise.all([listDiagnosisFields(farm.id), req.get<OperationsTasksResult>("/api/operations/tasks", { Authorization: `Bearer ${token}` })])
      .then(async ([fields, response]) => {
        if (cancelled) return;
        const fieldNames = new Map(fields.map((field) => [field.fieldId, field.name]));
        const items = Array.isArray(response.data?.items) ? response.data.items.flatMap((item) => {
          const id = typeof item.fieldId === "string" ? item.fieldId : "";
          return fieldNames.has(id) && (!fieldId || id === fieldId) ? [{ ...item, fieldName: fieldNames.get(id) }] : [];
        }) : [];
        const enriched = await Promise.all(items.map(async (item) => {
          if (String(item.status ?? "").toUpperCase() !== "COMPLETED" || typeof item.taskId !== "string" || !item.taskId) return item;
          try {
            const outcome = await req.get<OperationsOutcomeResult>(`/api/operations/tasks/${encodeURIComponent(item.taskId)}/outcome`, { Authorization: `Bearer ${token}` });
            return outcome.data ? { ...item, outcome: outcome.data } : item;
          } catch {
            return item;
          }
        }));
        if (!cancelled) setViewModel(getRealOperationsViewModel(farm, enriched));
      })
      .catch(() => {
        if (!cancelled) setViewModel(getUnavailableOperationsViewModel(farm));
      });
    return () => { cancelled = true; };
  }, [farm, fieldId, token]);

  useEffect(() => {
    let cancelled = false;
    setGuidance(undefined);
    if (farm.id <= 0 || !showRecommendations) return () => { cancelled = true; };
    loadGuidanceContext(farm.id, token, month)
      .then((value) => { if (!cancelled) setGuidance(value); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [farm.id, month, showRecommendations, token]);

  const openTaskCopilot = (task: OperationsTaskViewModel) => {
    const copilotMode = viewModel.mode === "DEMO" || viewModel.mode === "STALE" || viewModel.mode === "UNAVAILABLE" ? viewModel.mode : "REAL";
    openOverviewCopilot({
      contextType: "OPERATION",
      mode: copilotMode,
      page: "/operations/tasks",
      farm: { id: viewModel.farm.id, name: viewModel.farm.name },
      field: task.fieldId ? { id: task.fieldId, name: task.fieldName ?? task.fieldId } : undefined,
      title: task.title,
      summary: task.goal ?? `任务状态：${task.statusLabel}；来源：${task.sourceLabel}`,
      operation: { taskId: task.taskId, title: task.title, status: task.statusLabel, source: task.sourceLabel },
      evidence: normalizeOverviewEvidenceSources([], copilotMode),
      freshness: viewModel.freshness,
    }, { setAIContext, setCopilotContext, setDraft, open: openCopilot });
    if (task.taskId && !task.isDemo) setDraft(`打开当前农事任务「${task.title}」，查看进度和执行反馈。`);
  };

  const selectTask = (task: OperationsTaskViewModel) => {
    setViewModel((current) => selectOperationsTask(current, task.key));
    setAIContext({
      taskId: task.taskId,
      currentField: task.fieldId ? { id: task.fieldId, name: task.fieldName ?? task.fieldId, area: 0, crop: "" } : undefined,
      currentCrop: undefined,
      currentPhenology: undefined,
    });
  };
  return <div className="fm-page fm-operations-page">
    <OperationsCenter viewModel={viewModel} onSelectTask={selectTask} onAskAi={openTaskCopilot}
      onOpenDiagnosis={(id) => navigate(`/diagnosis/${id}`)} onOpenAgent={(id) => navigate(`/analysis/${id}`)}
      onOpenAnalysis={(runId, selectedField) => useCopilotStore.getState().openAnalysis({ runId, farmId: farm.id, fieldId: selectedField })}
      onPlanInAgent={() => {
        useCopilotStore.getState().closeAnalysis();
        setAIContext({ taskId: undefined, agentRunId: undefined, currentPage: "/planning" });
        setCopilotContext(undefined);
        setDraft("请结合当前农场和地块的情况，帮我制定一份农事计划草稿。");
        openCopilot();
      }}
      guidanceSection={guidance ? <GuidanceContent guidance={guidance} /> : undefined} />
  </div>;
}


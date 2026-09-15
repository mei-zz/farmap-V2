import { useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { normalizeOverviewEvidenceSources, openOverviewCopilot } from "@/features/overview/copilot/context";
import { getDemoOperationsViewModel, selectOperationsTask } from "@/features/operations/adapter";
import OperationsCenter from "@/features/operations/components/OperationsCenter";
import type { OperationsTaskViewModel } from "@/types/operations";

export default function DemoOperationsCenterPage() {
  const navigate = useNavigate();
  const initial = useMemo(() => getDemoOperationsViewModel(), []);
  const [viewModel, setViewModel] = useState(initial);
  const setAIContext = useAIContextStore((state) => state.setContext);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const setDraft = useCopilotStore((state) => state.setDraft);
  const openCopilot = useCopilotStore((state) => state.open);

  const openTaskCopilot = (task: OperationsTaskViewModel) => openOverviewCopilot({
    contextType: "OPERATION",
    mode: "DEMO",
    page: "/operations/tasks",
    farm: { id: viewModel.farm.id, name: viewModel.farm.name },
    field: task.fieldId ? { id: task.fieldId, name: task.fieldName ?? task.fieldId } : undefined,
    title: task.title,
    summary: "当前为演示任务，未关联真实 AI Run、Diagnosis 或 Operations 写入状态。",
    operation: { title: task.title, status: task.statusLabel, source: task.sourceLabel },
    evidence: normalizeOverviewEvidenceSources([], "DEMO"),
    freshness: viewModel.freshness,
  }, { setAIContext, setCopilotContext, setDraft, open: openCopilot });

  return <div className="fm-page fm-operations-page"><OperationsCenter viewModel={viewModel} onSelectTask={(task) => setViewModel((current) => selectOperationsTask(current, task.key))} onAskAi={openTaskCopilot} onOpenDiagnosis={(id) => navigate(`/diagnosis/${id}`)} onOpenAgent={(id) => navigate(`/analysis/${id}`)} /></div>;
}

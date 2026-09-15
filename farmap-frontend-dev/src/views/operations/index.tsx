import { useEffect, useMemo, useState } from "react";
import { Card, Empty, Skeleton, Tag, Typography } from "antd";
import { ReadOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router";
import { useFarmStore } from "@/store/farm";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { openOverviewCopilot, normalizeOverviewEvidenceSources } from "@/features/overview/copilot/context";
import { getRealOperationsViewModel, getUnavailableOperationsViewModel, selectOperationsTask } from "@/features/operations/adapter";
import OperationsCenter from "@/features/operations/components/OperationsCenter";
import type { OperationsOutcome, OperationsTaskRaw, OperationsTaskViewModel, OperationsViewModel } from "@/types/operations";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import { loadGuidanceContext, type FarmGuidanceContext } from "@/features/agricultural/context";

const { Text } = Typography;
type OperationsTasksResult = { code: number; data?: { items?: OperationsTaskRaw[]; count?: number } };
type OperationsOutcomeResult = { code: number; data?: OperationsOutcome };

const guidanceLabels: Record<string, string> = { body: "树体管理", fertite: "水肥管理", pest: "病虫管理", park: "清园操作" };

function getFarmContext(stored: ReturnType<typeof permanence.farm.useFarmStore>, current: { id: number; name: string; address: string; type: string }) {
  return {
    id: stored?.id ?? current.id,
    name: stored?.name || current.name || "当前农场",
    address: stored?.address || current.address,
    type: stored?.type ?? current.type,
  };
}

function GuidanceContent({ guidance }: { guidance: FarmGuidanceContext }) {
  if (guidance.status !== "READY" || guidance.entries.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前农场暂无本月农业指导" />;
  const groups = new Map<string, FarmGuidanceContext["entries"]>();
  guidance.entries.forEach((entry) => {
    const key = entry.guidanceType ?? "other";
    groups.set(key, [...(groups.get(key) ?? []), entry]);
  });
  return <div className="fm-current-guidance__grid">{[...groups.entries()].map(([type, entries]) => <details key={type} open className="fm-current-guidance__group">
    <summary><Tag>{guidanceLabels[type] ?? "农业指导"}</Tag><span>{entries.length} 条</span></summary>
    <div>{entries.map((entry) => <p key={entry.id ?? `${type}-${entry.text.slice(0, 14)}`}><b>{entry.formula ? "配方推荐：" : "操作建议："}</b>{entry.text}</p>)}</div>
  </details>)}</div>;
}

export default function Operations() {
  const navigate = useNavigate();
  const token = permanence.token.useToken();
  const storedFarm = permanence.farm.useFarmStore();
  const aiCurrentFarm = useAIContextStore((state) => state.context.currentFarm);
  const currentFarmId = useFarmStore((state) => state.id);
  const currentFarmName = useFarmStore((state) => state.name);
  const currentFarmAddress = useFarmStore((state) => state.address);
  const currentFarmType = useFarmStore((state) => state.type);
  const storeFarm = { id: currentFarmId, name: currentFarmName, address: currentFarmAddress, type: currentFarmType };
  const selectedFarm = currentFarmId > 0
    ? storeFarm
    : aiCurrentFarm.id > 0
      ? { id: aiCurrentFarm.id, name: aiCurrentFarm.name, address: aiCurrentFarm.address, type: currentFarmType }
      : undefined;
  const farm = useMemo(() => selectedFarm ? getFarmContext(undefined, selectedFarm) : getFarmContext(storedFarm, storeFarm), [
    storedFarm?.id,
    storedFarm?.name,
    storedFarm?.address,
    storedFarm?.type,
    currentFarmId,
    currentFarmName,
    currentFarmAddress,
    currentFarmType,
    aiCurrentFarm.id,
    aiCurrentFarm.name,
    aiCurrentFarm.address,
  ]);
  const [viewModel, setViewModel] = useState<OperationsViewModel>(() => getUnavailableOperationsViewModel(farm, "正在读取 Operations 任务…"));
  const [guidance, setGuidance] = useState<FarmGuidanceContext>();
  const [guidanceLoading, setGuidanceLoading] = useState(true);
  const [guidanceError, setGuidanceError] = useState<string>();
  const setAIContext = useAIContextStore((state) => state.setContext);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const setDraft = useCopilotStore((state) => state.setDraft);
  const openCopilot = useCopilotStore((state) => state.open);
  const month = new Date().getMonth();

  useEffect(() => {
    let cancelled = false;
    if (farm.id <= 0) return () => { cancelled = true; };
    req.get<OperationsTasksResult>("/api/operations/tasks", { Authorization: `Bearer ${token}` })
      .then(async (response) => {
        if (cancelled) return;
        const items = Array.isArray(response.data?.items) ? response.data.items : [];
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
  }, [farm, token]);

  useEffect(() => {
    let cancelled = false;
    setGuidance(undefined);
    setGuidanceError(undefined);
    if (farm.id <= 0) {
      setGuidanceLoading(false);
      return () => { cancelled = true; };
    }
    setGuidanceLoading(true);
    loadGuidanceContext(farm.id, token, month)
      .then((value) => { if (!cancelled) { setGuidance(value); setGuidanceError(undefined); } })
      .catch((error: unknown) => { if (!cancelled) setGuidanceError(error instanceof Error ? error.message : "农业指导不可用"); })
      .finally(() => { if (!cancelled) setGuidanceLoading(false); });
    return () => { cancelled = true; };
  }, [farm.id, month, token]);

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
    <OperationsCenter viewModel={viewModel} onSelectTask={selectTask} onAskAi={openTaskCopilot} onOpenDiagnosis={(id) => navigate(`/diagnosis/${id}`)} onOpenAgent={(id) => navigate(`/analysis/${id}`)} />
    <div className="fm-operations-support">
      <Card title={<><ReadOutlined />&nbsp;当前农业指导</>} bordered={false} className="fm-operations-guidance-card">
        {guidanceLoading ? <Skeleton active paragraph={{ rows: 4 }} /> : guidanceError ? <div className="fm-operations-state"><span>{guidanceError}</span></div> : guidance ? <><Text type="secondary">第 {guidance.month + 1} 月 · 只展示当前农场真实指导</Text><GuidanceContent guidance={guidance} /></> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前农场暂无本月农业指导" />}
      </Card>
    </div>
  </div>;
}

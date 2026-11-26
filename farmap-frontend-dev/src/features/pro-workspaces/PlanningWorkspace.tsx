import Operations from "@/views/operations";
import { Link, useNavigate, useSearchParams } from "react-router";
import { OperationsTaskView } from "@/features/copilot/OperationsTaskViews";
import { useWorkspaceSession } from "@/layout/session";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { operationsViews } from "@/routes/navigation";
import "./planning-knowledge.css";

export default function PlanningWorkspace() {
  const [params] = useSearchParams();
  const requested = params.get("view");
  const view = requested === "calendar" ? "calendar" : requested === "archive" ? "records" : "tasks";
  const { role } = useWorkspaceSession();
  const context = useAIContextStore((state) => state.context);
  const navigate = useNavigate();
  if (requested === "recommendations" || requested === "records" || requested === "board") {
    return <div className="fm-pro-workspace"><Operations /></div>;
  }
  const farmHint = Number(params.get("farmId"));
  const farmId = Number.isSafeInteger(farmHint) && farmHint > 0 ? farmHint : context.currentFarm.id;
  const fieldId = params.get("fieldId") ?? (!params.has("farmId") ? context.currentField?.id : undefined);
  return <main className="fm-pro-workspace fm-page">
    <header className="fm-pro-heading"><div><h1>{operationsViews[view].label}</h1></div><Link to="/workspace">返回 Agent 工作台</Link></header>
    <nav className="fm-operations-view-tabs" aria-label="农事专业视图">
      {Object.entries(operationsViews).map(([key, item]) => {
        const next = new URLSearchParams(params);
        next.set("view", item.query);
        return <Link key={key} to={{ pathname: "/planning", search: next.toString() }} aria-current={key === view ? "page" : undefined}>{item.label}</Link>;
      })}
    </nav>
    {farmId > 0 ? <OperationsTaskView key={`${farmId}:${fieldId ?? ""}:${view}`} farmId={farmId} fieldId={fieldId} view={view}
      from={params.get("from") ?? undefined} to={params.get("to") ?? undefined} filter={view === "tasks" ? params.get("filter") ?? undefined : undefined} professional allowBatch={role === "admin" && view === "tasks"}
      onOpenTask={(taskId) => {
        useAIContextStore.getState().setContext({ taskId });
        const copilot = useCopilotStore.getState();
        copilot.closeAnalysis();
        copilot.setContext(undefined);
        copilot.setDraft(`查看任务 ${taskId} 的详情和执行反馈。`);
        navigate("/workspace");
      }} /> : <p>请先选择农场。</p>}
  </main>;
}

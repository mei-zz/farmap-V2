import { BookOpen, Check, ChevronDown, CircleAlert, CirclePause, Globe, LoaderCircle, Sparkles, Wrench } from "lucide-react";
import { chatToolLabel, isKnowledgeTool, type ChatActivity } from "@/features/copilot/chatActivity";

export function CopilotTurnActivity({ activity, pending, answering, preparing }: { activity?: ChatActivity; pending: boolean; answering: boolean; preparing?: string }) {
  const knowledge = activity?.knowledge;
  const knowledgeLabel = knowledge?.unavailable ? "知识库暂不可用" : knowledge ? `知识库返回 ${knowledge.count} 条信息` : undefined;
  const tools = activity?.tools.filter((tool) => !knowledge || tool.web || !isKnowledgeTool(tool.name)) ?? [];
  const activeTool = tools.filter((tool) => tool.status === "running").at(-1);
  const label = pending
    ? activeTool ? `正在${chatToolLabel(activeTool.name, activeTool.web)}`
      : activity?.thinking === "running" ? "正在思考" : answering ? "正在生成回答" : activity?.modelStarted ? "模型正在处理" : preparing ?? "正在处理请求"
    : undefined;
  const hasDetails = Boolean(activity?.thinking || activity?.modelStarted || tools.length);
  if (!label && !hasDetails && !knowledgeLabel) return null;

  return <div className="farmap-chat-activity">
    {label && <div className="farmap-chat-activity__status" role="status"><LoaderCircle size={15} aria-hidden="true" />{label}</div>}
    {activity && hasDetails ? <details>
      <summary><ChevronDown size={14} aria-hidden="true" />执行过程{knowledgeLabel ? <span>{knowledgeLabel}</span> : tools.length > 0 && <span>{tools.length} 项工具操作</span>}</summary>
      <ul>
        {(activity.thinking || activity.modelStarted) && <li><Sparkles size={14} aria-hidden="true" />{activity.thinking ? "模型思考" : "模型处理"}<span>{activity.thinking === "done" ? "已完成" : pending ? "进行中" : "已结束"}</span></li>}
        {tools.map((tool) => {
          const Icon = tool.web ? Globe : Wrench;
          const StateIcon = tool.status === "done" ? Check : tool.status === "failed" ? CircleAlert : pending ? LoaderCircle : CirclePause;
          const state = tool.status === "done" ? "已完成" : tool.status === "failed" ? "失败" : pending ? "进行中" : "未完成";
          return <li key={tool.key}><Icon size={14} aria-hidden="true" />{chatToolLabel(tool.name, tool.web)}<span><StateIcon size={13} aria-hidden="true" />{state}</span></li>;
        })}
      </ul>
    </details> : knowledgeLabel && <div className="farmap-chat-activity__knowledge"><BookOpen size={14} aria-hidden="true" />{knowledgeLabel}</div>}
  </div>;
}

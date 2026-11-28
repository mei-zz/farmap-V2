type ActivityStatus = "running" | "done" | "failed";
type ToolActivity = { key: string; name: string; web: boolean; status: ActivityStatus };
export type ChatActivity = {
  thinking?: ActivityStatus;
  modelStarted?: boolean;
  knowledge?: { count: number; unavailable: boolean };
  tools: ToolActivity[];
};

const toolEvents = {
  "tool.started": "running", "tool.updated": "running", "tool.completed": "done", "tool.failed": "failed",
  tool_execution_start: "running", tool_execution_update: "running", tool_execution_end: "done",
  "web.search.started": "running", "web.search.completed": "done", "web.search.failed": "failed",
} as const;
const isWebSearch = (name: string) => /^(web[_./-]?search|search[_./-]?(the[_./-]?)?web|internet[_./-]?search|browse[_./-]?web)(?:$|[_./-])/i.test(name);

export function applyChatActivity(previous: ChatActivity | undefined, event: string, data: unknown): ChatActivity | undefined {
  if (!data || typeof data !== "object") return previous;
  const value = data as Record<string, unknown>;
  const type = event === "agent.runtime" ? value.type : event;
  const activity = previous ?? { tools: [] };
  if (event === "knowledge") {
    const count = typeof value.count === "number" && Number.isSafeInteger(value.count) && value.count >= 0
      ? value.count
      : Array.isArray(value.items) ? value.items.length : undefined;
    if (count === undefined) return previous;
    const unavailable = value.status === "UNAVAILABLE";
    if (activity.knowledge?.count === count && activity.knowledge.unavailable === unavailable) return previous;
    return { ...activity, knowledge: { count, unavailable } };
  }
  if (event === "agent.runtime" && type === "turn_start") {
    return activity.modelStarted ? previous : { ...activity, modelStarted: true };
  }
  if (type === "thinking_start" || type === "thinking_end") {
    const thinking = type === "thinking_start" ? "running" : "done";
    return activity.thinking === thinking ? previous : { ...activity, thinking };
  }
  if (typeof type !== "string" || !Object.hasOwn(toolEvents, type)) return previous;
  const rawName = value.toolName ?? value.name;
  const web = type.startsWith("web.search.") || (typeof rawName === "string" && isWebSearch(rawName));
  const name = typeof rawName === "string" && /^[a-zA-Z0-9_.-]{1,100}$/.test(rawName) ? rawName : web ? "web_search" : "tool";
  const key = typeof value.toolCallId === "string" && value.toolCallId.length <= 100 ? value.toolCallId : name;
  const status = value.isError === true || /^(FAILED|ERROR)$/i.test(String(value.status)) ? "failed" : toolEvents[type as keyof typeof toolEvents];
  const index = activity.tools.findIndex((tool) => tool.key === key);
  if (activity.tools[index]?.status === status) return previous;
  const tool = { key, name, web, status };
  const tools = index === -1 ? [...activity.tools, tool].slice(-24) : activity.tools.map((item, i) => i === index ? tool : item);
  return { ...activity, tools };
}

export function isKnowledgeTool(name: string) {
  return /knowledge|(?:^|[_.-])rag(?:$|[_.-])/i.test(name);
}

const toolLabels: Record<string, string> = {
  read_farm_directory: "读取农场列表",
  list_farm_fields: "读取农场地块",
  read_field_context: "核对地块与物候",
  read_current_weather: "读取农场天气",
  query_operations_tasks: "查询农事任务",
  read_monthly_guidance: "读取月度农事指导",
  read_orchard_memory: "读取果园记忆",
  read_user_preferences: "读取用户偏好",
  read_previous_analysis: "读取历史分析",
  read_previous_visual_analysis: "读取历史视觉分析",
  read_workflow_status: "查询工作流状态",
  propose_task_draft: "准备任务草稿",
  propose_memory_update: "准备果园资料提案",
  navigate_workspace: "打开工作台页面",
  manage_user_preference: "管理用户偏好",
  lookup_reference_atlas: "查找病虫害图鉴",
  search_historical_cases: "检索历史病例",
  analyze_uploaded_images: "分析上传图片",
  inspect_camera_maturity: "巡检摄像头画面",
  render_ui_block: "整理可交互结果",
  read_agricultural_knowledge: "检索农业知识",
  search_agricultural_knowledge: "检索农业知识",
};

export function chatToolLabel(name: string, web = isWebSearch(name)) {
  if (web) return "网页搜索";
  if (toolLabels[name]) return toolLabels[name];
  if (/farm_maturity_scan/.test(name)) return "分析成熟度";
  if (/weather/.test(name)) return "查询天气";
  if (isKnowledgeTool(name)) return "检索农业知识库";
  if (/historical|cases/.test(name)) return "检索历史病例";
  if (/field|parcel|farm/.test(name)) return "读取农场与地块";
  if (/diagnos|vision/.test(name)) return "分析作物情况";
  if (/maturity|analysis/.test(name)) return "读取分析报告";
  if (/workflow/.test(name)) return "查看工作流";
  return "调用工具";
}

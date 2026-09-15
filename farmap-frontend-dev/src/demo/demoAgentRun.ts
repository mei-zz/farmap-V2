import type { DemoAgentStep, DemoAgentTool } from "./types";

export const demoAgentSteps: DemoAgentStep[] = [
  { id: "understand", title: "理解任务", description: "解析用户问题，明确分析目标和所需数据", duration: "3s" },
  { id: "context", title: "获取地块上下文", description: "调用 GIS 工具获取地块边界、作物信息和历史数据", duration: "8s" },
  { id: "multimodal", title: "获取多模态数据", description: "调取监控图像、卫星影像、气象和土壤数据", duration: "15s" },
  { id: "rag", title: "多模态检索（RAG）", description: "检索相似图像、相关农业知识和历史案例", duration: "28s" },
  { id: "reasoning", title: "证据分析与推理", description: "基于多模态证据进行综合分析", duration: "运行中..." },
  { id: "conclusion", title: "生成结论与建议", description: "输出诊断结果，生成可执行的农事方案", duration: "等待中" },
  { id: "task", title: "执行后续操作", description: "创建巡检任务、更新知识库（可选）", duration: "等待中" },
  { id: "feedback", title: "专家反馈闭环", description: "提交专家复核并沉淀可追溯的案例记录", duration: "等待中" },
];

export const demoAgentTools: DemoAgentTool[] = [
  { id: "gis", name: "GIS Tool", description: "获取地块空间信息" },
  { id: "camera", name: "Camera Tool", description: "获取监控图像" },
  { id: "weather", name: "Weather Tool", description: "获取气象数据" },
  { id: "knowledge", name: "Knowledge Retriever", description: "检索农业知识" },
  { id: "case", name: "Case Retriever", description: "检索历史案例" },
  { id: "analysis", name: "Analysis Tool", description: "多模态分析" },
  { id: "task", name: "Task Tool", description: "创建农事任务" },
];

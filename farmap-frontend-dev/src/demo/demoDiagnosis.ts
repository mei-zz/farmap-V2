import type { DemoDiagnosis } from "./types";

export const demoInsights = [
  {
    id: "a12-leaf-anomaly",
    title: "A-12 地块叶片异常",
    basis: "Camera + Weather + History",
    evidenceCount: 8,
    confidence: "82% 置信度",
  },
  {
    id: "rain-risk",
    title: "未来 24 小时强降雨风险",
    basis: "Weather + GIS",
    evidenceCount: 6,
    confidence: "中风险",
  },
  {
    id: "harvest-window",
    title: "B-04 进入采收窗口",
    basis: "Phenology + History",
    evidenceCount: 4,
    confidence: "高匹配",
  },
];

export const demoDiagnosis: DemoDiagnosis = {
  id: "a12-demo",
  fieldId: "A-12",
  title: "疑似根区积水导致的营养吸收障碍",
  severity: "high",
  severityLabel: "高风险",
  confidence: 82,
  summary:
    "综合监控图像、气象数据、土壤数据和历史案例判断，A-12 地块近期持续降雨后根区排水受限，可能影响根系吸收能力并引发叶片黄化。",
  alternatives: [
    { label: "缺镁", value: 11 },
    { label: "根腐病", value: 5 },
    { label: "其他原因", value: 2 },
  ],
  evidenceCount: 31,
  sourceCount: 5,
  expertReview: "pending",
  createdAt: "2026-09-04 09:32",
};

export const demoRecommendations = [
  {
    title: "排水改善",
    priority: "高优先级",
    tone: "high" as const,
    detail: "检查主排水沟和根区积水情况，清理排水土埂。",
  },
  {
    title: "叶面补肥",
    priority: "中优先级",
    tone: "medium" as const,
    detail: "喷施含镁、铁的叶面肥，促进叶片恢复。",
  },
  {
    title: "持续监测",
    priority: "中优先级",
    tone: "medium" as const,
    detail: "加强未来 7 天土壤湿度与叶片状态监测。",
  },
];

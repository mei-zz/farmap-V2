export type DemoFieldStatus = "healthy" | "attention" | "risk";

export type DemoField = {
  id: string;
  name: string;
  crop: string;
  variety: string;
  area: number;
  growthStage: string;
  phenologyProgress: number;
  health: number;
  ndvi: number;
  soilMoisture: number;
  rainfall14d: number;
  elevation: number;
  slope: number;
  soilType: string;
  status: DemoFieldStatus;
  statusLabel: string;
  latitude: number;
  longitude: number;
};

export type DemoEvidence = {
  id: string;
  category: "监控图像" | "气象数据" | "土壤数据" | "农业知识" | "历史案例" | "空间证据";
  title: string;
  detail: string;
  source: string;
  observedAt: string;
  weight: "high" | "medium" | "low";
  claim: string;
  asset?: string;
};

export type DemoDiagnosis = {
  id: string;
  fieldId: string;
  title: string;
  severity: "high" | "medium" | "low";
  severityLabel: string;
  confidence: number;
  summary: string;
  alternatives: Array<{ label: string; value: number }>;
  evidenceCount: number;
  sourceCount: number;
  expertReview: "pending" | "reviewed";
  createdAt: string;
};

export type DemoRecommendation = {
  title: string;
  priority: string;
  tone: "high" | "medium";
  detail: string;
};

export type DemoAgentStep = {
  id: string;
  title: string;
  description: string;
  duration: string;
};

export type DemoAgentTool = {
  id: string;
  name: string;
  description: string;
};

export type DemoWeatherDay = {
  day: string;
  icon: "cloud" | "sun" | "rain";
  high: number;
  low: number;
};

import {
  demoAgentTools,
  demoDiagnosis,
  demoEvidence,
  demoFarm,
  demoFarmLocations,
  demoLeafAssets,
  demoMapCrops,
  demoPrimaryField,
  demoRecommendations,
  demoWeather,
} from "@/demo";
import type { DemoEvidence, DemoRecommendation } from "@/demo";
import type { DiagnosisAnalysisResponse, DiagnosisEvidence, EvidenceModality } from "./contract";
import type {
  DiagnosisEvidenceView,
  DiagnosisFarmContext,
  DiagnosisFinding,
  DiagnosisInputSource,
  DiagnosisRecommendationView,
  DiagnosisViewModel,
} from "@/types/diagnosis";

const modalityLabels: Record<EvidenceModality, DemoEvidence["category"]> = {
  camera: "监控图像",
  satellite: "空间证据",
  weather: "气象数据",
  soil: "土壤数据",
  knowledge: "农业知识",
  historical_case: "历史案例",
  spatial: "空间证据",
};

const demoFarmContext: DiagnosisFarmContext = {
  id: demoFarm.id,
  name: demoFarm.name,
  address: demoFarm.address,
  coordinates: demoFarm.coordinates,
};

const demoInputSources: DiagnosisInputSource[] = [
  { id: "camera", label: "监控图像", countLabel: "8 张", modality: "camera", available: true },
  { id: "satellite", label: "卫星影像", countLabel: "2 景", modality: "satellite", available: true },
  { id: "weather", label: "气象数据", countLabel: "14 天", modality: "weather", available: true },
  { id: "soil", label: "土壤数据", countLabel: "1 组", modality: "soil", available: true },
  { id: "historical", label: "历史诊断", countLabel: "3 条（演示）", modality: "historical_case", available: true },
];

const categoryToModality: Record<DemoEvidence["category"], string> = {
  监控图像: "camera",
  气象数据: "weather",
  土壤数据: "soil",
  农业知识: "knowledge",
  历史案例: "historical_case",
  空间证据: "spatial",
};

function formatSource(item: DiagnosisEvidence): string {
  const { source } = item;
  return [source.name, source.fieldId, source.cameraId].filter(Boolean).join(" · ");
}

function evidenceWeight(score?: number): DemoEvidence["weight"] {
  return score !== undefined && score >= 0.8 ? "high" : score !== undefined && score >= 0.6 ? "medium" : "low";
}

function findingsFromEvidence(evidence: DiagnosisEvidenceView[]): DiagnosisFinding[] {
  return evidence.map((item) => ({ id: `finding-${item.id}`, text: item.claim, evidenceIds: [item.id] }));
}

function recommendationView(item: DemoRecommendation, index: number): DiagnosisRecommendationView {
  return {
    ...item,
    id: `demo-recommendation-${index + 1}`,
    evidenceIds: [],
    evidenceStatus: "UNAVAILABLE",
    decisionState: "PENDING_REVIEW",
  };
}

function countByModality(evidence: DiagnosisEvidenceView[]): DiagnosisInputSource[] {
  const counts = new Map<string, number>();
  evidence.forEach((item) => counts.set(item.modality, (counts.get(item.modality) ?? 0) + 1));
  const definitions = [
    ["camera", "监控图像"],
    ["satellite", "卫星影像"],
    ["weather", "气象数据"],
    ["soil", "土壤数据"],
    ["knowledge", "农业知识"],
    ["historical_case", "历史案例"],
    ["spatial", "空间证据"],
  ];
  return definitions
    .filter(([modality]) => (counts.get(modality) ?? 0) > 0)
    .map(([modality, label]) => ({ id: modality, label, countLabel: `${counts.get(modality)} 条证据`, modality, available: true }));
}

function numberFromRecord(record: Record<string, unknown>, keys: string[]): number | undefined {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "number" && Number.isFinite(value)) return value;
  }
  return undefined;
}

export type DiagnosisAdapterFallback = {
  farm: DiagnosisViewModel["farm"];
  field: DiagnosisViewModel["field"];
  weather: DiagnosisViewModel["weather"];
  map: DiagnosisViewModel["map"];
  images: string[];
  createdAt: string;
};

export function getDemoDiagnosisViewModel(): DiagnosisViewModel {
  const evidence = demoEvidence.map((item) => ({
    ...item,
    modality: categoryToModality[item.category],
    evidenceIds: [item.id],
    technical: { evidenceId: item.id },
  } satisfies DiagnosisEvidenceView));

  return {
    mode: "DEMO",
    farm: demoFarmContext,
    field: demoPrimaryField,
    weather: {
      summary: demoWeather.summary,
      temperature: demoWeather.temperature,
      rainfallForecast: demoWeather.rainfallForecast,
      updatedAt: demoWeather.updatedAt,
    },
    map: { crops: demoMapCrops, locations: demoFarmLocations },
    images: demoLeafAssets,
    inputSources: demoInputSources,
    diagnosis: demoDiagnosis,
    citedEvidenceCount: evidence.length,
    retrievedEvidenceCount: demoDiagnosis.evidenceCount,
    findings: findingsFromEvidence(evidence),
    evidence,
    recommendations: demoRecommendations.map(recommendationView),
    uncertainty: [
      { id: "expert-review", label: "需要专家复核", detail: "当前结论仍需农业专家确认后再进入现场处置。" },
      { id: "alternatives", label: "存在其他可能性", detail: "页面保留缺镁、根腐病等替代解释，不将最高支持结论视为最终事实。" },
    ],
    freshness: { updatedAt: demoDiagnosis.createdAt, mode: "DEMO" },
    technical: {
      runId: demoDiagnosis.id,
      retrievedEvidenceCount: demoDiagnosis.evidenceCount,
      toolNames: demoAgentTools.map((tool) => tool.name),
    },
  };
}

function mapEvidence(response: DiagnosisAnalysisResponse): DiagnosisEvidenceView[] {
  return response.evidence.map((item) => {
    const claims = response.claims.filter((claim) => claim.evidence_ids.includes(item.id));
    const score = item.score;
    return {
      id: item.id,
      category: modalityLabels[item.modality],
      title: item.title,
      detail: item.summary,
      source: formatSource(item),
      observedAt: item.source.timestamp ?? item.source.section ?? "实时检索",
      weight: evidenceWeight(score),
      claim: claims[0]?.text ?? "该证据已纳入结构化诊断结果。",
      asset: item.preview?.imageUrl,
      modality: item.modality,
      evidenceIds: claims.map((claim) => claim.id),
      score,
      technical: { evidenceId: item.id, metadata: item.metadata },
    } satisfies DiagnosisEvidenceView;
  });
}

export function toDiagnosisViewModel(response: DiagnosisAnalysisResponse, fallback: DiagnosisAdapterFallback): DiagnosisViewModel {
  const evidence = mapEvidence(response);
  const citedEvidenceCount = evidence.length;
  const retrievedEvidenceCount = numberFromRecord(response.retrieval_summary, ["evidence_count", "retrieved_evidence_count", "total"]) ?? citedEvidenceCount;
  const findings = response.claims.map((claim) => ({ id: claim.id, text: claim.text, evidenceIds: claim.evidence_ids }));
  const recommendations = response.recommendations.map((item) => ({
    id: item.id,
    title: item.title,
    priority: item.priority === "high" ? "高优先级" : item.priority === "medium" ? "中优先级" : "低优先级",
    tone: item.priority === "high" ? "high" : "medium",
    detail: item.detail,
    evidenceIds: item.evidence_ids,
    evidenceStatus: item.evidence_ids.length > 0 ? "LINKED" : "UNAVAILABLE",
    decisionState: "PENDING_REVIEW",
  })) satisfies DiagnosisRecommendationView[];
  const diagnosis = {
    id: response.diagnosisId ?? response.run_id,
    fieldId: fallback.field.id,
    title: response.diagnosis.title,
    severity: response.diagnosis.risk_level,
    severityLabel: response.diagnosis.risk_level === "high" ? "高风险" : response.diagnosis.risk_level === "medium" ? "中风险" : "低风险",
    confidence: Math.round(response.diagnosis.confidence * 100),
    summary: response.diagnosis.summary,
    alternatives: response.diagnosis.alternatives.map((item) => ({ label: item.name, value: Math.round(item.probability * 100) })),
    evidenceCount: citedEvidenceCount,
    sourceCount: new Set(response.evidence.map((item) => item.modality)).size,
    expertReview: "pending" as const,
    createdAt: response.createdAt ?? fallback.createdAt,
  };

  return {
    mode: "REAL",
    farm: fallback.farm,
    field: fallback.field,
    weather: fallback.weather,
    map: fallback.map,
    images: evidence.map((item) => item.asset).filter((asset): asset is string => Boolean(asset)).length > 0
      ? evidence.map((item) => item.asset).filter((asset): asset is string => Boolean(asset))
      : fallback.images,
    inputSources: countByModality(evidence),
    diagnosis,
    citedEvidenceCount,
    retrievedEvidenceCount,
    findings,
    evidence,
    recommendations,
    uncertainty: response.diagnosis.alternatives.length > 0
      ? [{ id: "alternatives", label: "存在其他可能性", detail: "替代诊断仅作为待确认方向，不代表最终结论。" }]
      : [],
    freshness: { updatedAt: response.updatedAt ?? response.createdAt ?? fallback.createdAt, mode: "REAL" },
    technical: {
      runId: response.run_id,
      diagnosisId: response.diagnosisId,
      sourceRunId: response.sourceRunId ?? response.run_id,
      model: response.metadata.selectedModel ?? response.metadata.model,
      route: response.metadata.routingReason,
      retrievedEvidenceCount,
      toolNames: Array.isArray(response.metadata.toolNames) ? response.metadata.toolNames.filter((item): item is string => typeof item === "string") : [],
      metadata: response.metadata,
    },
  };
}

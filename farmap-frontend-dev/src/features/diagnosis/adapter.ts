import type { DemoEvidence, DemoRecommendation, DemoDiagnosis } from "@/demo";
import type { DiagnosisAnalysisResponse, EvidenceModality } from "./contract";

const modalityLabels: Record<EvidenceModality, DemoEvidence["category"]> = {
  camera: "监控图像",
  satellite: "空间证据",
  weather: "气象数据",
  soil: "土壤数据",
  knowledge: "农业知识",
  historical_case: "历史案例",
  spatial: "空间证据",
};

function formatSource(response: DiagnosisAnalysisResponse["evidence"][number]) {
  const { source } = response;
  return [source.name, source.fieldId, source.cameraId].filter(Boolean).join(" · ");
}

export function toDiagnosisViewModel(
  response: DiagnosisAnalysisResponse,
  fallback: { fieldId: string; createdAt: string },
): {
  diagnosis: DemoDiagnosis;
  evidence: DemoEvidence[];
  recommendations: DemoRecommendation[];
} {
  const evidence = response.evidence.map((item) => {
    const relatedClaim = response.claims.find((claim) => claim.evidence_ids.includes(item.id));
    const timestamp = item.source.timestamp ?? item.source.section ?? "实时检索";
    const score = item.score ?? 0;
    return {
      id: item.id,
      category: modalityLabels[item.modality],
      title: item.title,
      detail: item.summary,
      source: formatSource(item),
      observedAt: timestamp,
      weight: score >= 0.8 ? "high" : score >= 0.6 ? "medium" : "low",
      claim: relatedClaim?.text ?? "该证据已纳入结构化诊断结果。",
      asset: item.preview?.imageUrl,
    } satisfies DemoEvidence;
  });

  const recommendations = response.recommendations.map((item) => ({
    title: item.title,
    priority: item.priority === "high" ? "高优先级" : item.priority === "medium" ? "中优先级" : "低优先级",
    tone: item.priority === "high" ? "high" : "medium",
    detail: item.detail,
  })) satisfies DemoRecommendation[];

  const diagnosis: DemoDiagnosis = {
    id: response.run_id,
    fieldId: fallback.fieldId,
    title: response.diagnosis.title,
    severity: response.diagnosis.risk_level,
    severityLabel: response.diagnosis.risk_level === "high" ? "高风险" : response.diagnosis.risk_level === "medium" ? "中风险" : "低风险",
    confidence: Math.round(response.diagnosis.confidence * 100),
    summary: response.diagnosis.summary,
    alternatives: response.diagnosis.alternatives.map((item) => ({ label: item.name, value: Math.round(item.probability * 100) })),
    evidenceCount: evidence.length,
    sourceCount: new Set(response.evidence.map((item) => item.modality)).size,
    expertReview: "pending",
    createdAt: fallback.createdAt,
  };

  return { diagnosis, evidence, recommendations };
}

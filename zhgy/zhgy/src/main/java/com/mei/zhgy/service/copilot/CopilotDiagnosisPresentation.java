package com.mei.zhgy.service.copilot;

import com.mei.zhgy.vo.DiagnosisAnalysisResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the report and evidence views separately from chat routing and SSE delivery. */
final class CopilotDiagnosisPresentation {
    record Citations(List<Map<String, Object>> entries, List<Map<String, Object>> sources) {}
    private CopilotDiagnosisPresentation() {}

    static String summary(DiagnosisAnalysisResponse result) {
        if (result == null) return "诊断已完成，但暂未返回可展示的结构化结论。";
        StringBuilder output = new StringBuilder("### 只读诊断结果\n\n");
        if (result.getDiagnosis() != null) {
            String title = result.getDiagnosis().getTitle();
            String summary = result.getDiagnosis().getSummary();
            if (title != null && !title.isBlank()) output.append("**结论：** ").append(title).append("\n\n");
            if (summary != null && !summary.isBlank()) output.append(summary).append("\n\n");
        }
        if (result.getRecommendations() != null && !result.getRecommendations().isEmpty()) {
            output.append("**下一步建议：**\n");
            result.getRecommendations().stream().limit(3).forEach(item -> {
                if (item != null && item.getTitle() != null && !item.getTitle().isBlank()) {
                    output.append("- ").append(item.getTitle());
                    if (item.getDetail() != null && !item.getDetail().isBlank()) output.append("：").append(item.getDetail());
                    output.append("\n");
                }
            });
        }
        return output.toString().trim();
    }

    static List<CopilotPresentationCard> reportCards(String diagnosisId, DiagnosisAnalysisResponse result) {
        var cards = new ArrayList<CopilotPresentationCard>();
        List<Map<String, Object>> findings = new ArrayList<>();
        if (result != null && result.getDiagnosis() != null) addItem(findings, "诊断结论", result.getDiagnosis().getTitle());
        String summary = result == null || result.getDiagnosis() == null ? null : result.getDiagnosis().getSummary();
        cards.add(new CopilotPresentationCard("DiagnosisCard", "诊断结果", summary, Map.of("diagnosisId", diagnosisId, "items", findings),
                "completed", List.of(Map.of("label", "诊断记录", "reference", diagnosisId)), null));
        if (result == null || result.getEvidence() == null || result.getEvidence().isEmpty()) return List.copyOf(cards);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> provenance = new ArrayList<>();
        for (var evidence : result.getEvidence().stream().limit(10).toList()) {
            if (evidence == null) continue;
            String label = safe(evidence.getTitle()).isBlank() ? "诊断证据" : evidence.getTitle();
            addItem(rows, label, evidence.getSummary());
            String reference = safe(evidence.getId());
            if (evidence.getSource() != null && !safe(evidence.getSource().getName()).isBlank()) label = evidence.getSource().getName();
            provenance.add(Map.of("label", label, "reference", reference));
        }
        if (!provenance.isEmpty()) cards.add(new CopilotPresentationCard("DiagnosisEvidenceCard", "诊断依据", null,
                Map.of("items", rows, "diagnosisId", diagnosisId), "ready", provenance, null));
        return List.copyOf(cards);
    }

    static Citations citations(DiagnosisAnalysisResponse result) {
        var entries = new ArrayList<Map<String, Object>>();
        if (result == null || result.getEvidence() == null) return new Citations(List.of(), List.of());
        var sources = new ArrayList<Map<String, Object>>();
        for (var evidence : result.getEvidence().stream().filter(java.util.Objects::nonNull).limit(12).toList()) {
            String alias = "E" + (sources.size() + 1);
            var source = evidence.getSource();
            var preview = evidence.getPreview();
            var metadata = evidence.getMetadata() == null ? Map.<String, Object>of() : evidence.getMetadata();
            String modality = "reference-image".equals(metadata.get("retrievalType")) ? "atlas" : switch (safe(evidence.getModality())) {
                case "visual", "image", "camera" -> "camera";
                case "historical_case", "historical" -> "historical_case";
                case "weather" -> "weather";
                case "knowledge" -> "knowledge";
                default -> "field";
            };
            String title = safe(evidence.getTitle()).isBlank() ? "诊断证据" : evidence.getTitle();
            var reference = new LinkedHashMap<String, Object>();
            reference.put("evidenceId", alias);
            reference.put("title", title);
            reference.put("modality", modality);
            reference.put("source", source == null ? "FarMap" : source.getName());
            reference.put("documentName", source == null ? null : source.getDocument());
            reference.put("summary", preview == null || safe(preview.getTextSnippet()).isBlank()
                    ? evidence.getSummary() : preview.getTextSnippet());
            reference.put("imageUrl", preview == null ? null : preview.getImageUrl());
            reference.put("expertStatus", metadata.get("expertStatus"));
            reference.put("score", metadata.get("normalizedScore"));
            entries.add(reference);
            sources.add(Map.of("label", title, "reference", alias));
        }
        return new Citations(List.copyOf(entries), List.copyOf(sources));
    }

    static CopilotPresentationCard pestComparison(String diagnosisId, DiagnosisAnalysisResponse result,
                                    List<String> fieldImages, List<Map<String, Object>> sources) {
        if (result == null || result.getEvidence() == null) return null;
        List<Map<String, Object>> atlasFigures = new ArrayList<>();
        var siteImages = new java.util.LinkedHashSet<>(fieldImages);
        List<String> features = new ArrayList<>();
        for (var evidence : result.getEvidence()) {
            if (evidence == null || evidence.getMetadata() == null) continue;
            var metadata = evidence.getMetadata();
            if (metadata.get("visualObservation") instanceof com.mei.zhgy.service.rag.VisualObservation observed) {
                for (String symptom : observed.visibleSymptoms()) if (features.size() < 10 && !features.contains(symptom)) features.add(symptom);
            }
            if ("reference-image".equals(metadata.get("retrievalType")) && evidence.getPreview() != null
                    && !safe(evidence.getPreview().getImageUrl()).isBlank() && atlasFigures.size() < 8) {
                atlasFigures.add(Map.of("refId", safe(evidence.getId()), "label", safe(evidence.getTitle()),
                        "imageUrl", evidence.getPreview().getImageUrl()));
            }
            if ("camera".equals(evidence.getModality()) && !"VISUAL_SIMILARITY".equals(metadata.get("retrievalType"))
                    && !"reference-image".equals(metadata.get("retrievalType")) && evidence.getPreview() != null
                    && !safe(evidence.getPreview().getImageUrl()).isBlank() && siteImages.size() < 6) {
                siteImages.add(evidence.getPreview().getImageUrl());
            }
        }
        if (siteImages.isEmpty() && atlasFigures.isEmpty()) return null;
        boolean conflict = result.getMetadata() != null && Boolean.TRUE.equals(result.getMetadata().get("evidenceConflict"));
        var comparison = new LinkedHashMap<String, Object>();
        comparison.put("siteImages", AgriculturalToolRegistry.siteImages(List.copyOf(siteImages)));
        comparison.put("differentialFeatures", features);
        comparison.put("phi", Map.of("warning", "报告未核验具体施药方案。施药前请核对登记标签和采收安全间隔。"));
        if (result.getRecommendations() != null) {
            result.getRecommendations().stream()
                    .filter(item -> item != null && !safe(item.getDetail()).isBlank())
                    .findFirst().ifPresent(item -> comparison.put("recommendation",
                            Map.of("title", safe(item.getTitle()), "detail", item.getDetail())));
        }
        var data = Map.<String, Object>of("diagnosisId", diagnosisId, "atlasFigures", atlasFigures,
                "pestComparison", comparison, "evidenceConflict", conflict);
        return new CopilotPresentationCard("PestComparisonCard", "现场与病害图鉴对照",
                conflict ? "证据存在冲突，请复核田间症状与登记信息。" : null, data,
                atlasFigures.isEmpty() ? "unavailable" : "ready", sources, "PestComparisonCard:diagnosis:" + diagnosisId);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static void addItem(List<Map<String, Object>> items, String label, Object value) {
        if (value != null && !value.toString().isBlank()) items.add(Map.of("label", label, "value", value));
    }
}

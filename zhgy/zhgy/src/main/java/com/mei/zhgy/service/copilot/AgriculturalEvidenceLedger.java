package com.mei.zhgy.service.copilot;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Binds model-facing reference numbers to evidence collected by authorized tools this turn. */
public final class AgriculturalEvidenceLedger {
    private final Map<String, Entry> byReference = new LinkedHashMap<>();
    private final Map<String, Entry> byAlias = new LinkedHashMap<>();
    private final Map<String, List<Measurement>> measurements = new LinkedHashMap<>();
    private final Map<String, SprayClearance> clearances = new LinkedHashMap<>();
    private static final int MAX_EVIDENCE = 96;

    public record Entry(String alias, String reference, String title, String summary, String source, String modality,
                        Integer farmId, String fieldId, String durableId) {
        public DiagnosisEvidenceVO persistedEvidence() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("citation", alias);
            metadata.put("retrievalTool", modality);
            if (farmId != null) metadata.put("farmId", farmId);
            String domain = switch (modality) {
                case "read_current_weather" -> "weather";
                case "read_field_context" -> "field";
                case "agricultural_knowledge" -> "knowledge";
                default -> modality;
            };
            return DiagnosisEvidenceVO.builder().id(durableId).modality(domain).title(title).summary(summary)
                    .source(DiagnosisEvidenceVO.Source.builder().type(modality).name(source)
                            .document(reference).fieldId(fieldId).status("READY").build())
                    .metadata(metadata).build();
        }
    }

    public synchronized Entry add(String reference, String title, String summary, String source,
                                  String modality, Integer farmId, String fieldId) {
        String key = modality + '\0' + farmId + '\0' + fieldId + '\0' + reference;
        var existing = byReference.get(key);
        if (existing != null || byAlias.size() >= MAX_EVIDENCE) return existing;
        String alias = "E" + (byAlias.size() + 1);
        var entry = new Entry(alias, bounded(reference, 220), bounded(title, 150), bounded(summary, 520),
                bounded(source, 150), modality, farmId, fieldId, "ag-evidence-" + UUID.randomUUID());
        byReference.put(key, entry);
        byAlias.put(alias, entry);
        return entry;
    }

    public synchronized boolean supports(List<String> ids) {
        return !ids.isEmpty() && ids.stream().allMatch(byAlias::containsKey);
    }

    public synchronized List<DiagnosisEvidenceVO> evidence(List<String> ids, Integer farmId, String fieldId) {
        if (!supports(ids)) return List.of();
        List<DiagnosisEvidenceVO> result = new ArrayList<>();
        for (String id : ids.stream().distinct().toList()) {
            var entry = byAlias.get(id);
            if (entry.farmId() != null && !entry.farmId().equals(farmId)
                    || entry.fieldId() != null && !entry.fieldId().equals(fieldId)) continue;
            result.add(entry.persistedEvidence());
        }
        return List.copyOf(result);
    }

    public synchronized Entry lookup(String alias) { return byAlias.get(alias); }

    public synchronized List<DiagnosisEvidenceVO> taskEvidence(Integer farmId, String fieldId) {
        return byAlias.values().stream()
                .filter(entry -> entry.farmId() == null || entry.farmId().equals(farmId))
                .filter(entry -> entry.fieldId() == null || entry.fieldId().equals(fieldId))
                .filter(entry -> List.of("read_current_weather", "read_field_context", "agricultural_knowledge").contains(entry.modality()))
                .limit(24).map(Entry::persistedEvidence).toList();
    }

    public synchronized void addMeasurement(Entry evidence, String series, String label, Number value) {
        if (evidence == null || label == null || label.isBlank() || value == null || !Double.isFinite(value.doubleValue())) return;
        var values = measurements.computeIfAbsent(evidence.alias(), ignored -> new ArrayList<>());
        var point = new Measurement(series, label, value.doubleValue());
        if (values.size() < 100 && !values.contains(point)) values.add(point);
    }

    public synchronized boolean provesMeasurement(List<String> ids, String series, String label, double value) {
        return supports(ids) && ids.stream().flatMap(id -> measurements.getOrDefault(id, List.of()).stream())
                .anyMatch(point -> point.series().equals(series) && point.label().equals(label)
                        && Math.abs(point.value() - value) < 0.000001);
    }

    public synchronized List<Map<String, Object>> series(Entry evidence) {
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Measurement point : measurements.getOrDefault(evidence.alias(), List.of())) {
            groups.computeIfAbsent(point.series(), ignored -> new ArrayList<>())
                    .add(Map.of("label", point.label(), "value", point.value()));
        }
        return groups.entrySet().stream().map(group -> Map.<String, Object>of("label", group.getKey(), "points", group.getValue())).toList();
    }

    public synchronized void verifySpray(Entry evidence, String productId, String plannedDate) {
        if (evidence != null && evidence.fieldId() != null && productId != null && plannedDate != null) {
            clearances.put(evidence.alias(), new SprayClearance(evidence.farmId(), evidence.fieldId(), productId, plannedDate));
        }
    }

    public synchronized boolean supportsSpray(List<String> ids, Integer farmId, String fieldId, String productId, String plannedDate) {
        if (productId == null || plannedDate == null || !supports(ids)) return false;
        var expected = new SprayClearance(farmId, fieldId, productId, plannedDate);
        return ids.stream().anyMatch(id -> expected.equals(clearances.get(id)));
    }

    private record SprayClearance(Integer farmId, String fieldId, String productId, String plannedDate) {}

    private record Measurement(String series, String label, double value) {}

    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(max, value.length()));
    }
}

package com.mei.zhgy.service.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit local-only repository for direct unit construction. */
public class InMemoryDiagnosisRepository implements DiagnosisRepository {
    private final Map<String, DiagnosisRecord> records = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Override public DiagnosisRecord save(DiagnosisRecord record) {
        DiagnosisRecord snapshot = snapshot(record);
        records.put(snapshot.getDiagnosisId(), snapshot);
        return snapshot(snapshot);
    }

    @Override public Optional<DiagnosisRecord> find(String diagnosisId, Long userId) {
        DiagnosisRecord record = records.get(diagnosisId);
        if (record == null) return Optional.empty();
        if (userId == null || record.getUserId() == null || userId.equals(record.getUserId())) return Optional.of(snapshot(record));
        return Optional.empty();
    }

    private DiagnosisRecord snapshot(DiagnosisRecord record) {
        if (record == null) return null;
        DiagnosisAnalysisResponse payload = record.getPayload() == null ? null : mapper.convertValue(record.getPayload(), DiagnosisAnalysisResponse.class);
        return DiagnosisRecord.builder().diagnosisId(record.getDiagnosisId()).sourceRunId(record.getSourceRunId()).userId(record.getUserId())
                .farmId(record.getFarmId()).fieldId(record.getFieldId()).crop(record.getCrop()).status(record.getStatus())
                .riskLevel(record.getRiskLevel()).confidence(record.getConfidence()).summary(record.getSummary()).mode(record.getMode())
                .payload(payload).createdAt(record.getCreatedAt()).updatedAt(record.getUpdatedAt()).build();
    }
}

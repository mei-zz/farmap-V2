package com.mei.zhgy.service.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.ProductLoopRelationException;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Orchestrates stable Diagnosis identity without putting SQL in the Controller or RAG service. */
@Service
public class DiagnosisPersistenceService {
    private final DiagnosisRepository repository;

    /** Direct construction is local-only and exists for deterministic unit tests. */
    public DiagnosisPersistenceService() { this.repository = new InMemoryDiagnosisRepository(); }
    public DiagnosisPersistenceService(DiagnosisRepository repository) { this.repository = repository; }

    @Autowired
    public DiagnosisPersistenceService(DataSource dataSource, ObjectMapper mapper) {
        this.repository = dataSource == null ? new FailClosedDiagnosisRepository() : new JdbcDiagnosisRepository(dataSource, mapper);
    }

    public DiagnosisAnalysisResponse persist(DiagnosisAnalysisResponse response, DiagnosisAnalyzeRequest request, Long userId) {
        if (response == null || response.getRunId() == null || response.getRunId().isBlank()) {
            throw new DiagnosisPersistenceException("DIAGNOSIS_IDENTITY_INVALID", "诊断缺少 sourceRunId");
        }
        String diagnosisId = "diagnosis-" + UUID.randomUUID();
        Instant now = Instant.now();
        response.setDiagnosisId(diagnosisId); response.setSourceRunId(response.getRunId()); response.setStatus("COMPLETED"); response.setCreatedAt(now); response.setUpdatedAt(now);
        if (response.getMetadata() != null) {
            java.util.Map<String, Object> metadata = new LinkedHashMap<>(response.getMetadata());
            metadata.put("diagnosisId", diagnosisId); metadata.put("sourceRunId", response.getRunId()); metadata.put("diagnosisStatus", "COMPLETED");
            response.setMetadata(metadata);
        }
        DiagnosisRecord persisted = repository.save(responseRecord(response, request, userId, now));
        return persisted.getPayload();
    }

    public Optional<DiagnosisAnalysisResponse> find(String diagnosisId, Long userId) {
        if (diagnosisId == null || diagnosisId.isBlank()) return Optional.empty();
        return findRecord(diagnosisId, userId).map(DiagnosisRecord::getPayload);
    }

    /** Loads the complete server-owned Diagnosis record for product-loop consumers. */
    public Optional<DiagnosisRecord> findRecord(String diagnosisId, Long userId) {
        if (diagnosisId == null || diagnosisId.isBlank()) return Optional.empty();
        return repository.find(diagnosisId, userId);
    }

    /** Resolves a recommendation only from the persisted Diagnosis snapshot. */
    public Optional<DiagnosisAnalysisResponse.Recommendation> findRecommendation(String diagnosisId, String recommendationId, Long userId) {
        if (recommendationId == null || recommendationId.isBlank()) return Optional.empty();
        return find(diagnosisId, userId).flatMap(payload -> payload.getRecommendations() == null ? Optional.empty() : payload.getRecommendations().stream()
                .filter(item -> item != null && recommendationId.equals(item.getId())).findFirst());
    }

    /**
     * Builds the canonical Agent context from the persisted Diagnosis snapshot.
     * Client-provided diagnosis, recommendation, evidence, weather and image
     * copies are deliberately not used as business authority here.
     */
    public AgentContext buildAgentContext(String diagnosisId, String recommendationId, Long userId, AgentContext requestContext) {
        DiagnosisRecord record = findRecord(diagnosisId, userId)
                .orElseThrow(() -> ProductLoopRelationException.diagnosisNotFound(diagnosisId));
        DiagnosisAnalysisResponse snapshot = record.getPayload();
        if (snapshot == null || !"COMPLETED".equalsIgnoreCase(record.getStatus())) {
            throw ProductLoopRelationException.validation("Diagnosis 快照尚未完成，不能进入 Agent", diagnosisId, recommendationId);
        }
        DiagnosisAnalysisResponse.Recommendation recommendation = null;
        if (recommendationId != null && !recommendationId.isBlank()) {
            recommendation = snapshot.getRecommendations() == null ? null : snapshot.getRecommendations().stream()
                    .filter(item -> item != null && recommendationId.equals(item.getId())).findFirst().orElse(null);
            if (recommendation == null) throw ProductLoopRelationException.recommendationNotFound(diagnosisId, recommendationId);
            validateRecommendationEvidence(snapshot, recommendation, diagnosisId);
        }

        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("id", snapshot.getDiagnosisId());
        if (snapshot.getDiagnosis() != null) {
            diagnosis.put("title", snapshot.getDiagnosis().getTitle());
            diagnosis.put("severity", snapshot.getDiagnosis().getRiskLevel());
            diagnosis.put("confidence", snapshot.getDiagnosis().getConfidence());
            diagnosis.put("summary", snapshot.getDiagnosis().getSummary());
        }
        diagnosis.put("sourceRunId", snapshot.getSourceRunId());
        diagnosis.put("evidence", evidenceSnapshots(snapshot.getEvidence()));
        diagnosis.put("recommendations", recommendationSnapshots(snapshot.getRecommendations()));
        if (recommendation != null) {
            diagnosis.put("recommendation", recommendationSnapshot(recommendation));
            diagnosis.put("recommendationEvidenceIds", new ArrayList<>(safeIds(recommendation.getEvidenceIds())));
        }
        if (snapshot.getMetadata() != null) diagnosis.put("metadata", new LinkedHashMap<>(snapshot.getMetadata()));

        AgentContext context = AgentContext.builder()
                .farmId(record.getFarmId() == null ? null : String.valueOf(record.getFarmId()))
                .fieldId(record.getFieldId())
                .crop(record.getCrop())
                .diagnosisId(snapshot.getDiagnosisId())
                .sourceRecommendationId(recommendation == null ? null : recommendation.getId())
                .imageUrls(imageUrlsFromEvidence(snapshot.getEvidence()))
                .weather(new LinkedHashMap<>())
                .diagnosis(diagnosis)
                .sourceModes(sourceModes(snapshot.getEvidence()))
                .page(requestContext == null ? null : requestContext.getPage())
                .build();
        return context;
    }

    /**
     * The Agent must be able to consume the same uploaded image that produced
     * the Diagnosis. The image is immutable evidence, so rebuilding this list
     * from the persisted camera previews is server-authoritative and avoids
     * trusting a client-supplied replacement URL.
     */
    private List<String> imageUrlsFromEvidence(List<DiagnosisEvidenceVO> evidence) {
        List<String> result = new ArrayList<>();
        if (evidence == null) return result;
        for (DiagnosisEvidenceVO item : evidence) {
            if (item == null || !"camera".equals(item.getModality()) || item.getPreview() == null) continue;
            String imageUrl = item.getPreview().getImageUrl();
            if (imageUrl != null && !imageUrl.isBlank() && !result.contains(imageUrl)) result.add(imageUrl);
        }
        return result;
    }

    private void validateRecommendationEvidence(DiagnosisAnalysisResponse snapshot,
                                                 DiagnosisAnalysisResponse.Recommendation recommendation,
                                                 String diagnosisId) {
        Set<String> known = safeIds(snapshot.getEvidence() == null ? List.of() : snapshot.getEvidence().stream()
                .filter(item -> item != null).map(DiagnosisEvidenceVO::getId).toList());
        Set<String> referenced = safeIds(recommendation.getEvidenceIds());
        if (referenced.isEmpty() || !known.containsAll(referenced)) {
            throw ProductLoopRelationException.conflict("Recommendation 引用了未包含在 Diagnosis 快照中的 Evidence", diagnosisId, recommendation.getId());
        }
    }

    private List<Map<String, Object>> evidenceSnapshots(List<DiagnosisEvidenceVO> evidence) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (evidence == null) return result;
        for (DiagnosisEvidenceVO item : evidence) {
            if (item == null) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.getId()); value.put("modality", item.getModality()); value.put("title", item.getTitle());
            value.put("summary", item.getSummary()); value.put("score", item.getScore());
            value.put("source", item.getSource()); value.put("metadata", item.getMetadata());
            result.add(value);
        }
        return result;
    }

    private List<Map<String, Object>> recommendationSnapshots(List<DiagnosisAnalysisResponse.Recommendation> recommendations) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (recommendations == null) return result;
        for (DiagnosisAnalysisResponse.Recommendation item : recommendations) if (item != null) result.add(recommendationSnapshot(item));
        return result;
    }

    private Map<String, Object> recommendationSnapshot(DiagnosisAnalysisResponse.Recommendation item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", item.getId()); value.put("title", item.getTitle()); value.put("priority", item.getPriority());
        value.put("detail", item.getDetail()); value.put("evidenceIds", new ArrayList<>(safeIds(item.getEvidenceIds())));
        return value;
    }

    private Map<String, Object> sourceModes(List<DiagnosisEvidenceVO> evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (evidence != null) for (DiagnosisEvidenceVO item : evidence) {
            if (item == null || item.getModality() == null) continue;
            String mode = item.getSource() == null ? null : item.getSource().getSourceMode();
            if (mode != null && !mode.isBlank()) result.put(item.getModality(), mode);
        }
        return result;
    }

    private Set<String> safeIds(Iterable<String> values) {
        Set<String> result = new HashSet<>();
        if (values != null) for (String value : values) if (value != null && !value.isBlank()) result.add(value.trim());
        return result;
    }

    private DiagnosisRecord responseRecord(DiagnosisAnalysisResponse response, DiagnosisAnalyzeRequest request, Long userId, Instant now) {
        DiagnosisAnalysisResponse.Diagnosis diagnosis = response.getDiagnosis();
        String mode = response.getMetadata() == null || response.getMetadata().get("mode") == null ? "real" : String.valueOf(response.getMetadata().get("mode"));
        return DiagnosisRecord.builder().diagnosisId(response.getDiagnosisId()).sourceRunId(response.getSourceRunId()).userId(userId)
                .farmId(request == null ? null : request.getFarmId()).fieldId(request == null ? null : request.getFieldId())
                .crop(request == null ? null : request.getCrop()).status("COMPLETED")
                .riskLevel(diagnosis == null ? null : diagnosis.getRiskLevel()).confidence(diagnosis == null ? null : diagnosis.getConfidence())
                .summary(diagnosis == null ? null : diagnosis.getSummary()).mode(mode).payload(response).createdAt(now).updatedAt(now).build();
    }

    private static final class FailClosedDiagnosisRepository implements DiagnosisRepository {
        @Override public DiagnosisRecord save(DiagnosisRecord record) { throw new DiagnosisPersistenceException("DIAGNOSIS_PERSISTENCE_UNAVAILABLE", "Diagnosis 数据库不可用，已拒绝返回稳定身份"); }
        @Override public Optional<DiagnosisRecord> find(String diagnosisId, Long userId) { throw new DiagnosisPersistenceException("DIAGNOSIS_PERSISTENCE_UNAVAILABLE", "Diagnosis 数据库不可用"); }
    }
}

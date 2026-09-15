package com.mei.zhgy.service.agent;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Small, deterministic policy boundary between read-only evidence gathering and
 * write-capable ProposedAction creation. It never calls an LLM and never mutates
 * the evidence supplied by the caller.
 */
@Service
public class EvidenceSufficiencyEvaluator {
    public static final String NO_TRUSTED_EVIDENCE = "NO_TRUSTED_EVIDENCE";
    public static final String EMPTY_RESULT = "EMPTY_RESULT";
    public static final String UNAVAILABLE_DEPENDENCY = "UNAVAILABLE_DEPENDENCY";
    public static final String EVIDENCE_PROVENANCE_INVALID = "EVIDENCE_PROVENANCE_INVALID";
    public static final String RECOMMENDATION_EVIDENCE_MISSING = "RECOMMENDATION_EVIDENCE_MISSING";

    public EvidenceSufficiencyDecision evaluate(boolean writeAction,
                                                Collection<DiagnosisEvidenceVO> trustedEvidence,
                                                String recommendationId,
                                                Collection<String> recommendationEvidenceIds,
                                                Collection<String> unavailableDependencies,
                                                boolean emptyObserved) {
        return evaluate(writeAction, trustedEvidence, recommendationId, recommendationEvidenceIds,
                null, unavailableDependencies, emptyObserved);
    }

    /**
     * The requested IDs overload is used to prove that an action cannot smuggle
     * an evidence ID which is not present in the server-owned current evidence.
     */
    public EvidenceSufficiencyDecision evaluate(boolean writeAction,
                                                Collection<DiagnosisEvidenceVO> trustedEvidence,
                                                String recommendationId,
                                                Collection<String> recommendationEvidenceIds,
                                                Collection<String> requestedEvidenceIds,
                                                Collection<String> unavailableDependencies,
                                                boolean emptyObserved) {
        Set<String> currentIds = ids(trustedEvidence);
        Set<String> requestedIds = clean(requestedEvidenceIds);
        Set<String> unavailable = clean(unavailableDependencies);
        List<String> reasons = new ArrayList<>();

        if (!requestedIds.isEmpty() && !currentIds.containsAll(requestedIds)) {
            reasons.add(EVIDENCE_PROVENANCE_INVALID);
            return decision(EvidenceSufficiencyStatus.INSUFFICIENT, false, reasons, currentIds, unavailable,
                    "动作引用了当前运行中不存在的证据");
        }

        if (currentIds.isEmpty()) {
            reasons.add(NO_TRUSTED_EVIDENCE);
            if (emptyObserved) reasons.add(EMPTY_RESULT);
            if (!unavailable.isEmpty()) reasons.add(UNAVAILABLE_DEPENDENCY);
            return decision(EvidenceSufficiencyStatus.INSUFFICIENT, false, reasons, currentIds, unavailable,
                    "没有可用于支持写入动作的当前可信证据");
        }

        Set<String> recommendationIds = clean(recommendationEvidenceIds);
        if (recommendationId != null && !recommendationId.isBlank()) {
            if (recommendationIds.isEmpty() || currentIds.stream().noneMatch(recommendationIds::contains)) {
                reasons.add(RECOMMENDATION_EVIDENCE_MISSING);
                return decision(EvidenceSufficiencyStatus.INSUFFICIENT, false, reasons, currentIds, unavailable,
                        "推荐没有与当前运行证据建立可验证的 ID 交集");
            }
        }

        if (!unavailable.isEmpty()) {
            reasons.add(UNAVAILABLE_DEPENDENCY);
            return decision(EvidenceSufficiencyStatus.DEGRADED, false, reasons, currentIds, unavailable,
                    "证据存在，但仍有依赖不可用，默认不创建写入动作");
        }

        return decision(EvidenceSufficiencyStatus.SUFFICIENT, writeAction, reasons, currentIds, unavailable,
                writeAction ? "当前证据满足写入动作门槛，仍需人工审批" : "当前证据满足读取决策门槛");
    }

    private EvidenceSufficiencyDecision decision(EvidenceSufficiencyStatus status, boolean allowsWrite,
                                                  List<String> reasons, Set<String> evidenceIds,
                                                  Set<String> unavailable, String summary) {
        return EvidenceSufficiencyDecision.builder().status(status).allowsWrite(allowsWrite)
                .reasonCodes(List.copyOf(reasons)).availableEvidenceIds(List.copyOf(evidenceIds))
                .unavailableDependencies(List.copyOf(unavailable)).summary(summary).build();
    }

    private Set<String> ids(Collection<DiagnosisEvidenceVO> evidence) {
        if (evidence == null) return Set.of();
        return evidence.stream().filter(item -> item != null && item.getId() != null && !item.getId().isBlank())
                .map(item -> item.getId().trim()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> clean(Collection<String> values) {
        if (values == null) return Set.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

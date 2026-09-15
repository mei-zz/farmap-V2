package com.mei.zhgy.service.ai;

import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 确保模型只能引用 Retriever 已经生成的 Evidence ID。 */
@Component
public class EvidenceReferenceValidator {
    public void validateOrThrow(DiagnosisAnalysisResponse response, List<DiagnosisEvidenceVO> evidence) {
        if (response == null) {
            throw new InvalidEvidenceReferenceException("模型没有返回结构化诊断");
        }
        Set<String> allowedIds = new HashSet<>();
        if (evidence != null) {
            evidence.forEach(item -> allowedIds.add(item.getId()));
        }
        validateClaims(response.getClaims(), allowedIds);
        validateRecommendations(response.getRecommendations(), allowedIds);
    }

    private void validateClaims(List<DiagnosisAnalysisResponse.Claim> claims, Set<String> allowedIds) {
        if (claims == null || claims.isEmpty()) {
            throw new InvalidEvidenceReferenceException("模型没有返回 Claim");
        }
        for (DiagnosisAnalysisResponse.Claim claim : claims) {
            validateIds(claim.getEvidenceIds(), allowedIds, "Claim " + claim.getId());
        }
    }

    private void validateRecommendations(List<DiagnosisAnalysisResponse.Recommendation> recommendations,
                                         Set<String> allowedIds) {
        if (recommendations == null || recommendations.isEmpty()) {
            throw new InvalidEvidenceReferenceException("模型没有返回 Recommendation");
        }
        for (DiagnosisAnalysisResponse.Recommendation recommendation : recommendations) {
            validateIds(recommendation.getEvidenceIds(), allowedIds, "Recommendation " + recommendation.getId());
        }
    }

    private void validateIds(List<String> ids, Set<String> allowedIds, String owner) {
        if (ids == null || ids.isEmpty()) {
            throw new InvalidEvidenceReferenceException(owner + " 缺少 evidence_ids");
        }
        for (String id : ids) {
            if (id == null || !allowedIds.contains(id)) {
                throw new InvalidEvidenceReferenceException(owner + " 引用了不存在的 evidence_id");
            }
        }
    }

    public static class InvalidEvidenceReferenceException extends RuntimeException {
        public InvalidEvidenceReferenceException(String message) {
            super(message);
        }
    }
}

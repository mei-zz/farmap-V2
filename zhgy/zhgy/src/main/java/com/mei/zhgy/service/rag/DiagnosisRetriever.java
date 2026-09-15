package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;

import java.util.List;

/** 一个可独立计时、替换和扩展的证据检索器。 */
public interface DiagnosisRetriever {
    String modality();

    List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request);

    /** Optional actor-aware boundary. Legacy retrievers remain source-compatible. */
    default List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request, Long userId) {
        return retrieve(request);
    }

    /** Optional explicit status for an empty modality (for example VISION_UNAVAILABLE). */
    default String emptyStatus(DiagnosisAnalyzeRequest request, Long userId) {
        return null;
    }
}


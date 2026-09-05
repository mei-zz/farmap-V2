package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;

import java.util.List;

/** 一个可独立计时、替换和扩展的证据检索器。 */
public interface DiagnosisRetriever {
    String modality();

    List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request);
}


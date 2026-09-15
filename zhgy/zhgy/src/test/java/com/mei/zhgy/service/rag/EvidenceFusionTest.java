package com.mei.zhgy.service.rag;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceFusionTest {
    @Test void ranksAndRecordsAuditFields() {
        var low = DiagnosisEvidenceVO.builder().id("low").modality("knowledge").score(.2).build();
        var high = DiagnosisEvidenceVO.builder().id("high").modality("camera").score(.9).metadata(new LinkedHashMap<>()).build();
        var result = new EvidenceFusionService().fuse(List.of(low, high));
        assertEquals("high", result.get(0).getId());
        assertEquals(1, result.get(0).getMetadata().get("retrievalRank"));
    }
}

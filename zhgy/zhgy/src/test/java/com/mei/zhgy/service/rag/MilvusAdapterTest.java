package com.mei.zhgy.service.rag;

import com.mei.zhgy.entity.Case;
import com.mei.zhgy.service.CaseStorageService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MilvusAdapterTest {
    @Test void l2DistanceIsNotReportedAsProbability() {
        var value = new CaseStorageService.SimilarCaseWithScore(Case.builder().build(), 3D, 1D / 4D);
        assertEquals(3D, value.getVectorDistance());
        assertEquals(.25D, value.getRankingScore());
    }
}

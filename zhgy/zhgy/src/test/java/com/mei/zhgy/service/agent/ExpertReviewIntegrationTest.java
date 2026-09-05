package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.expert.ExpertReviewService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpertReviewIntegrationTest {
    @Test void confirmedAndCorrectedStatesArePersistedByFacade() {
        ExpertReviewService service = new ExpertReviewService();
        assertEquals("CONFIRMED", service.save("diag-1", Map.of("decision", "CONFIRMED", "expertDiagnosis", "缺素"), "expert-1").get("status"));
        assertEquals("CONFIRMED", service.get("diag-1").get("status"));
    }
}

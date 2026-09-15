package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.expert.ExpertReviewService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpertReviewIntegrationTest {
    @Test void confirmedAndCorrectedStatesArePersistedByFacade() {
        ExpertReviewService service = new ExpertReviewService();
        Map<String, Object> review = service.save("diagnosis-stable-1", Map.of("decision", "CONFIRMED", "expertDiagnosis", "缺素"), "expert-1");
        assertEquals("diagnosis-stable-1", review.get("diagnosisId"));
        assertEquals("CONFIRMED", review.get("status"));
        assertEquals("CONFIRMED", service.get("diagnosis-stable-1").get("status"));
    }
}

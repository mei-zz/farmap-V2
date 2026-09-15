package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.historical.HistoricalCaseIngestionService;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoricalCaseIngestionTest {
    @Test void emptyRealCaseSetIsReadyWithoutFabrication() {
        HistoricalCaseIngestionService.IngestionReport report = new HistoricalCaseIngestionService.IngestionReport(0, 0, 0, Collections.emptyList(), "DRY_RUN");
        assertEquals("READY", report.asMap().get("status"));
        assertEquals(0, report.asMap().get("inserted"));
    }
}

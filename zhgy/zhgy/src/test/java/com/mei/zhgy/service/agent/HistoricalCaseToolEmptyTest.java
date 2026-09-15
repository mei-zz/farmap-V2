package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import com.mei.zhgy.service.rag.HistoricalCaseEvidenceRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoricalCaseToolEmptyTest {
    @Test void readyEmptyStoreCompletesWithEmptyStatus() {
        HistoricalCaseEvidenceRetriever retriever = mock(HistoricalCaseEvidenceRetriever.class);
        when(retriever.retrieve(any())).thenReturn(List.of());
        HistoricalVectorStoreInitializer initializer = new HistoricalVectorStoreInitializer(null) {
            @Override public Map<String, Object> initialize(boolean execute) {
                return Map.of("status", "READY", "rowCount", 0L);
            }
        };

        AgentToolRequest request = new AgentToolRequest();
        request.setRunId("run-empty");
        ToolResult result = new HistoricalCaseTool(retriever, initializer).execute(request);

        assertEquals(ToolStatus.COMPLETED, result.getStatus());
        assertEquals("EMPTY", result.getOutput().get("status"));
        assertEquals("NO_HISTORICAL_CASES", result.getOutput().get("reason"));
        verify(retriever).retrieve(any());
    }
}

package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.rag.DiagnosisRagService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentRagToolTest {
    @Test void delegatesToExistingRagService() {
        DiagnosisRagService service = mock(DiagnosisRagService.class);
        when(service.analyze(any(), any())).thenReturn(DiagnosisAnalysisResponse.builder().evidence(java.util.List.of()).build());
        MultimodalRagTool tool = new MultimodalRagTool(service);
        ToolResult result = tool.execute(AgentToolRequest.builder().runId("r").goal("诊断").context(AgentContext.builder().fieldId("A-12").imageUrls(java.util.List.of("data:image/png;base64,x")).weather(java.util.Map.of("rainfall14d", 1)).build()).build());
        assertEquals(ToolStatus.COMPLETED, result.getStatus()); verify(service).analyze(any(), any());
    }
}

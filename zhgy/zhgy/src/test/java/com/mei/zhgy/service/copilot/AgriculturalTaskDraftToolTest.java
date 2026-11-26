package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgriculturalTaskDraftToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void batchFiltersSourcesByFieldAndRejectsUnknownRefsAndUncheckedSpraying() throws Exception {
        var fields = mock(FarmFieldService.class);
        when(fields.get("east", 1L)).thenReturn(FarmField.builder().farmId(7).fieldId("east").build());
        when(fields.get("west", 1L)).thenReturn(FarmField.builder().farmId(7).fieldId("west").build());
        var guard = new CopilotSecurityGuard(fields);
        var scope = CopilotStableContext.builder().farmId("7").fieldId("east").build();
        var ledger = new AgriculturalEvidenceLedger();
        ledger.add("east", "东地地块", "地块证据", "农场档案", "field", 7, "east");
        ledger.add("west", "西地地块", "地块证据", "农场档案", "field", 7, "west");
        var tool = AgriculturalTaskDraftTool.register(guard, scope, 1L, ledger);

        var batchReply = tool.execute().apply(json.readTree("""
                {"tasks":[{"title":"东地检查","detail":"确认表土湿度","taskType":"INSPECTION"},
                {"fieldId":"west","title":"西地巡查","detail":"核验采收通道","taskType":"INSPECTION"}],"evidenceIds":["E1","E2"]}
                """));
        assertFalse(batchReply.isError());
        var batch = (AgriculturalTaskDraftTool.Batch) batchReply.data();
        assertEquals(2, batch.tasks().size());
        assertEquals("east", batch.tasks().get(0).evidence().get(0).getSource().getFieldId());
        assertEquals("west", batch.tasks().get(1).evidence().get(0).getSource().getFieldId());

        var broken = tool.execute().apply(json.readTree("""
                {"tasks":[{"title":"东地检查","detail":"确认表土湿度","taskType":"INSPECTION"}],"evidenceIds":["E9"]}
                """));
        assertTrue(broken.isError());
        assertEquals("INVALID_ARGUMENTS", ((Map<?, ?>) broken.data()).get("status"));

        var spray = tool.execute().apply(json.readTree("""
                {"tasks":[{"title":"东地喷药","detail":"安排喷药","taskType":"SPRAY"}],"evidenceIds":["E1"]}
                """));
        assertEquals(Map.of("status", "SPRAY_LABEL_PHI_CERTIFICATION_REQUIRED"), spray.data());
    }
}

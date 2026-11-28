package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.SimplifiedFarmVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotWorkspaceToolsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CopilotLongTermMemoryService memory = new CopilotLongTermMemoryService(
            new InMemoryCopilotLongTermMemoryRepository(), null, null, null);

    @Test
    void directoryUsesTheAuthenticatedOwnerAndNavigationRejectsArbitraryUrls() {
        var fields = mock(FarmFieldService.class);
        when(fields.accessibleFarms(7L)).thenReturn(List.of(new SimplifiedFarmVO(42, "西坡果园")));
        var tools = AgriculturalToolRegistry.workspaceTools(fields, 7L, null);
        var directory = tool(tools, "read_farm_directory").execute().apply(json.createObjectNode());
        assertEquals("FarmDirectoryCard", directory.uiBlock().get("componentType"));
        assertEquals(1, ((Map<?, ?>) directory.data()).get("count"));
        verify(fields).accessibleFarms(7L);

        var navigation = tool(tools, "navigate_workspace");
        var safe = navigation.execute().apply(json.createObjectNode().put("route", "/operations"));
        assertEquals("WorkspaceNavigationCard", safe.uiBlock().get("componentType"));
        assertTrue(navigation.execute().apply(json.createObjectNode().put("route", "https://foreign.example")).isError());
        assertTrue(navigation.execute().apply(json.createObjectNode().put("route", "/operations?redirect=/other")).isError());
    }

    @Test
    void preferencesUseActualUserAuthorizationAndNeverMutateAnotherOwnersMemory() {
        var created = preference("帮我记住以后优先推荐生物防治", 7L);
        var saved = apply(created, "SAVE");
        assertEquals("SAVED", ((Map<?, ?>) saved.data()).get("status"));
        assertEquals(saved, apply(created, "SAVE"));
        assertEquals("BIOLOGICAL", memory.retrieve(7L, new CopilotStableContext(), null).get(0).getValue());
        assertTrue(apply(preference("我喜欢低残留的建议", 7L), "SAVE").isError());
        assertTrue(apply(preference("帮我记住生物防治用药浓度是记录内容", 7L), "SAVE").isError());
        assertTrue(apply(preference("麻烦忘掉这条偏好", 8L), "FORGET").isError());
        assertFalse(apply(preference("我之前让你记了什么", 7L), "LIST").isError());

        var revoked = apply(preference("麻烦忘掉这条偏好", 7L), "FORGET");
        assertEquals("REVOKED", ((Map<?, ?>) revoked.data()).get("status"));
        assertTrue(memory.retrieve(7L, new CopilotStableContext(), null).isEmpty());
    }

    @Test
    void supportedOralPreferencesAreExtractedWithoutModelSuppliedValues() {
        assertFalse(apply(preference("帮我记住以后避开中午打药", 7L), "SAVE").isError());
        assertFalse(apply(preference("帮我记一下习惯早上打药", 7L), "SAVE").isError());
        assertEquals(java.util.Set.of("MIDDAY", "MORNING"),
                memory.retrieve(7L, new CopilotStableContext(), null).stream().map(CopilotLongTermMemory::getValue)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private PiAgentRuntime.ReadTool preference(String message, long actor) {
        return tool(AgriculturalToolRegistry.workspaceTools(null, actor,
                new CopilotPreferenceTool(memory, new CopilotStableContext(), message, actor, "chat", "turn")),
                "manage_user_preference");
    }

    private PiAgentRuntime.ToolReply apply(PiAgentRuntime.ReadTool tool, String operation) {
        return tool.execute().apply(json.createObjectNode().put("operation", operation));
    }

    private PiAgentRuntime.ReadTool tool(List<PiAgentRuntime.ReadTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }
}

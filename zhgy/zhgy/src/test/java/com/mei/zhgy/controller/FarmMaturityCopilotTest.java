package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.copilot.*;
import com.mei.zhgy.service.maturity.FarmMaturityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FarmMaturityCopilotTest {
    @Test
    void explicitScanIsDistinctFromQuestionsAboutMaturity() {
        var router = new CopilotIntentRouter();
        assertEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classify("\u519c\u573a\u54ea\u4e9b\u533a\u57df\u6700\u9002\u5408\u91c7\u6536").intent());
        assertEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classify("analyze CAM-01 camera").intent());
        assertNotEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classify("how to assess camera maturity").intent());
        assertEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classifyHybrid("analyze maturity", null, true).finalClassification().intent());
        String request = "请用摄像头画面评估当前农场的果实成熟度，并展示全农场成熟度分布。";
        assertEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classify(request).intent());
        assertEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classifyHybrid(request, null, true).finalClassification().intent());
        assertNotEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classify("如何通过摄像头判断柑橘成熟度？").intent());
        assertNotEquals(CopilotIntent.FARM_MATURITY_SCAN, router.classify("不要抓拍，请解释农场成熟度分布").intent());
    }

    @Test
    void emitsAndRestoresDistributionFromTheAuthorizedFarm() throws Exception {
        var conversations = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        var conversation = conversations.create(7L);
        var routes = mock(CopilotRouteService.class);
        when(routes.route(any(), eq(7L), anyBoolean())).thenReturn(CopilotRouteResult.builder()
                .intent(CopilotIntent.FARM_MATURITY_SCAN).conversationId(conversation.getConversationId())
                .resolutionStatus(CopilotResolutionStatus.RESOLVED).resolvedContext(CopilotStableContext.builder().farmId("19").build()).build());
        var service = mock(FarmMaturityService.class);
        var report = Map.<String, Object>of("runId", "maturity-1", "farmId", 19, "mode", "FARM", "kind", "MATURITY",
                "status", "COMPLETED", "summary", "One usable observation.", "recommendations", List.of("请实地复核"));
        when(service.analyze(eq(19), eq(7L), anyList(), isNull(), anyList(), eq("MATURITY"), eq("FARM"), any()))
                .thenAnswer(call -> {
                    java.util.function.Consumer<FarmMaturityService.ScanProgress> progress = call.getArgument(7);
                    progress.accept(new FarmMaturityService.ScanProgress(2, 0, 0, 0));
                    progress.accept(new FarmMaturityService.ScanProgress(2, 2, 1, 1));
                    return report;
                });
        when(service.run("maturity-1", 7L)).thenReturn(report);
        var controller = new CopilotController(routes, conversations);
        controller.setMaturityService(service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        BaseContext.setCurrentId(7L);
        try {
            var pending = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"请扫描当前农场的成熟度分布\"}"))
                    .andExpect(request().asyncStarted()).andReturn();
            var stream = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(stream.contains("event:ui.block.created"));
            assertTrue(stream.contains("FarmMaturityCard"));
            assertTrue(stream.contains("FarmInspectionProgress"));
            assertTrue(stream.contains("event:ui.block.updated"));
            assertTrue(stream.contains("FarmRecommendationCard"));
            assertTrue(stream.contains("event:analysis.workspace"));
            assertTrue(stream.contains("maturity-1"));
            assertTrue(stream.contains("event:run.completed"));
            var restored = conversations.view(conversation.getConversationId(), 7L).getHistoryTurns().get(0);
            assertTrue(String.valueOf(restored.getResponseMetadata().get("uiBlocks")).contains("FarmMaturityCard"));
            assertTrue(String.valueOf(restored.getResponseMetadata().get("uiBlocks")).contains("analysisRunId=maturity-1"));

            var followUp = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"根据当前报告制定采收复核计划\",\"pageContext\":{\"farmId\":\"19\",\"analysisRunId\":\"maturity-1\"}}"))
                    .andExpect(request().asyncStarted()).andReturn();
            var followUpStream = mvc.perform(asyncDispatch(followUp)).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(followUpStream.contains("FarmRecommendationCard"));
            assertFalse(followUpStream.contains("FarmInspectionProgress"));
            verify(service).run("maturity-1", 7L);
            verify(service).analyze(eq(19), eq(7L), anyList(), isNull(), anyList(), eq("MATURITY"), eq("FARM"), any());
        } finally { BaseContext.removeCurrentId(); }
    }
}

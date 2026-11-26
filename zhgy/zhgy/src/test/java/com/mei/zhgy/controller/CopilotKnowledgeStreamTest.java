package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.ai.ModelCallResult;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.service.copilot.CopilotConversationService;
import com.mei.zhgy.service.copilot.CopilotConversationTurn;
import com.mei.zhgy.service.copilot.CopilotConversationView;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotResolutionStatus;
import com.mei.zhgy.service.copilot.CopilotRouteResult;
import com.mei.zhgy.service.copilot.CopilotRouteService;
import com.mei.zhgy.service.copilot.InMemoryCopilotConversationRepository;
import com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CopilotKnowledgeStreamTest {
    private static final String ANSWER = "叶面肥应在傍晚或阴天喷施，并避免高温时段。";

    @Test
    void chatStreamsOnlyKnowledgeCountAndStatusAndPersistsScalars() throws Exception {
        var conversations = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        String conversationId = conversations.create(1L).getConversationId();
        var routes = mock(CopilotRouteService.class);
        when(routes.route(any(), eq(1L), anyBoolean())).thenReturn(CopilotRouteResult.builder()
                .intent(CopilotIntent.KNOWLEDGE_QUERY)
                .resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .conversationId(conversationId)
                .traceId("knowledge-stream-trace")
                .build());
        var retriever = mock(KnowledgeEvidenceRetriever.class);
        when(retriever.retrieveWithStatus(any(), eq(1L)))
                .thenReturn(new KnowledgeEvidenceRetriever.RetrievalResult("READY", List.of(evidence())));
        var model = mock(ModelGateway.class);
        when(model.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept(ANSWER);
            return ModelCallResult.builder().success(true).content(ANSWER).build();
        });
        CopilotController controller = new CopilotController(routes, conversations, null, model, retriever,
                null, null, null, null, null, null, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        BaseContext.setCurrentId(1L);
        try {
            String stream = dispatchChat(mvc, "脆李采后为什么需要恢复树势？");
            assertTrue(stream.contains("event:knowledge"), stream);
            assertTrue(stream.contains("<count>1</count>"), stream);
            assertTrue(stream.contains("<status>READY</status>"), stream);
            assertFalse(stream.contains("<items>"), stream);
            assertFalse(stream.contains("raw knowledge preview"), stream);
            assertFalse(stream.contains("EvidenceCard"), stream);
            assertFalse(stream.contains("知识依据"), stream);
            assertTrue(stream.contains("结合当前地块解释"), stream);
            assertFalse(stream.contains("查看知识依据"), stream);

            CopilotConversationView restored = conversations.view(conversationId, 1L);
            CopilotConversationTurn answer = restored.getHistoryTurns().stream()
                    .filter(turn -> "ASSISTANT".equals(turn.getRole())).findFirst().orElseThrow();
            Map<String, Object> metadata = answer.getResponseMetadata();
            assertEquals(1, metadata.get("knowledgeCount"));
            assertEquals("READY", metadata.get("knowledgeStatus"));
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    @Test
    void conversationalFallbackAlsoUsesScalarKnowledgeEventAndPersistence() throws Exception {
        var conversations = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        String conversationId = conversations.create(1L).getConversationId();
        var routes = mock(CopilotRouteService.class);
        when(routes.route(any(), eq(1L), anyBoolean())).thenReturn(CopilotRouteResult.builder()
                .intent(CopilotIntent.DIAGNOSIS_REQUEST)
                .resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .conversationId(conversationId)
                .traceId("fallback-stream-trace")
                .build());
        var retriever = mock(KnowledgeEvidenceRetriever.class);
        when(retriever.retrieveWithStatus(any(), eq(1L)))
                .thenReturn(new KnowledgeEvidenceRetriever.RetrievalResult("READY", List.of(evidence())));
        var model = mock(ModelGateway.class);
        when(model.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept(ANSWER);
            return ModelCallResult.builder().success(true).content(ANSWER).build();
        });
        CopilotController controller = new CopilotController(routes, conversations, null, model, retriever,
                null, null, null, null, null, null, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        BaseContext.setCurrentId(1L);
        try {
            String stream = dispatchChat(mvc, "请评估当前地块的病害风险");
            assertTrue(stream.contains("event:fallback"), stream);
            assertTrue(stream.contains("event:knowledge"), stream);
            assertTrue(stream.contains("<count>1</count>"), stream);
            assertFalse(stream.contains("<items>"), stream);
            assertFalse(stream.contains("raw knowledge preview"), stream);

            CopilotConversationView restored = conversations.view(conversationId, 1L);
            CopilotConversationTurn answer = restored.getHistoryTurns().stream()
                    .filter(turn -> "ASSISTANT".equals(turn.getRole())).findFirst().orElseThrow();
            Map<String, Object> metadata = answer.getResponseMetadata();
            assertEquals(1, metadata.get("knowledgeCount"));
            assertEquals("READY", metadata.get("knowledgeStatus"));
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    @Test
    void greetingPersistsNullKnowledgeMetadataWhenNoRetrievalRuns() throws Exception {
        var conversations = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        String conversationId = conversations.create(1L).getConversationId();
        var routes = mock(CopilotRouteService.class);
        when(routes.route(any(), eq(1L), anyBoolean())).thenReturn(CopilotRouteResult.builder()
                .intent(CopilotIntent.GENERAL_QA)
                .resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .conversationId(conversationId)
                .traceId("greeting-stream-trace")
                .build());
        CopilotController controller = new CopilotController(routes, conversations);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        BaseContext.setCurrentId(1L);
        try {
            String stream = dispatchChat(mvc, "你好");
            assertFalse(stream.contains("event:knowledge"), stream);
            CopilotConversationTurn answer = conversations.view(conversationId, 1L).getHistoryTurns().stream()
                    .filter(turn -> "ASSISTANT".equals(turn.getRole())).findFirst().orElseThrow();
            Map<String, Object> metadata = answer.getResponseMetadata();
            assertTrue(metadata.containsKey("knowledgeCount"));
            assertTrue(metadata.containsKey("knowledgeStatus"));
            assertNull(metadata.get("knowledgeCount"));
            assertNull(metadata.get("knowledgeStatus"));
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    private String dispatchChat(MockMvc mvc, String message) throws Exception {
        MvcResult pending = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private DiagnosisEvidenceVO evidence() {
        return DiagnosisEvidenceVO.builder()
                .id("chunk-knowledge-1")
                .title("脆李叶面肥使用时机")
                .summary("叶面肥应在傍晚或阴天喷施。")
                .preview(new DiagnosisEvidenceVO.Preview(null, null, "raw knowledge preview"))
                .source(DiagnosisEvidenceVO.Source.builder().sourceId("source-1").documentId("document-1")
                        .versionId("version-1").contentHash("hash-1").document("农业知识库").build())
                .score(0.93)
                .build();
    }
}

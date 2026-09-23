package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotResponsePolicyTest {
    @Test
    void choosesSimpleForShortConversation() {
        assertEquals(CopilotResponseMode.SIMPLE, CopilotResponsePolicy.choose(resolved(CopilotIntent.GENERAL_QA), "你好", false, false));
    }

    @Test
    void choosesKnowledgeForProfessionalExplanation() {
        assertEquals(CopilotResponseMode.KNOWLEDGE, CopilotResponsePolicy.choose(resolved(CopilotIntent.KNOWLEDGE_QUERY), "脆李采后为什么需要恢复树势？", false, false));
    }

    @Test
    void choosesGuidedForManagementQuestion() {
        assertEquals(CopilotResponseMode.GUIDED, CopilotResponsePolicy.choose(resolved(CopilotIntent.GENERAL_QA), "脆李采后应该如何管理？", false, true));
    }

    @Test
    void distinguishesMissingImageFromMissingObject() {
        CopilotRouteResult route = resolved(CopilotIntent.DIAGNOSIS_REQUEST);
        assertEquals(CopilotResponseMode.CLARIFICATION, CopilotResponsePolicy.choose(route, "请结合当前地块和现场图片分析可见异常，暂不执行农事操作", false, true));
        assertEquals(CopilotResponseMode.AGENT, CopilotResponsePolicy.choose(route, "请结合当前地块分析风险，暂不执行农事操作", false, true));
    }

    @Test
    void unresolvedRouteUsesClarificationWithoutPrematureKnowledgeSources() {
        CopilotRouteResult route = CopilotRouteResult.builder()
                .intent(CopilotIntent.CLARIFICATION_REQUIRED)
                .resolutionStatus(CopilotResolutionStatus.CLARIFICATION_REQUIRED)
                .requiresClarification(true)
                .traceId("trace-test")
                .build();
        Map<String, Object> evidence = Map.of(
                "title", "脆李物候知识",
                "content", "采后阶段应关注树势恢复。",
                "sourceId", "source-plum",
                "documentId", "doc-plum-1",
                "versionId", "version-1",
                "chunkId", "chunk-7",
                "score", 0.91D);
        CopilotResponseMetadata metadata = CopilotResponsePolicy.build(route, "帮我分析", "message-1", false, false, null, null, "READY", List.of(evidence));
        assertEquals(CopilotResponseMode.CLARIFICATION, metadata.getResponseType());
        assertEquals("message-1", metadata.getMessageId());
        assertTrue(metadata.getSources().isEmpty());
        assertTrue(metadata.getContent().isEmpty());
    }

    @Test
    void metadataIsAdditiveAndJsonSafeForSseConsumers() throws Exception {
        CopilotResponseMetadata metadata = CopilotResponsePolicy.build(
                resolved(CopilotIntent.KNOWLEDGE_QUERY), "脆李采后为什么需要恢复树势？", "message-2", false, false,
                null, null, "READY", List.of(Map.of("title", "物候知识", "content", "恢复树势。", "chunkId", "chunk-1")));
        String json = new ObjectMapper().writeValueAsString(metadata);
        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"responseType\":\"KNOWLEDGE\""));
        assertTrue(json.contains("\"sources\""));
        assertTrue(json.contains("\"chunkId\":\"chunk-1\""));
        assertTrue(metadata.getCards().isEmpty(), "knowledge evidence belongs in the collapsible source section");
    }

    @Test
    void guidedAnswerKeepsSourcesCollapsedInsteadOfDuplicatingEveryChunk() {
        Map<String, Object> evidence = Map.of(
                "title", "采后管理依据",
                "content", "采后恢复树势需要关注水分与营养管理。",
                "chunkId", "chunk-guided");

        CopilotResponseMetadata metadata = CopilotResponsePolicy.build(
                resolved(CopilotIntent.RECOMMENDATION_QUERY), "结合当前地块制定采后管理方案", "message-3", false, true,
                "脆李", "采后恢复期", "READY", List.of(evidence));

        assertEquals(CopilotResponseMode.GUIDED, metadata.getResponseType());
        assertEquals(1, metadata.getCards().size(), "guided正文已有综合方案，知识片段应留在可展开来源中");
        assertEquals(1, metadata.getSources().size());
    }

    @Test
    void evidenceOnlyKeepsStableProvenanceAndCompactsLongQuotes() {
        String longContent = "采后恢复期应结合土壤湿度、树势和天气逐步安排水肥管理，避免一次性大水大肥。"
                + "现场执行前还应核对地块、作物和物候期，异常情况需要先补充证据。";
        Map<String, Object> stable = Map.of(
                "title", "脆李采后管理",
                "content", longContent.repeat(8),
                "documentId", "doc-plum-1",
                "chunkId", "chunk-9",
                "score", 0.88D);
        Map<String, Object> withoutProvenance = Map.of(
                "title", "没有稳定来源的片段",
                "content", "这条内容没有可核验的稳定 ID。",
                "score", 0.87D);

        CopilotResponseMetadata metadata = CopilotResponsePolicy.build(
                resolved(CopilotIntent.KNOWLEDGE_QUERY), "脆李采后如何管理？", "message-5", false, false,
                null, null, "READY", List.of(stable, withoutProvenance));

        assertEquals(1, metadata.getSources().size());
        assertEquals("chunk-9", metadata.getSources().get(0).getId());
        assertFalse(metadata.getSources().get(0).getQuote().isBlank());
        assertTrue(metadata.getSources().get(0).getQuote().length() <= 181);
    }

    @Test
    void diagnosisClarificationOnlyOffersMissingVisualEvidence() {
        CopilotResponseMetadata metadata = CopilotResponsePolicy.build(
                resolved(CopilotIntent.DIAGNOSIS_REQUEST), "我的李子叶片发黄了，该打什么药？", "message-4", false, true,
                "脆李", "花期", "NOT_REQUESTED", List.of());

        assertEquals(CopilotResponseMode.CLARIFICATION, metadata.getResponseType());
        assertEquals(2, metadata.getSuggestions().size());
    }

    private CopilotRouteResult resolved(CopilotIntent intent) {
        return CopilotRouteResult.builder()
                .intent(intent)
                .resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .requiresClarification(false)
                .traceId("trace-test")
                .build();
    }
}

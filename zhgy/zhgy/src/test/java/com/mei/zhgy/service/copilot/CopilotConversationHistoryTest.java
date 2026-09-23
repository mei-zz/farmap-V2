package com.mei.zhgy.service.copilot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotConversationHistoryTest {

    @Test
    void listsOnlyOwnerConversationsAndRestoresTheSavedContext() {
        CopilotConversationService service = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        CopilotConversationSnapshot ownerConversation = service.create(42L);
        CopilotConversationSnapshot otherConversation = service.create(43L);

        CopilotRouteRequest request = new CopilotRouteRequest();
        request.setConversationId(ownerConversation.getConversationId());
        request.setClientMessageId("history-owner-1");
        request.setMessage("继续处理 L-01 脆李的病害风险");
        CopilotConversationService.ConversationHandle handle = service.begin(request, 42L);
        CopilotRouteResult routed = CopilotRouteResult.builder()
                .intent(CopilotIntent.FIELD_STATUS_QUERY)
                .resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .resolvedContext(CopilotStableContext.builder().farmId("7").fieldId("L-01").diagnosisId("diagnosis-1").build())
                .traceId("history-trace")
                .build();
        service.commit(handle, request, routed);

        List<CopilotConversationListItem> ownerHistory = service.list(42L, 24);
        assertEquals(1, ownerHistory.size());
        assertEquals(ownerConversation.getConversationId(), ownerHistory.get(0).getConversationId());
        assertTrue(ownerHistory.get(0).getTitle().contains("L-01"));
        assertFalse(ownerHistory.stream().anyMatch(item -> item.getConversationId().equals(otherConversation.getConversationId())));

        CopilotConversationView resumed = service.resume(ownerConversation.getConversationId(), 42L);
        assertEquals("L-01", resumed.getActiveContext().getFieldId());
        assertEquals("diagnosis-1", resumed.getActiveContext().getDiagnosisId());
        assertEquals(1, resumed.getRecentTurns().size());
        assertEquals("USER", resumed.getRecentTurns().get(0).getRole());

        service.appendAssistantAnswer(ownerConversation.getConversationId(), 42L,
                CopilotIntent.FIELD_STATUS_QUERY, "history-trace", "这是最终用户可见回答。");
        CopilotConversationView withAnswer = service.resume(ownerConversation.getConversationId(), 42L);
        assertEquals(2, withAnswer.getRecentTurns().size());
        assertEquals("USER", withAnswer.getRecentTurns().get(0).getRole());
        assertEquals("ASSISTANT", withAnswer.getRecentTurns().get(1).getRole());
        assertEquals("这是最终用户可见回答。", withAnswer.getRecentTurns().get(1).getMessage());
        assertThrows(CopilotConversationException.class, () -> service.resume(ownerConversation.getConversationId(), 43L));
    }

    @Test
    void keepsTheFullTranscriptForDisplayWhileTheModelContextStaysBounded() {
        CopilotConversationService service = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        CopilotConversationSnapshot conversation = service.create(42L);

        for (int index = 0; index < 12; index++) {
            service.appendAssistantAnswer(conversation.getConversationId(), 42L,
                    CopilotIntent.KNOWLEDGE_QUERY, "history-trace-" + index, "历史回答 " + index);
        }

        CopilotConversationView view = service.resume(conversation.getConversationId(), 42L);
        assertEquals(10, view.getRecentTurns().size(), "模型上下文仍保持最近窗口");
        assertEquals(12, view.getHistoryTurns().size(), "历史面板应可查看完整会话");
        assertEquals("历史回答 0", view.getHistoryTurns().get(0).getMessage());
        assertEquals("历史回答 11", view.getHistoryTurns().get(11).getMessage());
    }

    @Test
    void restoresReferenceImagesAlongsideTheAssistantAnswer() {
        CopilotConversationService service = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        CopilotConversationSnapshot conversation = service.create(42L);
        Map<String, Object> responseMetadata = Map.of(
                "schemaVersion", 1,
                "referenceImages", List.of(Map.of(
                        "refId", "ref-leaf-spot-1",
                        "label", "叶斑病叶片",
                        "imageUrl", "/uploads/reference/leaf-spot-1.jpg",
                        "match", "disease:leaf_spot")),
                "referenceImageStatus", "READY");

        service.appendAssistantAnswer(conversation.getConversationId(), 42L,
                CopilotIntent.KNOWLEDGE_QUERY, "image-history-trace", "叶斑病参考说明。", responseMetadata);

        CopilotConversationView restored = service.resume(conversation.getConversationId(), 42L);
        CopilotConversationTurn answer = restored.getHistoryTurns().get(0);
        assertEquals("ASSISTANT", answer.getRole());
        assertEquals("叶斑病参考说明。", answer.getMessage());
        assertEquals("READY", answer.getResponseMetadata().get("referenceImageStatus"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) answer.getResponseMetadata().get("referenceImages");
        assertEquals("/uploads/reference/leaf-spot-1.jpg", images.get(0).get("imageUrl"));
    }
}

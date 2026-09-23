package com.mei.zhgy.service.copilot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proves that a short confirmation resumes the persisted action intent. */
class CopilotTaskClarificationContinuationTest {
    @Test
    void confirmationResumesTheOriginalTaskRequest() {
        CopilotConversationService conversations = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        CopilotConversationSnapshot created = conversations.create(42L);
        CopilotIntentRouter router = new CopilotIntentRouter();
        CopilotContextResolver resolver = mock(CopilotContextResolver.class);
        when(resolver.resolve(any(), any(), any(), eq(CopilotIntent.ACTION_REQUEST), anyLong()))
                .thenReturn(new CopilotContextResolver.Resolution(
                        CopilotResolutionStatus.CLARIFICATION_REQUIRED,
                        new CopilotStableContext(), "请先选择要创建任务的地块。"))
                .thenReturn(new CopilotContextResolver.Resolution(
                        CopilotResolutionStatus.RESOLVED,
                        CopilotStableContext.builder().farmId("7").fieldId("L-01").build(), null));
        CopilotRouteService service = new CopilotRouteService(router, resolver,
                new CopilotSessionContextStore(), conversations);

        CopilotRouteRequest first = new CopilotRouteRequest();
        first.setConversationId(created.getConversationId());
        first.setClientMessageId("task-clarify-1");
        first.setMessage("帮我创建明天的浇水任务");
        CopilotRouteResult pending = service.route(first, 42L);
        assertEquals(CopilotIntent.ACTION_REQUEST, pending.getIntent());
        assertEquals(CopilotResolutionStatus.CLARIFICATION_REQUIRED, pending.getResolutionStatus());
        assertEquals("ACTION", conversations.resume(created.getConversationId(), 42L)
                .getPendingClarification().getType());

        CopilotRouteRequest confirmation = new CopilotRouteRequest();
        confirmation.setConversationId(created.getConversationId());
        confirmation.setClientMessageId("task-clarify-2");
        confirmation.setMessage("对");
        CopilotPageContext page = new CopilotPageContext();
        page.setFarmId("7"); page.setFieldId("L-01");
        confirmation.setPageContext(page);
        CopilotRouteResult resumed = service.route(confirmation, 42L);

        assertEquals(CopilotIntent.ACTION_REQUEST, resumed.getIntent());
        assertEquals(CopilotCapability.AGENT_START, resumed.getCapability());
        assertEquals(CopilotResolutionStatus.RESOLVED, resumed.getResolutionStatus());
        assertEquals("L-01", resumed.getResolvedContext().getFieldId());
        assertEquals("对", resumed.getOriginalQuery());
        assertEquals("帮我创建明天的浇水任务", resumed.getRequestedAction());
        assertNull(conversations.resume(created.getConversationId(), 42L).getPendingClarification());
        verify(resolver, times(2)).resolve(any(), any(), any(), eq(CopilotIntent.ACTION_REQUEST), anyLong());
    }
}

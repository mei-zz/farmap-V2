package com.mei.zhgy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuestCopilotControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void rejectsAuthorityAndInvalidHistory() throws Exception {
        for (String body : List.of("{\"message\":\"hi\",\"userId\":1}", "{\"message\":\"hi\",\"images\":[]}", "{\"message\":\"hi\",\"history\":[{\"role\":\"system\",\"text\":\"x\"}]}", "{\"message\":\" \"}")) {
            assertThrows(ResponseStatusException.class, () -> GuestCopilotController.validate(mapper.readTree(body)));
        }
    }
    @Test void eachRequestHasFreshSessionAndNoTools() throws Exception {
        PiAgentRuntime runtime = mock(PiAgentRuntime.class);
        CountDownLatch calls = new CountDownLatch(2);
        java.util.Set<String> sessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        when(runtime.run(anyString(), anyString(), anyString(), anyList(), anyList(), any(), notNull())).thenAnswer(invocation -> {
            sessions.add(invocation.getArgument(0));
            assertEquals(List.of(), invocation.getArgument(4));
            calls.countDown();
            return new PiAgentRuntime.Result("ok", "completed");
        });
        GuestCopilotController controller = new GuestCopilotController(runtime);
        try {
            controller.chat(mapper.readTree("{\"message\":\"你好\"}"));
            controller.chat(mapper.readTree("{\"message\":\"你好\"}"));
            assertTrue(calls.await(5, TimeUnit.SECONDS));
            assertEquals(2, sessions.size());
            assertTrue(sessions.stream().allMatch(s -> s.startsWith("guest:")));
        } finally { controller.close(); }
    }
}

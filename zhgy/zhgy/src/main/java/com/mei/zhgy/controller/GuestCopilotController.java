package com.mei.zhgy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Public, ephemeral chat. It has no access to owner-scoped services or tools. */
@RestController
@RequestMapping("/api/copilot/guest")
public class GuestCopilotController {
    private static final String SYSTEM = "你是 FarMap 农业助手，只提供一般农业知识和建议。没有农场、地块、历史记录、图片或实时数据的访问权限；不要声称读取或操作了它们。信息不足时请说明并询问必要条件。涉及农药等高风险操作时提醒遵循当地标签和专业人员指导。";
    private final PiAgentRuntime runtime;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
    private final ArrayDeque<Long> requests = new ArrayDeque<>();

    public GuestCopilotController(PiAgentRuntime runtime) { this.runtime = runtime; }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody JsonNode body) {
        Input input = validate(body);
        synchronized (requests) {
            long now = System.currentTimeMillis();
            while (!requests.isEmpty() && requests.peekFirst() <= now - 60_000) requests.removeFirst();
            if (requests.size() >= 30) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请稍后再试");
            requests.addLast(now);
        }
        SseEmitter emitter = new SseEmitter(190_000L);
        try { executor.execute(() -> stream(input, emitter)); }
        catch (RejectedExecutionException e) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "服务繁忙，请稍后再试"); }
        return emitter;
    }

    static Input validate(JsonNode body) {
        if (body == null || !body.isObject() || !allowed(body, Set.of("message", "history"))) throw bad();
        String message = text(body.get("message"), 4000);
        List<PiAgentRuntime.HistoryMessage> history = new ArrayList<>();
        JsonNode items = body.get("history");
        int total = message.length();
        if (items != null) {
            if (!items.isArray() || items.size() > 12) throw bad();
            for (JsonNode item : items) {
                if (!item.isObject() || !allowed(item, Set.of("role", "text"))) throw bad();
                JsonNode role = item.get("role");
                if (role == null || !role.isTextual() || !Set.of("user", "assistant").contains(role.textValue())) throw bad();
                String value = text(item.get("text"), 4000);
                total += value.length();
                if (total > 16000) throw bad();
                history.add(new PiAgentRuntime.HistoryMessage(role.textValue(), value));
            }
        }
        return new Input(message, List.copyOf(history));
    }
    private static boolean allowed(JsonNode node, Set<String> keys) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) if (!keys.contains(names.next())) return false;
        return true;
    }
    private static String text(JsonNode node, int max) {
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max) throw bad();
        return node.textValue();
    }
    private static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求格式无效"); }
    record Input(String message, List<PiAgentRuntime.HistoryMessage> history) {}

    private void stream(Input input, SseEmitter emitter) {
        try {
            PiAgentRuntime.Result result = runtime.run("guest:" + UUID.randomUUID(), input.message(), SYSTEM,
                    input.history(), List.of(), delta -> sendDelta(emitter, delta), event -> sendRuntimeProgress(emitter, event));
            if (!"completed".equals(result.status())) throw new IllegalStateException("Incomplete response");
            emitter.send(SseEmitter.event().name("done").data(Map.of("status", "COMPLETED")));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", "暂时无法回答，请稍后再试。")));
                emitter.send(SseEmitter.event().name("done").data(Map.of("status", "FAILED")));
                emitter.complete();
            } catch (IOException | IllegalStateException ignored) { emitter.complete(); }
        }
    }
    private static void sendDelta(SseEmitter emitter, String delta) {
        if (delta == null || delta.isEmpty()) return;
        try { emitter.send(SseEmitter.event().name("content.delta").data(Map.of("delta", delta))); }
        catch (IOException e) { throw new IllegalStateException("Client disconnected", e); }
    }

    private static void sendRuntimeProgress(SseEmitter emitter, Map<String, Object> event) {
        Object type = event.get("type");
        if (!(type instanceof String name) || !Set.of("turn_start", "thinking_start", "thinking_end").contains(name)) return;
        try { emitter.send(SseEmitter.event().name("agent.runtime").data(Map.of("type", name))); }
        catch (IOException e) { throw new IllegalStateException("Client disconnected", e); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}

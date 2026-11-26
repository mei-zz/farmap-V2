package com.mei.zhgy.service.agent.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A private, lazy JSON-lines bridge. The worker never receives the server's ambient credentials. */
@Service
public class PiAgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(PiAgentRuntime.class);
    public record HistoryMessage(String role, String text) {}
    @FunctionalInterface public interface ProgressExecutor {
        ToolReply execute(JsonNode arguments, Consumer<ToolReply> progress);
    }
    public record ReadTool(String name, String description, Map<String, Object> parameters,
                           Function<JsonNode, ToolReply> execute, boolean multimodal, ProgressExecutor progressExecutor) {
        public ReadTool(String name, String description, Map<String, Object> parameters,
                        Function<JsonNode, ToolReply> execute) {
            this(name, description, parameters, execute, false, null);
        }
        public ReadTool(String name, String description, Map<String, Object> parameters,
                        Function<JsonNode, ToolReply> execute, boolean multimodal) {
            this(name, description, parameters, execute, multimodal, null);
        }
    }
    public record ToolCitation(int index, String evidenceId, String modality, String title, String sourceName,
                               String stageLabel, String snippet, String imageUrl, String documentName,
                               Double score, String expertStatus) {}
    public record ToolReply(Object data, Map<String, Object> uiBlock, List<ToolCitation> citations, boolean isError) {
        public ToolReply {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
        public ToolReply(Object data, Map<String, Object> uiBlock, boolean isError) {
            this(data, uiBlock, List.of(), isError);
        }
        public ToolReply withCitations(List<ToolCitation> references) {
            return new ToolReply(data, uiBlock, references, isError);
        }
        public static ToolReply result(Object data) { return new ToolReply(data, null, false); }
        public static ToolReply error(String code) { return new ToolReply(Map.of("status", code), null, true); }
    }
    public record ToolObservation(String name, ToolReply reply) {}
    public record Result(String text, String status) {}

    private static final int MAX_TOOL_TEXT = 30_000;
    private static final int MAX_ARGUMENTS = 8_000;
    private static final int MAX_TOOL_CALLS = 16;
    private static final int MAX_TOOL_SETS = 128;
    private static final long TOOL_SET_TTL = TimeUnit.MINUTES.toNanos(30);

    @Value("${farmap.agent.pi.enabled:true}") private boolean enabled = true;
    @Value("${farmap.agent.pi.node:node}") private String node = "node";
    @Value("${farmap.agent.pi.worker:pi-runtime/worker.mjs}") private String worker = "pi-runtime/worker.mjs";
    @Value("${farmap.agent.pi.timeout-ms:180000}") private long timeoutMs = 180000;
    @Value("${farmap.agent.pi.query-timeout-ms:12000}") private long queryTimeoutMs = 12000;
    @Value("${farmap.agent.pi.multimodal-timeout-ms:35000}") private long multimodalTimeoutMs = 35000;
    @Value("${farmap.ai.opencode.enabled:false}") private boolean openCodeEnabled;
    @Value("${farmap.ai.opencode.api-key:}") private String openCodeApiKey;
    @Value("${farmap.ai.opencode.url:https://opencode.ai/inference/go/openai/v1/chat/completions}") private String openCodeUrl;
    @Value("${farmap.ai.opencode.model:deepseek-v4.1-flash}") private String openCodeModel;
    @Value("${bailian.api.key:}") private String apiKey = "";
    @Value("${bailian.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}") private String baseUrl;
    @Value("${farmap.ai.router.fast-model:qwen-flash}") private String model;
    @Value("${farmap.ai.router.default-model:qwen-plus-2025-07-28}") private String fallbackModel;
    @Value("${farmap.ai.generation.copilot-max-output-tokens:2048}") private int maxOutputTokens = 2048;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Object lock = new Object();
    private final ExecutorService queries = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
        Thread thread = new Thread(runnable, "farmap-pi-tool");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledThreadPoolExecutor toolTimers = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "farmap-pi-tool-deadline");
        thread.setDaemon(true);
        return thread;
    });
    {
        toolTimers.setRemoveOnCancelPolicy(true);
    }
    private Worker current;
    private boolean closed;

    public boolean isEnabled() { return enabled; }

    public Result run(String session, String message, String systemPrompt, List<HistoryMessage> history,
                      List<ReadTool> tools, Consumer<String> delta, Consumer<Map<String, Object>> event) {
        return run(session, null, null, message, systemPrompt, history, tools, delta, event, null);
    }

    public Result run(String session, Long contextVersion, String scope, String message, String systemPrompt,
                      List<HistoryMessage> history, List<ReadTool> tools, Consumer<String> delta,
                      Consumer<Map<String, Object>> event) {
        return run(session, contextVersion, scope, message, systemPrompt, history, tools, delta, event, null);
    }

    public Result run(String session, Long contextVersion, String scope, String message, String systemPrompt,
                      List<HistoryMessage> history, List<ReadTool> tools, Consumer<String> delta,
                      Consumer<Map<String, Object>> event, Consumer<ToolObservation> observation) {
        return run(session, contextVersion, scope, message, systemPrompt, null, null, null, history, tools, delta, event, observation);
    }

    public Result run(String session, Long contextVersion, String scope, String message, String systemPrompt,
                      String environment, String turnId, String lastCompletedTurnId, List<HistoryMessage> history,
                      List<ReadTool> tools, Consumer<String> delta, Consumer<Map<String, Object>> event,
                      Consumer<ToolObservation> observation) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", "start"); request.put("session", session); request.put("message", message);
        if (contextVersion != null) request.put("contextVersion", contextVersion);
        if (scope != null) request.put("scope", scope);
        if (environment != null) request.put("environment", environment);
        if (turnId != null) request.put("turnId", turnId);
        if (lastCompletedTurnId != null) request.put("lastCompletedTurnId", lastCompletedTurnId);
        request.put("systemPrompt", systemPrompt); request.put("history", history == null ? List.of() : history);
        List<ReadTool> registered = tools == null ? List.of() : List.copyOf(tools);
        request.put("tools", registered.stream().map(t -> Map.of("name", t.name(),
                "description", t.description(), "parameters", t.parameters())).toList());
        return execute(request, registered, delta, event, observation);
    }

    public Result control(String session, String op, String message, Consumer<String> delta,
                          Consumer<Map<String, Object>> event) {
        return control(session, null, op, message, delta, event);
    }

    public Result control(String session, Long contextVersion, String op, String message, Consumer<String> delta,
                          Consumer<Map<String, Object>> event) {
        return control(session, contextVersion, op, message, delta, event, null);
    }

    public Result control(String session, Long contextVersion, String op, String message, Consumer<String> delta,
                          Consumer<Map<String, Object>> event, Consumer<ToolObservation> observation) {
        if (!Set.of("abort", "continue", "steer", "followUp").contains(op)) throw failure("INVALID_OPERATION");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", op); request.put("session", session);
        if (contextVersion != null) request.put("contextVersion", contextVersion);
        if (message != null) request.put("message", message);
        return execute(request, List.of(), delta, event, observation);
    }

    private Result execute(Map<String, Object> request, List<ReadTool> tools, Consumer<String> delta,
                           Consumer<Map<String, Object>> event, Consumer<ToolObservation> observation) {
        if (!enabled) throw failure("DISABLED");
        if (!(request.get("session") instanceof String) || ((String) request.get("session")).isBlank()) throw failure("INVALID_SESSION");
        String id = UUID.randomUUID().toString(); request.put("id", id);
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(2048);
        Map<String, ToolInvocation> inFlight = new LinkedHashMap<>();
        Worker w;
        ToolSet initial = tools.isEmpty() ? null : new ToolSet(request, tools);
        synchronized (lock) {
            w = acquire();
            if (initial != null) {
                w.register(id, initial);
                request.put("toolsetId", id);
            }
            w.pending.put(id, queue);
        }
        boolean keepRegistration = false;
        int toolCalls = 0;
        try {
            write(w, request);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
            while (true) {
                long remaining = deadline - System.nanoTime();
                Object item = remaining > 0 ? queue.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (item == null) { keepRegistration = true; abortRunQuietly(w, request); throw failure("TIMEOUT"); }
                if (w.overflowed.contains(id)) {
                    abortRunQuietly(w, request);
                    throw failure("STREAM_QUEUE_OVERFLOW");
                }
                if (item instanceof ToolProgress progress) {
                    if (observation != null && inFlight.containsKey(progress.callId())) {
                        try { observation.accept(new ToolObservation(progress.name(), progress.reply())); }
                        catch (RuntimeException error) { keepRegistration = true; abortRunQuietly(w, request); throw error; }
                    }
                    continue;
                }
                if (item instanceof ToolCompletion completion) {
                    ToolInvocation invocation = inFlight.remove(completion.callId());
                    if (invocation != null) invocation.timeout().cancel(false);
                    if (observation != null && !completion.reply().isError()) {
                        try { observation.accept(new ToolObservation(completion.name(), completion.reply())); }
                        catch (RuntimeException error) { keepRegistration = true; abortRunQuietly(w, request); throw error; }
                    }
                    sendToolReply(w, id, completion.callId(), completion.text(), completion.reply().isError());
                    continue;
                }
                JsonNode frame = (JsonNode) item;
                String type = frame.path("type").asText();
                if ("delta".equals(type) || "event".equals(type)) {
                    try {
                        if ("delta".equals(type) && delta != null) delta.accept(frame.path("text").asText(""));
                        if ("event".equals(type) && event != null) event.accept(sanitize(frame.path("event")));
                    } catch (RuntimeException error) {
                        keepRegistration = true;
                        abortRunQuietly(w, request);
                        throw error;
                    }
                } else if ("tool_call_request".equals(type)) {
                    if (++toolCalls > MAX_TOOL_CALLS) {
                        abortRunQuietly(w, request); throw failure("TOOL_LIMIT");
                    }
                    dispatchTool(w, request, frame, queue, inFlight);
                } else if ("done".equals(type)) {
                    String status = frame.path("status").asText();
                    if (!"completed".equals(status) && !"aborted".equals(status)) throw failure("INVALID_RESPONSE");
                    keepRegistration = "aborted".equals(status);
                    if ("continue".equals(request.get("op")) && !keepRegistration) w.removeResumed(request);
                    return new Result(frame.path("text").asText(""), status);
                } else if ("error".equals(type)) { String code = frame.path("code").asText(); throw failure(Set.of("SESSION_BUSY", "STALE_VERSION", "SESSION_PAUSED", "SESSION_LIMIT", "SESSION_NOT_FOUND", "SESSION_IDLE", "QUEUE_LIMIT", "NOT_RESUMABLE", "INVALID_REQUEST", "INPUT_LIMIT", "UNKNOWN_OPERATION", "PROVIDER_ERROR", "RUN_FAILED").contains(code) ? code : "WORKER_ERROR"); }
            }
        } catch (InterruptedException e) {
            keepRegistration = true;
            Thread.currentThread().interrupt(); abortRunQuietly(w, request); throw failure("INTERRUPTED");
        } catch (IOException e) {
            failWorker(w); throw failure("WORKER_UNAVAILABLE");
        } finally {
            synchronized (queue) {
                w.pending.remove(id, queue);
                w.overflowed.remove(id);
            }
            inFlight.values().forEach(ToolInvocation::cancel);
            if (initial != null) {
                if (keepRegistration) initial.resumeUntil = System.nanoTime() + TOOL_SET_TTL;
                else w.toolSets.remove(id, initial);
            }
        }
    }

    private void dispatchTool(Worker w, Map<String, Object> request, JsonNode frame, BlockingQueue<Object> queue,
                              Map<String, ToolInvocation> inFlight) throws IOException {
        String id = (String) request.get("id");
        String callId = frame.path("callId").asText();
        String toolsetId = frame.path("toolsetId").asText();
        String name = frame.path("toolName").asText();
        JsonNode args = frame.path("args");
        if (!callId.matches("[A-Za-z0-9_-]{1,100}") || inFlight.containsKey(callId)) return;
        boolean sameStart = "start".equals(request.get("op")) && id.equals(toolsetId);
        boolean continuation = "continue".equals(request.get("op"));
        ToolSet toolSet = sameStart || continuation ? w.toolSets.get(toolsetId) : null;
        ReadTool tool = toolSet != null && toolSet.matches(request) ? toolSet.tools.get(name) : null;
        if (tool == null || !args.isObject() || args.toString().length() > MAX_ARGUMENTS) {
            sendToolReply(w, id, callId, "{\"status\":\"INVALID_TOOL_REQUEST\"}", true);
            return;
        }
        AtomicBoolean completed = new AtomicBoolean();
        FutureTask<Void> query = new FutureTask<>(() -> {
            ToolCompletion completion = invoke(tool, callId, args,
                    progress -> { if (!completed.get()) receive(w, id, new ToolProgress(callId, name, progress)); });
            if (completed.compareAndSet(false, true)) receive(w, id, completion);
            return null;
        });
        ScheduledFuture<?> timeout = null;
        try {
            long timeoutMillis = tool.multimodal() ? multimodalTimeoutMs : queryTimeoutMs;
            timeout = toolTimers.schedule(() -> {
                if (!completed.compareAndSet(false, true)) return;
                query.cancel(true);
                String text = "{\"status\":\"TOOL_TIMEOUT\",\"retryable\":true,\"hint\":\"该数据源响应超时，请基于已有证据回答并说明缺口\"}";
                receive(w, id, new ToolCompletion(callId, tool.name(), ToolReply.error("TOOL_TIMEOUT"), text));
            }, Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
            queries.execute(query);
            inFlight.put(callId, new ToolInvocation(query, timeout));
        } catch (RejectedExecutionException busy) {
            if (timeout != null) timeout.cancel(false);
            if (completed.compareAndSet(false, true)) sendToolReply(w, id, callId, "{\"status\":\"TOOL_BUSY\"}", true);
            else if (timeout != null) inFlight.put(callId, new ToolInvocation(query, timeout));
        }
    }

    private ToolCompletion invoke(ReadTool tool, String callId, JsonNode args, Consumer<ToolReply> progress) {
        ToolReply reply;
        String text;
        try {
            reply = tool.progressExecutor() == null ? tool.execute().apply(args) : tool.progressExecutor().execute(args, progress);
            text = mapper.writeValueAsString(reply.data());
            if (text.length() > MAX_TOOL_TEXT) {
                reply = ToolReply.error("TOOL_RESULT_TOO_LARGE");
                text = "{\"status\":\"TOOL_RESULT_TOO_LARGE\"}";
            }
        } catch (RuntimeException | IOException ex) {
            reply = ToolReply.error("TOOL_UNAVAILABLE");
            text = "{\"status\":\"TOOL_UNAVAILABLE\"}";
        }
        return new ToolCompletion(callId, tool.name(), reply, text);
    }

    private void sendToolReply(Worker w, String id, String callId, String text, boolean isError) throws IOException {
        write(w, Map.of("op", "tool_call_response", "id", id, "callId", callId, "text", text, "isError", isError));
    }

    private void write(Worker w, Map<String, Object> message) throws IOException {
        synchronized (w.writeLock) {
            if (!w.process.isAlive()) throw new IOException("Pi worker exited");
            w.input.write(mapper.writeValueAsString(message)); w.input.newLine(); w.input.flush();
        }
    }

    private Map<String, Object> sanitize(JsonNode source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("type", "name", "toolName", "toolCallId", "status", "phase")) {
            JsonNode value = source.get(key);
            if (value != null && value.isTextual()) {
                String text = value.asText();
                if (text.matches("[A-Za-z0-9_.-]{1,100}")) safe.put(key, text);
            }
        }
        JsonNode isError = source.get("isError");
        if (isError != null && isError.isBoolean()) safe.put("isError", isError.booleanValue());
        if ("message_end".equals(safe.get("type"))) {
            JsonNode usage = source.path("usage");
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String key : List.of("inputTokens", "outputTokens", "cacheReadTokens")) {
                JsonNode count = usage.path(key);
                if (count.isIntegralNumber() && count.canConvertToLong() && count.longValue() >= 0) {
                    counts.put(key, count.longValue());
                }
            }
            if (!counts.isEmpty()) safe.put("usage", counts);
        }
        return safe;
    }

    private Worker acquire() {
        synchronized (lock) {
            if (closed) throw failure("CLOSED");
            if (current != null && current.process.isAlive()) return current;
            try {
                ProcessBuilder builder = new ProcessBuilder(node, worker);
                Map<String, String> env = builder.environment();
                Map<String, String> inherited = new HashMap<>(env); env.clear();
                for (String name : List.of("PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "HOME", "USERPROFILE", "TMP", "TEMP", "LANG", "LC_ALL")) {
                    if (inherited.containsKey(name)) env.put(name, inherited.get(name));
                }
                env.put("FARMAP_OPENCODE_ENABLED", String.valueOf(openCodeEnabled));
                if (openCodeEnabled) {
                    env.put("FARMAP_OPENCODE_API_KEY", openCodeApiKey == null ? "" : openCodeApiKey);
                    env.put("FARMAP_OPENCODE_URL", openCodeUrl == null ? "" : openCodeUrl);
                    env.put("FARMAP_OPENCODE_MODEL", openCodeModel == null ? "" : openCodeModel);
                } else {
                    env.put("FARMAP_PI_API_KEY", apiKey == null ? "" : apiKey);
                    env.put("FARMAP_PI_BASE_URL", baseUrl == null ? "" : baseUrl);
                    env.put("FARMAP_PI_MODEL", model == null ? "" : model);
                    env.put("FARMAP_PI_FALLBACK_MODEL", fallbackModel == null ? "" : fallbackModel);
                }
                env.put("FARMAP_PI_MAX_OUTPUT_TOKENS", String.valueOf(Math.max(256, maxOutputTokens)));
                Worker w = new Worker(builder.start()); current = w;
                Thread reader = new Thread(() -> read(w), "farmap-pi-stdout"); reader.setDaemon(true); reader.start();
                Thread stderr = new Thread(() -> readStderr(w), "farmap-pi-stderr"); stderr.setDaemon(true); stderr.start();
                return w;
            } catch (IOException e) { throw failure("WORKER_UNAVAILABLE"); }
        }
    }

    private void read(Worker w) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(w.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode frame;
                try { frame = mapper.readTree(line); } catch (IOException ignored) { continue; }
                if (frame == null) continue;
                receive(w, frame.path("id").asText(), frame);
            }
        } catch (IOException ignored) { } finally { failWorker(w); }
    }

    private void receive(Worker w, String id, Object frame) {
        BlockingQueue<Object> queue = w.pending.get(id);
        if (queue == null) return;
        synchronized (queue) {
            if (w.pending.get(id) != queue || w.overflowed.contains(id)) return;
            if (!queue.offer(frame)) {
                w.overflowed.add(id);
                queue.clear();
                queue.offer(mapper.createObjectNode().put("type", "error"));
            }
        }
    }

    private void readStderr(Worker w) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(w.process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                // Only forward structured worker diagnostics, which contain no provider payload.
                if (line.matches("FARMAP_PI [A-Z0-9_]{1,64}")) log.warn("{}", line);
                else if (line.matches("(?:[A-Za-z]+Error|Error):.*")) log.warn("Pi worker stderr: {}", line.substring(0, line.indexOf(':')));
                else if (log.isDebugEnabled()) log.debug("Pi worker emitted a stderr diagnostic");
            }
        } catch (IOException ignored) { /* the stdout reader owns process lifecycle */ }
    }

    private void abortRunQuietly(Worker w, Map<String, Object> request) {
        if (!Set.of("start", "continue").contains(request.get("op"))) return;
        try {
            Map<String, Object> abort = new LinkedHashMap<>();
            abort.put("id", UUID.randomUUID().toString()); abort.put("op", "abort"); abort.put("session", request.get("session"));
            if (request.get("contextVersion") != null) abort.put("contextVersion", request.get("contextVersion"));
            write(w, abort);
        } catch (IOException ignored) { failWorker(w); }
    }

    private void failWorker(Worker w) {
        synchronized (lock) { if (current == w) current = null; }
        w.process.destroyForcibly();
        w.toolSets.clear();
        JsonNode error = mapper.createObjectNode().put("type", "error");
        w.pending.values().forEach(q -> { q.clear(); q.offer(error); });
    }

    @PreDestroy public void close() {
        Worker w;
        synchronized (lock) { closed = true; w = current; current = null; }
        if (w != null) failWorker(w);
        queries.shutdownNow();
        toolTimers.shutdownNow();
    }

    private static IllegalStateException failure(String code) { return new IllegalStateException("PI_" + code); }
    private record ToolCompletion(String callId, String name, ToolReply reply, String text) {}
    private record ToolProgress(String callId, String name, ToolReply reply) {}
    private record ToolInvocation(Future<?> query, Future<?> timeout) {
        void cancel() {
            timeout.cancel(false);
            query.cancel(true);
        }
    }

    private static final class ToolSet {
        final Object session;
        final Object version;
        final Map<String, ReadTool> tools;
        volatile long resumeUntil;

        ToolSet(Map<String, Object> request, List<ReadTool> tools) {
            session = request.get("session");
            version = request.get("contextVersion");
            Map<String, ReadTool> registered = new LinkedHashMap<>();
            for (ReadTool tool : tools) {
                if (registered.putIfAbsent(tool.name(), tool) != null) throw failure("DUPLICATE_TOOL");
            }
            this.tools = Map.copyOf(registered);
        }

        boolean matches(Map<String, Object> request) {
            return session.equals(request.get("session")) && Objects.equals(version, request.get("contextVersion"));
        }
    }

    private static final class Worker {
        final Process process;
        final BufferedWriter input;
        final Object writeLock = new Object();
        final ConcurrentMap<String, BlockingQueue<Object>> pending = new ConcurrentHashMap<>();
        final Set<String> overflowed = ConcurrentHashMap.newKeySet();
        final ConcurrentMap<String, ToolSet> toolSets = new ConcurrentHashMap<>();
        Worker(Process process) {
            this.process = process;
            this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        void register(String id, ToolSet tools) {
            long now = System.nanoTime();
            toolSets.entrySet().removeIf(entry -> entry.getValue().resumeUntil != 0 && now - entry.getValue().resumeUntil >= 0);
            if (toolSets.size() >= MAX_TOOL_SETS) {
                toolSets.entrySet().stream().filter(entry -> entry.getValue().resumeUntil != 0)
                        .min(Comparator.comparingLong(entry -> entry.getValue().resumeUntil))
                        .ifPresent(entry -> toolSets.remove(entry.getKey(), entry.getValue()));
            }
            if (toolSets.size() >= MAX_TOOL_SETS) throw failure("SESSION_LIMIT");
            toolSets.put(id, tools);
        }

        void removeResumed(Map<String, Object> request) {
            toolSets.entrySet().removeIf(entry -> entry.getValue().resumeUntil != 0 && entry.getValue().matches(request));
        }
    }
}






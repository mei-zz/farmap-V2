package com.mei.zhgy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.agent.AgentRun;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.agent.ProposedAction;
import com.mei.zhgy.service.agent.RunStatus;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import com.mei.zhgy.service.copilot.*;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import com.mei.zhgy.vo.WeatherForecastDay;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CopilotPiRuntimeIntegrationTest {
    @Test
    void streamsTrustedWeatherBlocksAndRestoresThemForTheConversationOwner() throws Exception {
        var conversations = new CopilotConversationService(new InMemoryCopilotConversationRepository());
        String conversationId = conversations.create(1L).getConversationId();
        CopilotRouteService routes = mock(CopilotRouteService.class);
        when(routes.route(any(), eq(1L), anyBoolean())).thenReturn(CopilotRouteResult.builder()
                .intent(CopilotIntent.GENERAL_QA).traceId("test-weather").conversationId(conversationId)
                .resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .resolvedContext(CopilotStableContext.builder().farmId("42").build()).build());
        var fields = mock(com.mei.zhgy.service.field.FarmFieldService.class);
        var weather = mock(com.mei.zhgy.service.CurrentWeatherService.class);
        when(weather.get(42, 1L)).thenReturn(new com.mei.zhgy.vo.CurrentWeatherResponse(42, "测试农场", "READY",
                "23", "多云", "62", null, null, "2026-11-20 12:00", java.time.Instant.now(),
                "OPEN_METEO", "PROVIDER", "CURRENT", null, null, null, null, null, null, List.of()));
        CopilotController controller = new CopilotController(new CopilotApplicationService(routes, conversations, null, null, null,
                null, fields, null, null, null, null));
        controller.application.setCurrentWeatherService(weather);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        BaseContext.setCurrentId(1L);
        try {
            MvcResult pending = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"查询天气\"}")).andExpect(request().asyncStarted()).andReturn();
            String stream = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(stream.contains("event:run.started"));
            assertTrue(stream.contains("event:ui.block.created"));
            assertTrue(stream.contains("WeatherCard"));
            assertTrue(stream.contains("Open-Meteo 气象服务"));
            assertTrue(stream.contains("event:run.completed"));
            verify(weather).get(42, 1L);
            var restored = conversations.view(conversationId, 1L);
            var blocks = restored.getHistoryTurns().get(0).getResponseMetadata().get("uiBlocks");
            assertTrue(String.valueOf(blocks).contains("WeatherCard"));
            assertThrows(CopilotConversationException.class, () -> conversations.view(conversationId, 2L));
        } finally { BaseContext.removeCurrentId(); }
    }

    @Test
    @EnabledIfSystemProperty(named = "farmap.pi.integration", matches = "true")
    void chatStreamsThroughRealPiAndReadTool() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ObjectMapper mapper = new ObjectMapper();
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            String delta = requests.size() == 1
                    ? "{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_field_context\",\"arguments\":\"{}\"}}]}"
                    : "{\"content\":\"pi integration answer\"}";
            String finish = requests.size() == 1 ? "tool_calls" : "stop";
            String body = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\ndata: [DONE]\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        PiAgentRuntime runtime = new PiAgentRuntime();
        ReflectionTestUtils.setField(runtime, "apiKey", "test-key");
        ReflectionTestUtils.setField(runtime, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        ReflectionTestUtils.setField(runtime, "model", "mock");
        try {
            CopilotRouteService routes = mock(CopilotRouteService.class);
            when(routes.route(any(), eq(1L), anyBoolean(), eq(false))).thenReturn(CopilotRouteResult.builder().intent(CopilotIntent.GENERAL_QA).resolutionStatus(CopilotResolutionStatus.RESOLVED).build());
            CopilotController controller = new CopilotController(new CopilotApplicationService(routes));
            controller.application.setPiRuntime(runtime);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
            BaseContext.setCurrentId(1L);
            MvcResult pending = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"如何管理土壤\"}")).andExpect(request().asyncStarted()).andReturn();
            MvcResult completed = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
            String output = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(output.contains("event:content.delta"), output);
            assertTrue(output.contains("pi integration answer"), output);
            assertEquals(2, requests.size());
            var messages = mapper.readTree(requests.get(1)).path("messages");
            boolean sawTool = false;
            for (var message : messages) if ("tool".equals(message.path("role").asText()) && "call_1".equals(message.path("tool_call_id").asText())) sawTool = true;
            assertTrue(sawTool, requests.get(1));

            ReflectionTestUtils.setField(controller.application, "allowUngrounded", false);
            var summaryRequest = new CopilotApplicationService.AnswerRequest();
            summaryRequest.message = "总结这次查询";
            summaryRequest.intent = "OPERATIONS_STATUS_QUERY";
            summaryRequest.evidence = java.util.Map.of("status", "caller-reported-task-state");
            var summary = controller.answer(summaryRequest).getData();
            assertEquals("pi integration answer", summary.get("answer"));
            assertEquals("CLIENT_REPORTED", summary.get("sourceProvenance"));
            assertEquals(3, requests.size());
            assertTrue(requests.get(2).contains("caller-reported-task-state"));
        } finally { BaseContext.removeCurrentId(); runtime.close(); server.stop(0); }
    }

    @Test
    @EnabledIfSystemProperty(named = "farmap.pi.integration", matches = "true")
    void parameterizedToolCallsReadRealServicesInParallelWithoutInjectingResultsAsPrompt() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        List<String> queries = new CopyOnWriteArrayList<>();
        List<Integer> retrievalCountsAtProvider = new CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch concurrentReads = new CountDownLatch(2);
        var knowledge = mock(KnowledgeEvidenceRetriever.class);
        when(knowledge.retrieveWithStatus(any(), eq(1L))).thenAnswer(invocation -> {
            com.mei.zhgy.dto.DiagnosisAnalyzeRequest query = invocation.getArgument(0);
            queries.add(query.getQuery() + ":" + query.getFieldId() + ":" + query.getTopK());
            concurrentReads.countDown();
            if (!concurrentReads.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Queries were not dispatched concurrently");
            return new KnowledgeEvidenceRetriever.RetrievalResult("READY", List.of(DiagnosisEvidenceVO.builder()
                    .id(query.getQuery()).title("evidence-" + query.getQuery()).summary("authorized-result-" + query.getQuery()).build()));
        });
        var fields = mock(FarmFieldService.class);
        when(fields.get("west-field", 1L)).thenReturn(FarmField.builder().fieldId("west-field").farmId(42).crop("plum").build());
        when(fields.get("east-field", 1L)).thenReturn(FarmField.builder().fieldId("east-field").farmId(42).name("east-field-live-name").crop("plum").build());
        var weather = mock(com.mei.zhgy.service.CurrentWeatherService.class);
        when(weather.get(42, 1L)).thenReturn(new com.mei.zhgy.vo.CurrentWeatherResponse(42, "测试农场", "READY",
                "23", "多云", "62", null, null, null, null, "OPEN_METEO", "PROVIDER", "CURRENT",
                null, null, null, null, null, null, List.of(
                new WeatherForecastDay("2026-12-05", "date-matched-day", "20", "12", 0.0, 0, null),
                new WeatherForecastDay("2026-12-06", "out-of-query-day", "20", "12", 0.0, 0, null))));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            retrievalCountsAtProvider.add(queries.size());
            if (requests.size() == 1) {
                var tools = List.of(
                        call(0, "field", "read_field_context", "{\"fieldId\":\"east-field\"}"),
                        call(1, "denied", "read_field_context", "{\"farmId\":99,\"fieldId\":\"forbidden-field\"}"),
                        call(2, "knowledge_a", "read_agricultural_knowledge", "{\"query\":\"alpha\",\"fieldId\":\"east-field\",\"topK\":2}"),
                        call(3, "knowledge_b", "read_agricultural_knowledge", "{\"query\":\"beta\",\"fieldId\":\"west-field\",\"topK\":3}"),
                        call(4, "forecast", "read_current_weather", "{\"date\":\"2026-12-05\"}"));
                sendCompletion(exchange, mapper.writeValueAsString(Map.of("tool_calls", tools)), "tool_calls");
            } else if (requests.size() == 2) {
                var followUp = call(0, "knowledge_refine", "read_agricultural_knowledge",
                        "{\"query\":\"alpha-refined\",\"fieldId\":\"east-field\",\"topK\":1}");
                sendCompletion(exchange, mapper.writeValueAsString(Map.of("tool_calls", List.of(followUp))), "tool_calls");
            } else sendCompletion(exchange, "{\"content\":\"已查询指定日期和地块的资料。\"}", "stop");
        });
        server.start();
        PiAgentRuntime runtime = new PiAgentRuntime();
        ReflectionTestUtils.setField(runtime, "apiKey", "test-key");
        ReflectionTestUtils.setField(runtime, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        ReflectionTestUtils.setField(runtime, "model", "mock");
        try {
            CopilotRouteService routes = mock(CopilotRouteService.class);
            when(routes.route(any(), eq(1L), anyBoolean(), eq(false))).thenReturn(CopilotRouteResult.builder()
                    .intent(CopilotIntent.GENERAL_QA).resolutionStatus(CopilotResolutionStatus.RESOLVED)
                    .resolvedContext(CopilotStableContext.builder().farmId("42").fieldId("west-field").build()).build());
            CopilotController controller = new CopilotController(new CopilotApplicationService(routes, null, null, null, knowledge, null, fields, null, null, null, null));
            controller.application.setPiRuntime(runtime);
            controller.application.setCurrentWeatherService(weather);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
            BaseContext.setCurrentId(1L);
            MvcResult pending = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"请结合农场天气预报和知识证据给出建议\"}")).andExpect(request().asyncStarted()).andReturn();
            String output = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(output.contains("已查询指定日期和地块的资料。"), output);
            assertTrue(output.contains("WeatherCard"), output);
            assertTrue(output.contains("event:knowledge")
                    && (output.contains("\"count\":3") || output.contains("<count>3</count>")), output);
            assertEquals(List.of(0, 2, 3), retrievalCountsAtProvider);
            assertEquals(java.util.Set.of("alpha:east-field:2", "beta:west-field:3", "alpha-refined:east-field:1"), java.util.Set.copyOf(queries));
            assertEquals(3, requests.size());
            assertFalse(requests.get(0).contains("authorized-result"));
            assertFalse(requests.get(0).contains("east-field-live-name"));
            Map<String, String> toolResults = new java.util.HashMap<>();
            for (var message : mapper.readTree(requests.get(2)).path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    toolResults.put(message.path("tool_call_id").asText(), message.path("content").asText());
                } else {
                    assertFalse(message.toString().contains("authorized-result"), message.toString());
                }
            }
            assertTrue(toolResults.get("field").contains("east-field-live-name"));
            assertTrue(toolResults.get("denied").contains("SCOPE_UNAVAILABLE"));
            assertTrue(toolResults.get("knowledge_a").contains("authorized-result-alpha"));
            assertTrue(toolResults.get("knowledge_b").contains("authorized-result-beta"));
            assertTrue(toolResults.get("knowledge_refine").contains("authorized-result-alpha-refined"));
            assertTrue(toolResults.get("forecast").contains("date-matched-day"));
            assertFalse(toolResults.get("forecast").contains("out-of-query-day"));
            verify(fields, never()).get(eq("forbidden-field"), anyLong());
            verify(weather).get(42, 1L);
        } finally {
            BaseContext.removeCurrentId();
            runtime.close();
            server.stop(0);
        }
    }

    private Map<String, Object> call(int index, String id, String name, String args) {
        return Map.of("index", index, "id", id, "type", "function", "function", Map.of("name", name, "arguments", args));
    }

    @Test
    @EnabledIfSystemProperty(named = "farmap.pi.integration", matches = "true")
    void compoundHarnessTurnSchedulesAuthorizedTasksAndCameraInspectionInOnePiRun() throws Exception {
        var mapper = new ObjectMapper();
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (requests.size() == 1) {
                var calls = List.of(call(0, "tasks", "query_operations_tasks", "{\"farmId\":\"42\"}"),
                        call(1, "scan", "inspect_camera_maturity", "{\"scope\":\"FARM\",\"kind\":\"MATURITY\"}"));
                sendCompletion(exchange, mapper.writeValueAsString(Map.of("tool_calls", calls)), "tool_calls");
            } else sendCompletion(exchange, "{\"content\":\"已检查区域观测和任务。\"}", "stop");
        });
        server.start();
        var runtime = new PiAgentRuntime();
        ReflectionTestUtils.setField(runtime, "apiKey", "test-key");
        ReflectionTestUtils.setField(runtime, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        ReflectionTestUtils.setField(runtime, "model", "mock");
        try {
            var routes = mock(CopilotRouteService.class);
            when(routes.routeHarness(any(), eq(1L), eq(false))).thenReturn(CopilotRouteResult.builder()
                    .intent(CopilotIntent.GENERAL_QA).traceId("compound-harness")
                    .resolvedContext(CopilotStableContext.builder().farmId("42").build())
                    .resolutionStatus(CopilotResolutionStatus.RESOLVED).build());
            var fields = mock(FarmFieldService.class);
            var views = mock(com.mei.zhgy.service.operations.OperationsTaskViewService.class);
            when(views.view(42, null, null, null, "tasks", null, 1L)).thenReturn(Map.of("items", List.of(),
                    "counts", Map.of("total", 0), "truncated", false));
            var maturity = mock(com.mei.zhgy.service.maturity.FarmMaturityService.class);
            when(maturity.analyze(eq(42), eq(1L), eq(List.of()), isNull(), eq(List.of()), eq("MATURITY"), eq("FARM"), any()))
                    .thenAnswer(call -> {
                        java.util.function.Consumer<com.mei.zhgy.service.maturity.FarmMaturityService.ScanProgress> progress = call.getArgument(7);
                        progress.accept(new com.mei.zhgy.service.maturity.FarmMaturityService.ScanProgress(1, 1, 1, 1));
                        return Map.of("status", "COMPLETED", "runId", "maturity-verified", "summary", "已查看一个区域");
                    });
            var crops = new com.mei.zhgy.service.agriculture.CropResolver();
            var registry = new AgriculturalToolRegistry(fields, views, null, null, null, maturity, crops,
                    new com.mei.zhgy.service.agriculture.PhenologyService(crops));
            var controller = new CopilotController(new CopilotApplicationService(routes, null, null, null, null, null, fields, null, null, crops, null));
            controller.application.setPiRuntime(runtime);
            ReflectionTestUtils.setField(controller.application, "agriculturalTools", registry);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
            BaseContext.setCurrentId(1L);
            var pending = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"看看农场成熟度和本周任务，再给采收建议\"}")).andExpect(request().asyncStarted()).andReturn();
            String output = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(output.contains("OperationsQueryCard"), output);
            assertTrue(output.contains("FarmInspectionProgress"), output);
            assertTrue(output.contains("ui.block.updated"), output);
            assertTrue(output.contains("FarmMaturityCard"), output);
            assertTrue(output.contains("已检查区域观测和任务。"), output);
            assertFalse(output.contains("event:diagnosis.job"), output);
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).contains("maturity-verified"));
            verify(views).view(42, null, null, null, "tasks", null, 1L);
        } finally {
            BaseContext.removeCurrentId();
            runtime.close();
            server.stop(0);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "farmap.pi.integration", matches = "true")
    void toolTimeoutOnlyCancelsTheSlowCall() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        var mapper = new ObjectMapper();
        var interrupted = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (requests.size() == 1) {
                var calls = List.of(call(0, "delayed-call", "delayed", "{}"), call(1, "available-call", "available", "{}"));
                sendCompletion(exchange, mapper.writeValueAsString(Map.of("tool_calls", calls)), "tool_calls");
            } else sendCompletion(exchange, "{\"content\":\"已使用可用资料\"}", "stop");
        });
        server.start();
        var runtime = new PiAgentRuntime();
        ReflectionTestUtils.setField(runtime, "apiKey", "test-key");
        ReflectionTestUtils.setField(runtime, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        ReflectionTestUtils.setField(runtime, "model", "mock");
        ReflectionTestUtils.setField(runtime, "queryTimeoutMs", 300L);
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of(), "additionalProperties", false);
        var delayed = new PiAgentRuntime.ReadTool("delayed", "等待数据", schema, ignored -> {
            try { TimeUnit.SECONDS.sleep(10); }
            catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return PiAgentRuntime.ToolReply.result(Map.of("status", "LATE_VALUE"));
        });
        var available = new PiAgentRuntime.ReadTool("available", "获取数据", schema,
                ignored -> PiAgentRuntime.ToolReply.result(Map.of("status", "SIBLING_READY")));
        try {
            var result = runtime.run("test-timeout", "查询", "test runtime", List.of(), List.of(delayed, available), null, null);
            assertEquals("completed", result.status());
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).contains("TOOL_TIMEOUT"), requests.get(1));
            assertTrue(requests.get(1).contains("SIBLING_READY"), requests.get(1));
            assertFalse(requests.get(1).contains("LATE_VALUE"));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        } finally {
            runtime.close();
            server.stop(0);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "farmap.pi.integration", matches = "true")
    void overflowIsScopedToOneCommandOnASharedWorker(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Path worker = temp.resolve("overflow-fixture.mjs");
        java.nio.file.Files.writeString(worker, """
                import readline from 'node:readline';
                const input = readline.createInterface({input: process.stdin});
                input.on('line', raw => {
                  const frame = JSON.parse(raw);
                  if (frame.op !== 'start') return;
                  const write = (value) => process.stdout.write(JSON.stringify({id: frame.id, ...value}) + '\\n');
                  if (frame.session === 'congested') {
                    for (let i = 0; i < 4096; i++) write({type: 'delta', text: 'x'});
                  }
                  write({type: 'done', status: 'completed', text: process.pid.toString()});
                });
                """);
        var bridge = new PiAgentRuntime();
        ReflectionTestUtils.setField(bridge, "worker", worker.toString());
        ReflectionTestUtils.setField(bridge, "timeoutMs", 10000L);
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String existingWorker = bridge.run("first", "start", "", List.of(), List.of(), null, null).text();
            var congested = executor.submit(() -> bridge.run("congested", "start", "", List.of(), List.of(), text -> {
                callbackEntered.countDown();
                try { releaseCallback.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, null));
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            assertEquals(existingWorker, bridge.run("other-session", "start", "", List.of(), List.of(), null, null).text());
            releaseCallback.countDown();
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> congested.get(5, TimeUnit.SECONDS));
            assertEquals("PI_STREAM_QUEUE_OVERFLOW", failed.getCause().getMessage());
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
            bridge.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "farmap.pi.integration", matches = "true")
    void explicitActionHandsPiDraftToApprovalInsteadOfRunningTheLegacyPlanner() throws Exception {
        var mapper = new ObjectMapper();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        String date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(2).toString();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (count.incrementAndGet() == 1) {
                String args = mapper.writeValueAsString(Map.of("title", "地块灌溉复核", "detail", "先检查土壤湿度再灌溉。", "scheduledFor", date));
                sendCompletion(exchange, mapper.writeValueAsString(Map.of("tool_calls", List.of(call(0, "draft", "propose_task_draft", args)))), "tool_calls");
            } else sendCompletion(exchange, "{\"content\":\"草稿参数已准备。\"}", "stop");
        });
        server.start();
        PiAgentRuntime runtime = new PiAgentRuntime();
        ReflectionTestUtils.setField(runtime, "apiKey", "test-key");
        ReflectionTestUtils.setField(runtime, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        ReflectionTestUtils.setField(runtime, "model", "mock");
        try {
            CopilotRouteService routes = mock(CopilotRouteService.class);
            when(routes.route(any(), eq(1L), anyBoolean(), eq(false))).thenReturn(CopilotRouteResult.builder()
                    .intent(CopilotIntent.ACTION_REQUEST).resolutionStatus(CopilotResolutionStatus.RESOLVED)
                    .requestedAction("创建浇水任务").resolvedContext(CopilotStableContext.builder().farmId("42").fieldId("west-field").build()).build());
            AgentRuntime workflows = mock(AgentRuntime.class);
            var run = AgentRun.builder().runId("pi-draft-run").status(RunStatus.WAITING_FOR_APPROVAL)
                    .actions(List.of(ProposedAction.builder().id("draft-action").title("地块灌溉复核").build())).build();
            var draft = new AgentRuntime.TaskDraft("地块灌溉复核", "先检查土壤湿度再灌溉。", date);
            when(workflows.prepareTaskDraft(eq("创建浇水任务"), any(), eq(1L), eq(draft))).thenReturn(run);
            when(workflows.require("pi-draft-run", 1L)).thenReturn(run);
            CopilotController controller = new CopilotController(new CopilotApplicationService(routes, null, null, null, null, null, null, workflows, null, null, null));
            controller.application.setPiRuntime(runtime);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
            BaseContext.setCurrentId(1L);
            MvcResult pending = mvc.perform(post("/api/copilot/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"创建浇水任务\"}")).andExpect(request().asyncStarted()).andReturn();
            String output = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(output.contains("ui.confirmation.required"), output);
            assertTrue(output.contains("地块灌溉复核"), output);
            verify(workflows).prepareTaskDraft(eq("创建浇水任务"), argThat(context ->
                    "42".equals(context.getFarmId()) && "west-field".equals(context.getFieldId())), eq(1L), eq(draft));
            verify(workflows, never()).createRun(anyString(), any(), anyString(), anyLong());
            verify(workflows, never()).start(anyString());
            assertEquals(2, count.get());
        } finally {
            BaseContext.removeCurrentId();
            runtime.close();
            server.stop(0);
        }
    }

    private void sendCompletion(HttpExchange exchange, String delta, String finish) throws java.io.IOException {
        String body = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\ndata: [DONE]\n\n";
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }

    @Test
    void otherOwnerCannotControlConversation() {
        CopilotConversationRepository repository = mock(CopilotConversationRepository.class);
        when(repository.find("owned", 2L)).thenReturn(Optional.empty());
        CopilotConversationService conversations = new CopilotConversationService(repository);
        CopilotController controller = new CopilotController(new CopilotApplicationService(mock(CopilotRouteService.class), conversations));
        PiAgentRuntime runtime = mock(PiAgentRuntime.class);
        controller.application.setPiRuntime(runtime);
        BaseContext.setCurrentId(2L);
        try {
            CopilotConversationException error = assertThrows(CopilotConversationException.class,
                    () -> controller.controlAgent("owned", new CopilotApplicationService.PiControl("abort", 0L, null)));
            assertEquals("CONVERSATION_NOT_FOUND", error.getCode());
            verify(repository).find("owned", 2L);
            verifyNoInteractions(runtime);
        } finally { BaseContext.removeCurrentId(); }
    }
}


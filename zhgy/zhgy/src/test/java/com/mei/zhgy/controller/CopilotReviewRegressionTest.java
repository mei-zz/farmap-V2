package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import com.mei.zhgy.service.copilot.AgriculturalToolRegistry;
import com.mei.zhgy.service.copilot.CopilotApplicationService;
import com.mei.zhgy.service.copilot.CopilotConversationService;
import com.mei.zhgy.service.copilot.CopilotConversationTurn;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotResolutionStatus;
import com.mei.zhgy.service.copilot.CopilotRouteResult;
import com.mei.zhgy.service.copilot.CopilotRouteService;
import com.mei.zhgy.service.copilot.CopilotStableContext;
import com.mei.zhgy.service.copilot.InMemoryCopilotConversationRepository;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.CurrentWeatherResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CopilotReviewRegressionTest {
    private final CopilotRouteService routes = mock(CopilotRouteService.class);
    private final PiAgentRuntime runtime = mock(PiAgentRuntime.class);

    @BeforeEach
    void authenticate() {
        BaseContext.setCurrentId(7L);
        when(runtime.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void endRequest() { BaseContext.removeCurrentId(); }

    @Test
    void readyWeatherToolShowsACardWithoutWeatherKeywordsOrMissingModelStatus() throws Exception {
        configureRoute(route().build());
        stubPi((observed) -> observed.accept(currentWeatherTool()), new PiAgentRuntime.Result("风力较弱。", "completed"));

        String response = send(mvc(null), post("/api/copilot/chat").content("{\"message\":\"明天风大不大，适合干活吗？\"}"));
        assertTrue(response.contains("\"componentType\":\"WeatherCard\""), response);
        assertFalse(response.contains("MODEL_UNAVAILABLE"), response);
        assertTrue(response.contains("风力较弱。"), response);
    }

    @Test
    void plainWeatherCardDoesNotDuplicateAnAlreadyPresentedSprayWindow() throws Exception {
        configureRoute(route().build());
        stubPi(observed -> {
            observed.accept(new PiAgentRuntime.ToolObservation("read_current_weather",
                    new PiAgentRuntime.ToolReply(Map.of("status", "READY"), Map.of(
                            "componentType", "SprayWeatherWindowCard", "title", "未来农事作业天气",
                            "data", Map.of("operationWindows", List.of())), false)));
            observed.accept(currentWeatherTool());
        }, new PiAgentRuntime.Result("已查作业窗口。", "completed"));

        String response = send(mvc(null), post("/api/copilot/chat").content("{\"message\":\"明天适合打药吗？\"}"));
        assertTrue(response.contains("\"componentType\":\"SprayWeatherWindowCard\""), response);
        assertFalse(response.contains("\"componentType\":\"WeatherCard\""), response);
    }

    @Test
    void continueRetainsTheSameTurnsVerifiedEvidenceWhenNoToolsRunAgain() throws Exception {
        var repository = new InMemoryCopilotConversationRepository();
        var conversations = new CopilotConversationService(repository);
        var snapshot = conversations.create(7L);
        String conversationId = snapshot.getConversationId();
        repository.appendTurn(CopilotConversationTurn.builder().turnId("turn-weather").conversationId(conversationId)
                .userId(7L).role("USER").intent(CopilotIntent.GENERAL_QA).traceId("trace-weather")
                .message("明天风大不大？").createdAt(Instant.now()).build());
        configureRoute(route().conversationId(conversationId).contextVersion(snapshot.getVersion()).turnId("turn-weather")
                .traceId("trace-weather").build());
        stubPi(observed -> observed.accept(currentWeatherTool()), new PiAgentRuntime.Result("", "aborted"));
        when(runtime.control(eq("7:" + conversationId), eq(snapshot.getVersion()), eq("continue"), isNull(), any(), any(), any()))
                .thenReturn(new PiAgentRuntime.Result("根据刚才取得的数据，风力较弱。", "completed"));
        MockMvc mvc = mvc(conversations);

        String paused = send(mvc, post("/api/copilot/chat").content("{\"message\":\"明天风大不大？\"}"));
        assertTrue(paused.contains("ABORTED"), paused);
        String resumed = send(mvc, post("/api/copilot/conversations/" + conversationId + "/agent-control")
                .content("{\"operation\":\"continue\",\"contextVersion\":" + snapshot.getVersion() + "}"));
        assertTrue(resumed.contains("根据刚才取得的数据，风力较弱。"), resumed);
        assertTrue(resumed.contains("L1_FIELD_PRIVATE"), resumed);
        assertFalse(resumed.contains("当前没有取得可验证"), resumed);
        assertEquals("根据刚才取得的数据，风力较弱。", repository.allTurns(conversationId, 7L).get(1).getMessage());
    }

    @Test
    void nativeHarnessClarifiesAnUnknownFieldCropInsteadOfApplyingTheQuestionsCalendar() throws Exception {
        var fields = mock(FarmFieldService.class);
        when(fields.get("field-demo", 7L)).thenReturn(FarmField.builder()
                .fieldId("field-demo").farmId(42).crop("目录外作物").build());
        when(routes.routeHarness(any(), eq(7L), eq(false))).thenReturn(route()
                .resolvedContext(CopilotStableContext.builder().farmId("42").fieldId("field-demo").build()).build());
        var application = new CopilotApplicationService(routes, null, null, null, null, null, fields,
                null, null, null, null);
        application.setPiRuntime(runtime);
        ReflectionTestUtils.setField(application, "agriculturalTools", mock(AgriculturalToolRegistry.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CopilotController(application)).build();

        String response = send(mvc, post("/api/copilot/chat").content("{\"message\":\"当前这个地块的脆李是什么物候期？\"}"));
        assertTrue(response.contains("当前地块登记的作物暂不在物候目录中"), response);
        assertFalse(response.contains("通用物候历推算"), response);
        verify(runtime, atLeastOnce()).isEnabled();
        verifyNoMoreInteractions(runtime);
    }

    private CopilotRouteResult.CopilotRouteResultBuilder route() {
        return CopilotRouteResult.builder().intent(CopilotIntent.GENERAL_QA)
                .resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .resolvedContext(CopilotStableContext.builder().farmId("42").build());
    }

    private void configureRoute(CopilotRouteResult route) {
        when(routes.route(any(), eq(7L), anyBoolean(), eq(false))).thenReturn(route);
    }

    private MockMvc mvc(CopilotConversationService conversations) {
        var application = new CopilotApplicationService(routes, conversations);
        application.setPiRuntime(runtime);
        ReflectionTestUtils.setField(application, "allowUngrounded", false);
        return MockMvcBuilders.standaloneSetup(new CopilotController(application)).build();
    }

    private void stubPi(Consumer<Consumer<PiAgentRuntime.ToolObservation>> fixture, PiAgentRuntime.Result result) {
        when(runtime.run(anyString(), nullable(Long.class), anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), anyList(), anyList(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    fixture.accept(invocation.getArgument(12));
                    return result;
                });
    }

    private PiAgentRuntime.ToolObservation currentWeatherTool() {
        var weather = new CurrentWeatherResponse(42, "示例农场", "READY", "15", "晴", "66", "北", "2级", "2026-11-27",
                null, "OPEN_METEO", "PROVIDER", "CURRENT", null, null, null, null, null, null, List.of());
        return new PiAgentRuntime.ToolObservation("read_current_weather", new PiAgentRuntime.ToolReply(
                Map.of("status", "READY", "weather", weather, "farmId", 42), Map.of("weather", weather), false));
    }

    private String send(MockMvc mvc, MockHttpServletRequestBuilder command) throws Exception {
        var stream = mvc.perform(command.contentType("application/json")).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}

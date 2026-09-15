package com.mei.zhgy.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentEvent;
import com.mei.zhgy.service.agent.AgentRun;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.agent.AgentEventBroadcaster;
import com.mei.zhgy.service.agent.AgentAccessException;
import com.mei.zhgy.service.agent.ApprovalException;
import com.mei.zhgy.service.agent.ProductLoopRelationException;
import com.mei.zhgy.service.agent.RunStatus;
import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentRuntime runtime;
    private final FarmFieldService farmFieldService;
    private final DiagnosisPersistenceService diagnosisPersistence;
    private final AgentEventBroadcaster eventBroadcaster;
    private final FieldFactsService fieldFactsService;
    public AgentController(AgentRuntime runtime) { this(runtime, null, null, null, null); }
    public AgentController(AgentRuntime runtime, FarmFieldService farmFieldService) { this(runtime, farmFieldService, null, null, null); }
    public AgentController(AgentRuntime runtime, FarmFieldService farmFieldService, DiagnosisPersistenceService diagnosisPersistence) { this(runtime, farmFieldService, diagnosisPersistence, null, null); }
    @Autowired
    public AgentController(AgentRuntime runtime, FarmFieldService farmFieldService, DiagnosisPersistenceService diagnosisPersistence, AgentEventBroadcaster eventBroadcaster,
                           @Autowired(required = false) FieldFactsService fieldFactsService) {
        this.runtime = runtime; this.farmFieldService = farmFieldService; this.diagnosisPersistence = diagnosisPersistence; this.eventBroadcaster = eventBroadcaster; this.fieldFactsService = fieldFactsService;
    }

    @PostMapping("/runs")
    public Map<String, Object> create(@RequestBody AgentRunRequest request) {
        Long userId = currentUser();
        if (request == null) throw ProductLoopRelationException.validation("Agent request 不能为空", null, null);
        AgentContext context = request.getContext();
        String diagnosisId = request.getDiagnosisId() != null && !request.getDiagnosisId().isBlank() ? request.getDiagnosisId() : context == null ? null : context.getDiagnosisId();
        String recommendationId = request.getRecommendationId() != null && !request.getRecommendationId().isBlank() ? request.getRecommendationId() : context == null ? null : context.getSourceRecommendationId();
        if (request.getDiagnosisId() != null && context != null && context.getDiagnosisId() != null && !request.getDiagnosisId().equals(context.getDiagnosisId()))
            throw ProductLoopRelationException.conflict("Agent request 与客户端上下文的 diagnosisId 不一致", request.getDiagnosisId(), recommendationId);
        if (request.getRecommendationId() != null && context != null && context.getSourceRecommendationId() != null && !request.getRecommendationId().equals(context.getSourceRecommendationId()))
            throw ProductLoopRelationException.conflict("Agent request 与客户端上下文的 recommendationId 不一致", diagnosisId, request.getRecommendationId());
        boolean real = !"mock".equalsIgnoreCase(request.getMode());
        if (real && (diagnosisId == null || diagnosisId.isBlank()))
            throw ProductLoopRelationException.validation("REAL Agent 必须通过 diagnosisId 进入已持久化 Diagnosis", null, recommendationId);
        if (real && diagnosisPersistence != null && diagnosisId != null && !diagnosisId.isBlank()) {
            context = diagnosisPersistence.buildAgentContext(diagnosisId, recommendationId, userId, context);
            if (context != null && context.getFieldId() != null && !context.getFieldId().isBlank()) {
                if (fieldFactsService != null) {
                    FieldFacts facts = fieldFactsService.forField(context.getFieldId(), userId);
                    if (facts.unknownCrop()) throw ProductLoopRelationException.validation("当前地块的作物暂不在支持目录中，无法启动 Agent", diagnosisId, recommendationId);
                    if ("UNAVAILABLE".equals(facts.status())) throw ProductLoopRelationException.validation("当前地块事实暂不可用，无法启动 Agent", diagnosisId, recommendationId);
                    if (facts.ready()) context.applyFieldFacts(facts);
                } else if (farmFieldService != null) {
                    FieldContext trusted = farmFieldService.context(context.getFieldId(), userId);
                    applyTrustedFieldContext(context, trusted, null);
                }
            }
        }
        String goal = request.getIntent() != null && !request.getIntent().isBlank() ? request.getIntent() : request.getGoal();
        AgentRun run = runtime.createRun(goal, context, request.getMode(), userId);
        runtime.start(run.getRunId());
        return new LinkedHashMap<>(Map.of("runId", run.getRunId(), "status", "planning"));
    }

    @GetMapping("/runs/{runId}")
    public AgentRun get(@PathVariable String runId) { return runtime.require(runId, currentUser()); }

    @PostMapping("/runs/{runId}/execute")
    public AgentRun execute(@PathVariable String runId) { return runtime.execute(runId, currentUser()); }

    @PostMapping("/runs/{runId}/actions/{actionId}/approve")
    public AgentRun approve(@PathVariable String runId, @PathVariable String actionId) { return runtime.approve(runId, actionId, currentUser()); }

    @PostMapping("/runs/{runId}/actions/{actionId}/reject")
    public AgentRun reject(@PathVariable String runId, @PathVariable String actionId) { return runtime.reject(runId, actionId, currentUser()); }

    @ExceptionHandler(AgentAccessException.class)
    public ResponseEntity<Map<String, Object>> accessError(AgentAccessException error) {
        return ResponseEntity.status(error.getHttpStatus()).body(new LinkedHashMap<>(Map.of("code", error.getCode(), "message", error.getMessage(), "timestamp", java.time.Instant.now().toString())));
    }

    @ExceptionHandler(ApprovalException.class)
    public ResponseEntity<Map<String, Object>> approvalError(ApprovalException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode()); body.put("message", error.getMessage());
        body.put("runId", error.getRunId()); body.put("actionId", error.getActionId());
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.status(HttpStatus.valueOf(error.getHttpStatus())).body(body);
    }

    private void applyTrustedFieldContext(AgentContext target, FieldContext trusted, FieldFacts facts) {
        if (facts != null) { target.applyFieldFacts(facts); return; }
        Map<String, Object> field = trusted.getField(); Map<String, Object> farm = trusted.getFarm(); Map<String, Object> crop = trusted.getCrop(); Map<String, Object> location = trusted.getLocation();
        target.setFarmId(String.valueOf(farm.get("id"))); target.setFarmName(String.valueOf(farm.get("name"))); target.setFieldId(String.valueOf(field.get("fieldId"))); target.setFieldName(String.valueOf(field.get("name")));
        if (crop.get("name") != null) target.setCrop(facts == null || facts.cropCode() == null ? String.valueOf(crop.get("name")) : facts.cropCode());
        if (facts != null && facts.currentGrowthStage() != null) target.setPhenologyStage(facts.currentGrowthStage());
        else if (field.get("growthStage") != null) target.setPhenologyStage(String.valueOf(field.get("growthStage")));
        if (location.get("latitude") instanceof Number) target.setLatitude(((Number) location.get("latitude")).doubleValue()); if (location.get("longitude") instanceof Number) target.setLongitude(((Number) location.get("longitude")).doubleValue());
        target.setLocation(String.valueOf(location.getOrDefault("scope", "FIELD"))); target.setWeather(new java.util.LinkedHashMap<>(trusted.getWeather())); target.setSourceModes(new java.util.LinkedHashMap<>(Map.of("field", trusted.getSourceMode(), "weather", trusted.getWeather().getOrDefault("sourceMode", "UNAVAILABLE"), "gis", trusted.getGis().getOrDefault("sourceMode", "UNAVAILABLE"), "camera", "UNAVAILABLE", "knowledge", "UNAVAILABLE", "historical", "UNAVAILABLE")));
    }

    @ExceptionHandler(ProductLoopRelationException.class)
    public ResponseEntity<Map<String, Object>> relationError(ProductLoopRelationException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode()); body.put("message", error.getMessage());
        body.put("diagnosisId", error.getDiagnosisId()); body.put("recommendationId", error.getRecommendationId());
        body.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @PostMapping("/runs/{runId}/cancel")
    public AgentRun cancel(@PathVariable String runId) { return runtime.cancel(runId, currentUser()); }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId) throws IOException {
        AgentRun run = runtime.require(runId, currentUser());
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        if (eventBroadcaster == null) {
            for (AgentEvent event : run.getTraces()) emitter.send(SseEmitter.event().id(String.valueOf(event.getSequence())).name(event.getType()).data(event));
            if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED
                    || run.getStatus() == RunStatus.WAITING_FOR_APPROVAL || run.getStatus() == RunStatus.CANCELLED) {
                emitter.send(SseEmitter.event().name("done").data(Map.of("runId", runId, "status", run.getStatus().name())));
                emitter.complete();
            }
            return emitter;
        }
        eventBroadcaster.subscribe(runId, emitter);
        eventBroadcaster.replay(runId, emitter, run.getTraces());
        eventBroadcaster.completeIfTerminal(runId, emitter, run.getStatus());
        return emitter;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentRunRequest {
        private String goal;
        private AgentContext context;
        private String mode;
        /** Canonical REAL input. The server reloads all business content by these IDs. */
        private String diagnosisId;
        private String recommendationId;
        private String intent;
    }

    private Long currentUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) throw AgentAccessException.unauthenticated();
        return userId;
    }
}

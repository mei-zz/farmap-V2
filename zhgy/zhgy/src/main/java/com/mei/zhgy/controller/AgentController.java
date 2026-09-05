package com.mei.zhgy.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentEvent;
import com.mei.zhgy.service.agent.AgentRun;
import com.mei.zhgy.service.agent.AgentRuntime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.MediaType;
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
    public AgentController(AgentRuntime runtime) { this.runtime = runtime; }

    @PostMapping("/runs")
    public Map<String, Object> create(@RequestBody AgentRunRequest request) {
        AgentRun run = runtime.createRun(request.getGoal(), request.getContext(), request.getMode());
        runtime.start(run.getRunId());
        return new LinkedHashMap<>(Map.of("runId", run.getRunId(), "status", "planning"));
    }

    @GetMapping("/runs/{runId}")
    public AgentRun get(@PathVariable String runId) { return runtime.require(runId); }

    @PostMapping("/runs/{runId}/execute")
    public AgentRun execute(@PathVariable String runId) { return runtime.execute(runId); }

    @PostMapping("/runs/{runId}/actions/{actionId}/approve")
    public AgentRun approve(@PathVariable String runId, @PathVariable String actionId) { return runtime.approve(runId, actionId); }

    @PostMapping("/runs/{runId}/actions/{actionId}/reject")
    public AgentRun reject(@PathVariable String runId, @PathVariable String actionId) { return runtime.reject(runId, actionId); }

    @PostMapping("/runs/{runId}/cancel")
    public AgentRun cancel(@PathVariable String runId) { return runtime.cancel(runId); }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId) throws IOException {
        AgentRun run = runtime.require(runId);
        SseEmitter emitter = new SseEmitter(30_000L);
        for (AgentEvent event : run.getTraces()) emitter.send(SseEmitter.event().id(String.valueOf(event.getSequence())).name(event.getType()).data(event));
        emitter.complete();
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
    }
}

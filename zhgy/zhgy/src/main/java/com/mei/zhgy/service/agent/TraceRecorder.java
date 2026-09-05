package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TraceRecorder {
    public AgentEvent record(AgentRun run, String type, Map<String, Object> payload) {
        AgentEvent event = AgentEvent.builder().sequence(run.getTraces().size() + 1L)
                .timestamp(Instant.now()).type(type)
                .payload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload)).build();
        run.getTraces().add(event);
        run.setUpdatedAt(event.getTimestamp());
        return event;
    }
}

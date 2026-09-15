package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class TraceRecorder {
    private final AgentEventBroadcaster broadcaster;

    /** Compatibility constructor for deterministic unit tests. */
    public TraceRecorder() { this(null); }

    @Autowired
    public TraceRecorder(AgentEventBroadcaster broadcaster) { this.broadcaster = broadcaster; }

    public AgentEvent record(AgentRun run, String type, Map<String, Object> payload) {
        AgentEvent event = AgentEvent.builder().sequence(run.getTraces().size() + 1L)
                .timestamp(Instant.now()).type(type)
                .payload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload)).build();
        run.getTraces().add(event);
        run.setUpdatedAt(event.getTimestamp());
        if (broadcaster != null) broadcaster.publish(run.getRunId(), event);
        return event;
    }

    /** Completes live subscribers only after the run repository has saved its terminal state. */
    public void complete(AgentRun run) {
        if (broadcaster != null && run != null) broadcaster.complete(run.getRunId(), run.getStatus());
    }
}

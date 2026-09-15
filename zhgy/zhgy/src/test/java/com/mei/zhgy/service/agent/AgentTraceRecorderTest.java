package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class AgentTraceRecorderTest {
    @Test void recordsMonotonicSequenceWithoutReasoningText() {
        AgentRun run = AgentRun.builder().runId("r").traces(new java.util.ArrayList<>()).build();
        TraceRecorder recorder = new TraceRecorder();
        recorder.record(run, "run.created", new LinkedHashMap<>());
        recorder.record(run, "run.completed", new LinkedHashMap<>());
        assertEquals(2, run.getTraces().size());
        assertEquals(2, run.getTraces().get(1).getSequence());
    }
}

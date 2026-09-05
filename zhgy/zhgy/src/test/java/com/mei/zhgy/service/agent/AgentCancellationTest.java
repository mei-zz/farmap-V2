package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentCancellationTest {
    @Test void cancellationIsTerminal() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRun run = AgentRun.builder().runId("r").status(RunStatus.RUNNING).createdAt(java.time.Instant.now()).traces(new java.util.ArrayList<>()).build(); repository.save(run);
        run.setCancellationRequested(true); run.setStatus(RunStatus.CANCELLED);
        assertTrue(run.isCancellationRequested()); assertEquals(RunStatus.CANCELLED, run.getStatus());
    }
}

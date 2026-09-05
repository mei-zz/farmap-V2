package com.mei.zhgy.service.agent;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {
    AgentRun save(AgentRun run);
    Optional<AgentRun> find(String runId);
    List<AgentRun> findAll();
}

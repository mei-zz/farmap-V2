package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunStateMachineTest {
    @Test void exposesRequiredStates() {
        assertEquals(7, RunStatus.values().length); assertEquals(5, StepStatus.values().length); assertEquals(5, ToolStatus.values().length);
    }
}

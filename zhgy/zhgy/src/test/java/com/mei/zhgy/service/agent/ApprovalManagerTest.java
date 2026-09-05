package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalManagerTest {
    @Test void approvalIsExplicitAndReversibleToReject() {
        ProposedAction action = ProposedAction.builder().id("a1").approvalState(ApprovalState.REQUIRED).build();
        AgentRun run = AgentRun.builder().actions(new java.util.ArrayList<>(List.of(action))).build();
        ApprovalManager manager = new ApprovalManager();
        manager.approve(run, "a1"); assertEquals(ApprovalState.APPROVED, action.getApprovalState());
        manager.reject(run, "a1"); assertEquals(ApprovalState.REJECTED, action.getApprovalState());
    }
}

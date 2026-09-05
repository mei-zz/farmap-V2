package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Service;

@Service
public class ApprovalManager {
    public ProposedAction approve(AgentRun run, String actionId) {
        for (ProposedAction action : run.getActions()) {
            if (action.getId().equals(actionId)) {
                action.setApprovalState(ApprovalState.APPROVED);
                run.setApprovalState(ApprovalState.APPROVED);
                return action;
            }
        }
        throw new IllegalArgumentException("动作不存在: " + actionId);
    }

    public ProposedAction reject(AgentRun run, String actionId) {
        for (ProposedAction action : run.getActions()) {
            if (action.getId().equals(actionId)) {
                action.setApprovalState(ApprovalState.REJECTED);
                run.setApprovalState(ApprovalState.REJECTED);
                return action;
            }
        }
        throw new IllegalArgumentException("动作不存在: " + actionId);
    }
}

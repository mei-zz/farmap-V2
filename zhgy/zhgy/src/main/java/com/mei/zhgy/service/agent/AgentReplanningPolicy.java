package com.mei.zhgy.service.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A separate, deliberately small budget for plan adaptation. */
@Component
public class AgentReplanningPolicy {
    private final int maxReplans;

    public AgentReplanningPolicy() { this(1); }

    @Autowired
    public AgentReplanningPolicy(@Value("${farmap.agent.max-replans:1}") int maxReplans) {
        this.maxReplans = Math.max(0, Math.min(maxReplans, 1));
    }

    public int maxReplans() { return maxReplans; }
}

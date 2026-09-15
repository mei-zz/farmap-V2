package com.mei.zhgy.service.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Small, bounded policy. It intentionally has no open-ended replanning behavior. */
@Component
public class AgentRecoveryPolicy {
    private final int maxAttempts;
    private final long retryBaseDelayMs;
    private final int maxRecoveryAttempts;

    public AgentRecoveryPolicy() { this(2, 0, 1); }

    @Autowired
    public AgentRecoveryPolicy(
            @Value("${farmap.agent.tool.max-attempts:2}") int maxAttempts,
            @Value("${farmap.agent.tool.retry-base-delay-ms:25}") long retryBaseDelayMs,
            @Value("${farmap.agent.max-recovery-attempts:1}") int maxRecoveryAttempts) {
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 2));
        this.retryBaseDelayMs = Math.max(0, Math.min(retryBaseDelayMs, 1000));
        this.maxRecoveryAttempts = Math.max(0, Math.min(maxRecoveryAttempts, 1));
    }

    public int maxAttempts() { return maxAttempts; }
    public long retryBaseDelayMs() { return retryBaseDelayMs; }
    public int maxRecoveryAttempts() { return maxRecoveryAttempts; }
}

package com.mei.zhgy.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 研究记录先进入结构化日志；不保存 prompt、响应正文或私有思维链。 */
@Slf4j
@Component
public class ModelUsageRecorder {
    public void record(ModelUsageRecord record) {
        log.info("MODEL_USAGE requestId={} runId={} taskType={} requestedModel={} selectedModel={} fallbackChain={} modelRole={} routingReason={} escalated={} timestamp={} inputModalities={} latencyMs={} success={} errorType={} inputTokens={} outputTokens={} thinkingTokens={} totalTokens={}",
                record.getRequestId(), record.getRunId(), record.getTaskType(), record.getRequestedModel(),
                record.getSelectedModel(), record.getFallbackChain(), record.getModelRole(), record.getRoutingReason(),
                record.isEscalated(), record.getTimestamp(), record.getInputModalities(), record.getLatencyMs(),
                record.isSuccess(), record.getErrorType(), record.getInputTokens(), record.getOutputTokens(),
                record.getThinkingTokens(), record.getTotalTokens());
    }
}

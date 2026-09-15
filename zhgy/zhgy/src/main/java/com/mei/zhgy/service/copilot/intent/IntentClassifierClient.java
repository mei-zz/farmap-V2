package com.mei.zhgy.service.copilot.intent;

/** Transport boundary for model-backed intent classification. */
public interface IntentClassifierClient {
    RawIntentResponse classify(String model, String tier, IntentPromptBuilder.Prompt prompt, int maxTokens);

    record RawIntentResponse(String content, String model, String tier, long latencyMs) { }
}

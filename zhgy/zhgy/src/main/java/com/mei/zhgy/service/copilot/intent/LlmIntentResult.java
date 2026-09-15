package com.mei.zhgy.service.copilot.intent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mei.zhgy.service.copilot.CopilotIntent;

import java.util.List;

/** Bounded, observable result of one optional intent-model decision. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmIntentResult(CopilotIntent intent, double confidence, IntentSlots slots,
                              boolean needsClarification, List<String> multiIntent,
                              String model, String tier, long latencyMs,
                              String fallbackReason, boolean ok) { }

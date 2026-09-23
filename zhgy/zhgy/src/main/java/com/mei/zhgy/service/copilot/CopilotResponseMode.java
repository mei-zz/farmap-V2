package com.mei.zhgy.service.copilot;

/** Presentation policy for a Copilot answer. This is not an intent enum. */
public enum CopilotResponseMode {
    SIMPLE,
    KNOWLEDGE,
    GUIDED,
    AGENT,
    CLARIFICATION
}

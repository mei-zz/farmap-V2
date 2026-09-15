package com.mei.zhgy.service.agent;

import java.util.List;

public interface AgentTool {
    String name();
    String description();
    boolean readOnly();
    ToolResult execute(AgentToolRequest request);

    default ToolCapability capability() { return readOnly() ? ToolCapability.READ_ONLY : ToolCapability.WRITE_CAPABLE; }
    default boolean idempotent() { return readOnly(); }
    default String capabilityFamily() { return name(); }
    /** Server-registered safe read alternatives; never model-supplied. */
    default List<String> safeAlternativeTools() { return List.of(); }
}

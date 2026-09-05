package com.mei.zhgy.service.agent;

public interface AgentTool {
    String name();
    String description();
    boolean readOnly();
    ToolResult execute(AgentToolRequest request);
}

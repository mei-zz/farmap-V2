package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ToolExecutor {
    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) { this.registry = registry; }

    public ToolResult execute(String toolName, AgentToolRequest request) {
        AgentTool tool = registry.get(toolName);
        if (tool == null) return ToolResult.unavailable("TOOL_NOT_REGISTERED", "未注册工具: " + toolName);
        try { return tool.execute(request); }
        catch (Exception error) { return ToolResult.failed("TOOL_EXECUTION_FAILED", error.getMessage()); }
    }

    public Map<String, Object> descriptor() {
        Map<String, Object> output = new LinkedHashMap<>();
        registry.all().forEach((key, value) -> output.put(key, Map.of("description", value.description(), "readOnly", value.readOnly())));
        return output;
    }
}

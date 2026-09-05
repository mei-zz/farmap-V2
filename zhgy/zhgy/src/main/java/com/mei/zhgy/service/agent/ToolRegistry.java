package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> discovered) {
        if (discovered != null) discovered.forEach(tool -> tools.put(tool.name(), tool));
    }
    public AgentTool get(String name) { return tools.get(name); }
    public boolean contains(String name) { return tools.containsKey(name); }
    public Map<String, AgentTool> all() { return Collections.unmodifiableMap(tools); }
}

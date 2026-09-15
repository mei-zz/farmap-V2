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

    public List<String> safeAlternatives(String name) {
        AgentTool tool = get(name);
        if (tool == null || tool.safeAlternativeTools() == null) return List.of();
        return tool.safeAlternativeTools().stream().filter(tools::containsKey)
                .filter(candidate -> {
                    AgentTool alternative = tools.get(candidate);
                    return alternative != null && alternative.readOnly() && tool.readOnly()
                            && alternative.capabilityFamily().equals(tool.capabilityFamily());
                }).toList();
    }
}

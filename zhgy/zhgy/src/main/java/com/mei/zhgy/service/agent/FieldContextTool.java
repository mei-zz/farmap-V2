package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FieldContextTool implements AgentTool {
    @Override public String name() { return "field-context"; }
    @Override public String description() { return "读取结构化农场、地块、作物与物候上下文"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        AgentContext c = request.getContext();
        Map<String, Object> out = new LinkedHashMap<>();
        if (c != null) { out.put("farmId", c.getFarmId()); out.put("farmName", c.getFarmName()); out.put("fieldId", c.getFieldId()); out.put("fieldName", c.getFieldName()); out.put("crop", c.getCrop()); out.put("variety", c.getVariety()); out.put("phenologyStage", c.getPhenologyStage()); }
        return ToolResult.builder().output(out).metadata(Map.of("sourceMode", source(c, "field", "LOCAL"))).build();
    }
    private Object source(AgentContext c, String key, String fallback) { if (c == null || c.getSourceModes() == null) return fallback; Object value = c.getSourceModes().get(key); return value == null ? fallback : value; }
}

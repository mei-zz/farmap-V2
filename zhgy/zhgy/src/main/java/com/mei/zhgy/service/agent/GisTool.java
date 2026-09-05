package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GisTool implements AgentTool {
    @Override public String name() { return "gis"; }
    @Override public String description() { return "读取地块位置与空间来源模式"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        AgentContext c = request.getContext();
        Map<String, Object> out = new LinkedHashMap<>();
        if (c != null) { out.put("location", c.getLocation()); out.put("latitude", c.getLatitude()); out.put("longitude", c.getLongitude()); out.put("fieldId", c.getFieldId()); }
        Object source = "LOCAL"; if (c != null && c.getSourceModes() != null && c.getSourceModes().get("gis") != null) source = c.getSourceModes().get("gis");
        return ToolResult.builder().output(out).metadata(Map.of("sourceMode", source)).build();
    }
}

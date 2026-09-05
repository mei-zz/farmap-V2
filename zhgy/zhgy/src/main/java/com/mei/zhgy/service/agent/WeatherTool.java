package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WeatherTool implements AgentTool {
    @Override public String name() { return "weather"; }
    @Override public String description() { return "读取页面注入的天气与未来24小时风险上下文"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        Map<String, Object> weather = request.getContext() == null || request.getContext().getWeather() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getContext().getWeather());
        if (weather.isEmpty()) return ToolResult.unavailable("WEATHER_CONTEXT_EMPTY", "当前运行没有天气上下文");
        return ToolResult.builder().output(weather).metadata(Map.of("sourceMode", source(request.getContext()))).build();
    }
    private Object source(AgentContext c) { if (c == null || c.getSourceModes() == null) return "LOCAL"; Object value = c.getSourceModes().get("weather"); return value == null ? "LOCAL" : value; }
}

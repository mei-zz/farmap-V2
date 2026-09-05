package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CameraTool implements AgentTool {
    @Override public String name() { return "camera"; }
    @Override public String description() { return "读取用户已经选择的摄像头图像，不控制设备"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        AgentContext c = request.getContext();
        if (c == null || c.getImageUrls() == null || c.getImageUrls().isEmpty()) return ToolResult.unavailable("CAMERA_IMAGES_EMPTY", "当前运行没有图像输入");
        Map<String, Object> output = new LinkedHashMap<>(); output.put("imageUrls", c.getImageUrls()); output.put("cameraId", c.getCameraId()); output.put("count", c.imageCount());
        return ToolResult.builder().output(output).metadata(Map.of("sourceMode", source(c))).build();
    }
    private Object source(AgentContext c) { if (c == null || c.getSourceModes() == null) return "LOCAL"; Object value = c.getSourceModes().get("camera"); return value == null ? "LOCAL" : value; }
}

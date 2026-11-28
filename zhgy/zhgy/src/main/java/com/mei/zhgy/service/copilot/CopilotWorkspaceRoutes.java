package com.mei.zhgy.service.copilot;

import java.util.List;
import java.util.Map;

/** The only destinations available to natural-language workspace navigation. */
final class CopilotWorkspaceRoutes {
    static final List<String> PATHS = List.of("/operations", "/diagnosis", "/analysis", "/knowledge", "/farm/map", "/overview");
    private static final Map<String, String> TITLES = Map.of(
            "/operations", "农事任务", "/diagnosis", "农情诊断", "/analysis", "农场分析",
            "/knowledge", "农业知识", "/farm/map", "地块空间", "/overview", "农场总览");

    private CopilotWorkspaceRoutes() {}

    static String title(String path) { return path == null ? null : TITLES.get(path); }
}

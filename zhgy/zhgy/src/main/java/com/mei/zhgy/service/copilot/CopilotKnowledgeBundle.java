package com.mei.zhgy.service.copilot;

import java.util.List;
import java.util.Map;

record CopilotKnowledgeBundle(String status, List<Map<String, Object>> items) {
    static CopilotKnowledgeBundle notRequested() { return new CopilotKnowledgeBundle("NOT_REQUESTED", List.of()); }
}

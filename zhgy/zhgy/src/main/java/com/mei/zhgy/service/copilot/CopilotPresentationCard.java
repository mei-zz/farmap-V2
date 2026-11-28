package com.mei.zhgy.service.copilot;

import java.util.List;
import java.util.Map;

record CopilotPresentationCard(String type, String title, String summary, Map<String, Object> data,
                               String status, List<Map<String, Object>> sources, String key) {}

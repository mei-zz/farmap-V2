package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Write boundary. It only creates a task after ApprovalManager has approved the action. */
@Component
public class TaskTool implements AgentTool {
    private final Map<String, Map<String, Object>> createdTasks = new LinkedHashMap<>();
    @Override public String name() { return "task"; }
    @Override public String description() { return "创建 Operations 农事任务（需要人工审批）"; }
    @Override public boolean readOnly() { return false; }
    @Override public synchronized ToolResult execute(AgentToolRequest request) {
        if (!request.isApprovalGranted()) return ToolResult.unavailable("APPROVAL_REQUIRED", "写操作需要人工审批");
        String taskId = "task-" + UUID.randomUUID();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", taskId); task.put("title", "复核 " + (request.getContext() == null ? "地块" : request.getContext().getFieldId()) + " 农情");
        task.put("status", "OPEN"); task.put("source", "far-map-agent"); task.put("goal", request.getGoal());
        createdTasks.put(taskId, task);
        return ToolResult.builder().output(task).metadata(Map.of("write", true, "system", "operations")).build();
    }
    public synchronized int createdTaskCount() { return createdTasks.size(); }
}

package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import com.mei.zhgy.service.operations.OperationsTaskService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/** Write boundary. It only creates a task after ApprovalManager has approved the action. */
@Component
public class TaskTool implements AgentTool {
    private final OperationsTaskService operations;
    public TaskTool() { this.operations = new OperationsTaskService(); }
    @Autowired public TaskTool(OperationsTaskService operations) { this.operations = operations; }
    @Override public String name() { return "task"; }
    @Override public String description() { return "创建 Operations 农事任务（需要人工审批）"; }
    @Override public boolean readOnly() { return false; }
    @Override public synchronized ToolResult execute(AgentToolRequest request) {
        if (!request.isApprovalGranted()) return ToolResult.unavailable("APPROVAL_REQUIRED", "写操作需要人工审批");
        Map<String, Object> task = operations.createTask(request);
        return ToolResult.builder().output(task).metadata(Map.of("write", true, "system", "operations", "taskId", task.get("taskId"))).build();
    }
    public synchronized int createdTaskCount() { return operations.count(); }
}

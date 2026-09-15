package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.operations.OperationsWriteException;
import com.mei.zhgy.service.ai.IntelligenceGatewayException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ToolExecutor {
    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) { this.registry = registry; }

    public ToolResult execute(String toolName, AgentToolRequest request) {
        AgentTool tool = registry.get(toolName);
        if (tool == null) return ToolResult.unavailable("TOOL_NOT_REGISTERED", "未注册工具: " + toolName);
        try { return normalize(tool.execute(request)); }
        catch (OperationsWriteException error) { return ToolResult.failed(error.getCode(), error.getMessage()); }
        catch (IntelligenceGatewayException error) {
            return error.isRetryable() ? ToolResult.retryableFailure(error.getCode(), error.getMessage())
                    : ToolResult.failed(error.getCode(), error.getMessage());
        }
        catch (IllegalArgumentException error) { return ToolResult.failed("INVALID_REQUEST", error.getMessage()); }
        catch (IllegalStateException error) { return ToolResult.contractFailure("CONTRACT_FAILURE", error.getMessage()); }
        catch (RuntimeException error) {
            Throwable cause = error.getCause();
            String type = cause == null ? error.getClass().getSimpleName() : cause.getClass().getSimpleName();
            if (type.contains("Timeout") || type.contains("Connect") || type.contains("Socket") || type.contains("IOException"))
                return ToolResult.retryableFailure("TRANSIENT_IO_FAILURE", error.getMessage());
            return ToolResult.failed("TOOL_EXECUTION_FAILED", error.getMessage());
        }
    }

    /** A write-capable tool must be explicitly registered; unknown names never become actions. */
    public boolean supportsWrite(String toolName) {
        AgentTool tool = registry.get(toolName);
        return tool != null && !tool.readOnly();
    }

    public java.util.List<String> safeAlternatives(String toolName) { return registry.safeAlternatives(toolName); }
    public boolean isRegistered(String toolName) { return registry.contains(toolName); }
    public boolean isReadOnly(String toolName) { return registry.get(toolName) != null && registry.get(toolName).readOnly(); }
    public String capabilityFamily(String toolName) { return registry.get(toolName) == null ? null : registry.get(toolName).capabilityFamily(); }
    public boolean isDeclaredSafeAlternative(String sourceToolName, String alternativeToolName) {
        return sourceToolName != null && alternativeToolName != null
                && registry.safeAlternatives(sourceToolName).contains(alternativeToolName);
    }

    public Map<String, Object> descriptor() {
        Map<String, Object> output = new LinkedHashMap<>();
        registry.all().forEach((key, value) -> output.put(key, Map.of("description", value.description(), "readOnly", value.readOnly(),
                "capability", value.capability().name(), "idempotent", value.idempotent(), "capabilityFamily", value.capabilityFamily(),
                "safeAlternatives", registry.safeAlternatives(key))));
        return output;
    }

    private ToolResult normalize(ToolResult result) {
        if (result == null) return ToolResult.contractFailure("NULL_TOOL_RESULT", "工具返回了空结果");
        if (result.getExecutionStatus() != null) return result;
        if (result.getStatus() == ToolStatus.UNAVAILABLE) { result.setExecutionStatus(ToolExecutionStatus.UNAVAILABLE); return result; }
        if (result.getStatus() == ToolStatus.FAILED) {
            result.setExecutionStatus(result.isRetryable() ? ToolExecutionStatus.RETRYABLE_FAILURE : ToolExecutionStatus.NON_RETRYABLE_FAILURE);
            return result;
        }
        Object status = result.getOutput() == null ? null : result.getOutput().get("status");
        Object historical = result.getMetadata() == null ? null : result.getMetadata().get("historicalStatus");
        if ("EMPTY".equalsIgnoreCase(String.valueOf(status)) || "NO_KNOWLEDGE_AVAILABLE".equalsIgnoreCase(String.valueOf(status))
                || "READY_EMPTY".equalsIgnoreCase(String.valueOf(historical))) result.setExecutionStatus(ToolExecutionStatus.EMPTY);
        else result.setExecutionStatus(ToolExecutionStatus.SUCCESS);
        return result;
    }
}

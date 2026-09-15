package com.mei.zhgy.service.agent;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {
    @Builder.Default private ToolStatus status = ToolStatus.COMPLETED;
    @Builder.Default private Map<String, Object> output = new LinkedHashMap<>();
    @Builder.Default private List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
    @Builder.Default private List<ProposedAction> actions = new ArrayList<>();
    @Builder.Default private Map<String, Object> metadata = new LinkedHashMap<>();
    private String errorCode;
    private String errorMessage;
    private ToolExecutionStatus executionStatus;
    private String errorType;
    private boolean retryable;
    private Integer attempt;
    private Long durationMs;

    public static ToolResult unavailable(String code, String message) {
        return ToolResult.builder().status(ToolStatus.UNAVAILABLE).executionStatus(ToolExecutionStatus.UNAVAILABLE)
                .errorCode(code).errorMessage(message).errorType("DEPENDENCY_UNAVAILABLE").build();
    }
    public static ToolResult failed(String code, String message) {
        return ToolResult.builder().status(ToolStatus.FAILED).executionStatus(ToolExecutionStatus.NON_RETRYABLE_FAILURE)
                .errorCode(code).errorMessage(message).errorType("NON_RETRYABLE").build();
    }
    public static ToolResult retryableFailure(String code, String message) {
        return ToolResult.builder().status(ToolStatus.FAILED).executionStatus(ToolExecutionStatus.RETRYABLE_FAILURE)
                .retryable(true).errorCode(code).errorMessage(message).errorType("TRANSIENT").build();
    }
    public static ToolResult contractFailure(String code, String message) {
        return ToolResult.builder().status(ToolStatus.FAILED).executionStatus(ToolExecutionStatus.CONTRACT_FAILURE)
                .errorCode(code).errorMessage(message).errorType("CONTRACT").build();
    }
    public static ToolResult empty(Map<String, Object> output) {
        return ToolResult.builder().status(ToolStatus.COMPLETED).executionStatus(ToolExecutionStatus.EMPTY)
                .output(output == null ? new LinkedHashMap<>() : output).build();
    }
}

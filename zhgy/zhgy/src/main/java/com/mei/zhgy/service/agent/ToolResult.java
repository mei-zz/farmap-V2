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

    public static ToolResult unavailable(String code, String message) {
        return ToolResult.builder().status(ToolStatus.UNAVAILABLE).errorCode(code).errorMessage(message).build();
    }
    public static ToolResult failed(String code, String message) {
        return ToolResult.builder().status(ToolStatus.FAILED).errorCode(code).errorMessage(message).build();
    }
}

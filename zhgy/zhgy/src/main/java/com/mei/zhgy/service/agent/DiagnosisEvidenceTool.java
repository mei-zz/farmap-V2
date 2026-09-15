package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads the immutable Diagnosis snapshot already attached to this Agent Run. */
@Component
public class DiagnosisEvidenceTool implements AgentTool {
    @Override public String name() { return "diagnosis-evidence"; }
    @Override public String description() { return "读取已保存 Diagnosis、Evidence 与 Recommendation 快照"; }
    @Override public boolean readOnly() { return true; }

    @Override
    public ToolResult execute(AgentToolRequest request) {
        AgentContext context = request == null ? null : request.getContext();
        if (context == null || context.getDiagnosisId() == null || context.getDiagnosisId().isBlank()
                || context.getDiagnosis() == null || context.getDiagnosis().isEmpty()) {
            return ToolResult.unavailable("DIAGNOSIS_SNAPSHOT_UNAVAILABLE", "已保存 Diagnosis 快照不可用");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("diagnosisId", context.getDiagnosisId());
        output.put("recommendationId", context.getSourceRecommendationId());
        output.put("diagnosis", context.getDiagnosis());
        return ToolResult.builder().output(output).metadata(Map.of("sourceMode", "PERSISTED_DIAGNOSIS")).build();
    }
}

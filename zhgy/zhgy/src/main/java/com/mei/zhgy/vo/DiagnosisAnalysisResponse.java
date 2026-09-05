package com.mei.zhgy.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** 结构化诊断响应，禁止让前端解析 LLM 自由文本。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiagnosisAnalysisResponse {
    @JsonProperty("run_id")
    private String runId;
    private Diagnosis diagnosis;
    @JsonProperty("visual_findings")
    private List<String> visualFindings;
    private List<Claim> claims;
    private List<DiagnosisEvidenceVO> evidence;
    private List<Recommendation> recommendations;
    @JsonProperty("retrieval_summary")
    private Map<String, Object> retrievalSummary;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Diagnosis {
        private String title;
        @JsonProperty("risk_level")
        private String riskLevel;
        private Double confidence;
        private String summary;
        private List<Alternative> alternatives;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Alternative {
        private String name;
        private Double probability;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Claim {
        private String id;
        private String text;
        @JsonProperty("evidence_ids")
        private List<String> evidenceIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recommendation {
        private String id;
        private String title;
        private String priority;
        private String detail;
        @JsonProperty("evidence_ids")
        private List<String> evidenceIds;
    }
}

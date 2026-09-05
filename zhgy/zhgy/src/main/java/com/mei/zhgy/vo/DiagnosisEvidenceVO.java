package com.mei.zhgy.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 统一 Evidence Contract，屏蔽不同 Retriever 的返回差异。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiagnosisEvidenceVO {
    private String id;
    private String modality;
    private String title;
    private String summary;
    private Double score;
    private Source source;
    private Preview preview;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Source {
        private String type;
        private String name;
        private String timestamp;
        private String document;
        private String section;
        private String fieldId;
        private String cameraId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Preview {
        private String imageUrl;
        private Object chartData;
        private String textSnippet;
    }
}


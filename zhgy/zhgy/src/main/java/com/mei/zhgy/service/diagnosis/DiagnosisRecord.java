package com.mei.zhgy.service.diagnosis;

import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Stable Diagnosis business object; the response is retained as an immutable snapshot payload. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisRecord {
    private String diagnosisId;
    private String sourceRunId;
    private Long userId;
    private Integer farmId;
    private String fieldId;
    private String crop;
    private String status;
    private String riskLevel;
    private Double confidence;
    private String summary;
    private String mode;
    private DiagnosisAnalysisResponse payload;
    private Instant createdAt;
    private Instant updatedAt;
}

package com.mei.zhgy.service.field;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FarmField {
    private String fieldId;
    private Integer farmId;
    private String name;
    private String crop;
    private BigDecimal area;
    private String growthStage;
    private Double latitude;
    private Double longitude;
    private String status;
    private String sourceMode;
    private Long ownerUserId;
    private Instant createdAt;
    private Instant updatedAt;
}

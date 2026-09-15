package com.mei.zhgy.service.agriculture;

import com.mei.zhgy.service.field.FieldContext;

import java.time.LocalDate;
import java.util.Map;

/** Canonical server facts shared by light chat and heavy Agent entry points. */
public record FieldFacts(String status, LocalDate serverDate, String currentDate,
                         String fieldId, Integer farmId, String cropRaw, String cropCode, String cropLabel,
                         String registeredGrowthStage, String registeredGrowthStageLabel,
                         String dateDerivedGrowthStage, String dateDerivedGrowthStageLabel,
                         String currentGrowthStage, String currentGrowthStageLabel,
                         String previousGrowthStage, String previousGrowthStageLabel,
                         String nextGrowthStage, String nextGrowthStageLabel, Long daysUntilNext,
                         String phenologyCycleType, java.util.List<String> orderedGrowthStages,
                         boolean stageMismatch, boolean cycleEnded, Map<String, String> sources,
                         FieldContext trustedContext) {
    public boolean ready() { return "READY".equals(status); }
    public boolean unknownCrop() { return "UNKNOWN_CROP".equals(status); }
    public boolean noField() { return "NO_FIELD".equals(status); }
}

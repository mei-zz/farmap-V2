package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.agriculture.PhenologyResult;

import java.util.List;
import java.util.Map;

/** Crop and calendar facts at the scope's level of authority. */
record CopilotFacts(String currentDate, String crop, String cropLabel, String growthStage, String growthStageLabel,
                            String dateDerivedGrowthStage, String dateDerivedGrowthStageLabel, String registeredGrowthStage,
                            String registeredGrowthStageLabel, String previousGrowthStage, String previousGrowthStageLabel,
                            String nextGrowthStage, String nextGrowthStageLabel, Long daysUntilNext,
                            List<String> orderedStageNames, String cycleType, boolean stageMismatch, boolean cycleEnded,
                            String status, Map<String, String> sources) {
    static CopilotFacts from(FieldFacts facts) {
        return new CopilotFacts(facts.currentDate(), facts.cropCode(), facts.cropLabel(), facts.currentGrowthStage(), facts.currentGrowthStageLabel(),
                facts.dateDerivedGrowthStage(), facts.dateDerivedGrowthStageLabel(), facts.registeredGrowthStage(), facts.registeredGrowthStageLabel(),
                facts.previousGrowthStage(), facts.previousGrowthStageLabel(), facts.nextGrowthStage(), facts.nextGrowthStageLabel(), facts.daysUntilNext(),
                facts.orderedGrowthStages(), facts.phenologyCycleType(), facts.stageMismatch(), facts.cycleEnded(), facts.status(), facts.sources());
    }

    static CopilotFacts from(String currentDate, CropResolver.ResolvedCrop crop, PhenologyResult result,
                                     List<String> orderedStageNames, String cycleType, Map<String, String> sources) {
        return from(currentDate, crop, result, orderedStageNames, cycleType, sources, "READY");
    }

    static CopilotFacts from(String currentDate, CropResolver.ResolvedCrop crop, PhenologyResult result,
                                     List<String> orderedStageNames, String cycleType, Map<String, String> sources,
                                     String status) {
        return new CopilotFacts(currentDate, crop.code(), crop.name(), result.currentCode(), result.currentName(), result.dateDerivedCode(), result.dateDerivedName(),
                result.registeredStage() == null ? null : result.registeredStage().code(), result.registeredStage() == null ? null : result.registeredStage().name(),
                result.previousCode(), result.previousName(), result.nextCode(), result.nextName(), result.daysUntilNext(),
                orderedStageNames == null ? List.of() : orderedStageNames, cycleType, result.mismatch(), result.cycleEnded(), status, sources);
    }

    boolean ready() { return "READY".equals(status); }

    static CopilotFacts empty(String currentDate, String status) {
        return new CopilotFacts(currentDate, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, false, false, status, Map.of("serverDate", "SERVER_ASIA_SHANGHAI"));
    }

    static CopilotFacts scopeOnly(String currentDate, CropResolver.ResolvedCrop crop, String stageCode) {
        return new CopilotFacts(currentDate, crop == null ? null : crop.code(), crop == null ? null : crop.name(),
                stageCode, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, false, false, "SCOPE_ONLY", Map.of("serverDate", "SERVER_ASIA_SHANGHAI"));
    }
}

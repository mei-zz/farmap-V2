package com.mei.zhgy.service.agriculture;

public record PhenologyResult(PhenologyStage dateDerivedStage, PhenologyStage registeredStage,
                              PhenologyStage currentStage, PhenologyStage previousStage,
                              PhenologyStage nextStage, Long daysUntilNext, boolean mismatch,
                              boolean cycleEnded) {
    public String currentCode() { return currentStage == null ? null : currentStage.code(); }
    public String currentName() { return currentStage == null ? null : currentStage.name(); }
    public String dateDerivedCode() { return dateDerivedStage == null ? null : dateDerivedStage.code(); }
    public String dateDerivedName() { return dateDerivedStage == null ? null : dateDerivedStage.name(); }
    public String previousCode() { return previousStage == null ? null : previousStage.code(); }
    public String previousName() { return previousStage == null ? null : previousStage.name(); }
    public String nextCode() { return nextStage == null ? null : nextStage.code(); }
    public String nextName() { return nextStage == null ? null : nextStage.name(); }
}

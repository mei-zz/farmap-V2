package com.mei.zhgy.service.agriculture;

import java.util.List;

public record PhenologyCalendar(String cropCode, String cycleType, List<PhenologyStage> stages,
                                boolean hasDormancy, boolean terminalAfterHarvest) {
    public PhenologyCalendar {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }
}

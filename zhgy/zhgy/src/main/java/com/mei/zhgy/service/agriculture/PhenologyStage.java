package com.mei.zhgy.service.agriculture;

import java.time.MonthDay;
import java.util.List;

/** One date-driven phenology phase. Dates are intentionally approximate旬 boundaries. */
public record PhenologyStage(String code, String name, List<String> aliases,
                             MonthDay from, MonthDay to, int priority, boolean terminal) {
    public PhenologyStage {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public boolean contains(MonthDay value) {
        if (!from.isAfter(to)) return !value.isBefore(from) && !value.isAfter(to);
        return !value.isBefore(from) || !value.isAfter(to);
    }
}

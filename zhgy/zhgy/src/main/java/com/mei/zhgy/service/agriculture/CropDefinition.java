package com.mei.zhgy.service.agriculture;

import java.util.List;

/** Server-owned crop master data. User-facing aliases never become facts. */
public record CropDefinition(String code, String name, List<String> aliases, String phenologyCalendarId) {
    public CropDefinition {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}

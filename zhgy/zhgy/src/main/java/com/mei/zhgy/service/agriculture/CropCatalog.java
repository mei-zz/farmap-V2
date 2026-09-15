package com.mei.zhgy.service.agriculture;

import org.springframework.stereotype.Component;

import java.util.List;

/** P0 crop directory. Adding a crop is a data change, not a router switch. */
@Component
public class CropCatalog {
    private final List<CropDefinition> crops = List.of(
            new CropDefinition("plum", "脆李", List.of("plum", "脆李", "巫山脆李", "李子", "青脆李"), "plum"),
            new CropDefinition("citrus", "柑橘", List.of("citrus", "柑橘", "沃柑", "橙子", "脐橙", "椪柑", "春见", "爱媛38", "金秋砂糖橘", "龙回红", "纽荷尔", "W默科特"), "citrus"),
            new CropDefinition("rice", "水稻", List.of("rice", "水稻", "稻谷", "中稻"), "rice"),
            new CropDefinition("rice_late", "晚稻/再生稻", List.of("rice_late", "晚稻", "晚季稻", "再生稻"), "rice_late")
    );

    public List<CropDefinition> all() { return crops; }
}

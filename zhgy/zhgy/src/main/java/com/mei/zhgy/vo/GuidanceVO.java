package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuidanceVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String guidanceType;
    private List<GuidanceItem> items;
    private Integer status;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GuidanceItem {
        private Boolean isFormula;
        private String text;
    }
}
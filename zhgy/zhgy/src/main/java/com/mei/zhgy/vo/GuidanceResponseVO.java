package com.mei.zhgy.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class GuidanceResponseVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private List<GuidanceItem> body;
    private List<GuidanceItem> fertile;
    private List<GuidanceItem> pest;
    private List<GuidanceItem> park;
    
    @Data
    @Builder
    public static class GuidanceItem implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private Integer id;
        private String text;
        private Boolean isFormula;
    }
}
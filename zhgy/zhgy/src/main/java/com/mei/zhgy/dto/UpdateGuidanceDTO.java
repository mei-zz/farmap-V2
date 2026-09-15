package com.mei.zhgy.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class UpdateGuidanceDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer farmId;
    private String guidanceType;
    private List<GuidanceContent> content;
    
    @Data
    public static class GuidanceContent implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String text;
        private Boolean isFormula;
    }
}
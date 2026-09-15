package com.mei.zhgy.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class WeatherIntroResponseVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private List<List<WeatherIntroItem>> intro;
    
    @Data
    @Builder
    public static class WeatherIntroItem implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private Integer id;
        private String text;
    }
}
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
public class FarmLocationVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer farmId;
    private List<LocationPoint> locations;
    private Integer status;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationPoint {
        private Integer id;
        private Double longitude;
        private Double latitude;
    }
}
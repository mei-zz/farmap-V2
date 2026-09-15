package com.mei.zhgy.vo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccumulatedTemperatureVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private List<TemperatureItem> last;  // 去年数据
    private List<TemperatureItem> thisYear; // 今年数据

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemperatureItem implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private Integer id;
        private LocalDate date;
        private BigDecimal accTemp; // 累积温度
    }
}
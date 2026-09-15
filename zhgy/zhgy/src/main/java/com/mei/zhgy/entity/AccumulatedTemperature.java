package com.mei.zhgy.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccumulatedTemperature implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer id;
    private Integer farmId;
    private LocalDate date;
    private BigDecimal temperature; // 当日平均温度
    private BigDecimal accumulatedTemp; // 累积温度
}
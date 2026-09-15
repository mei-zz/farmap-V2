package com.mei.zhgy.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Guidance implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer id;
    private Integer farmId;
    private String farmType;
    private Integer month;
    private String guidanceType; // body, fertite, pest, park
    private String text;
    private Boolean isFormula;
}
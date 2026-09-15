package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CropInfoVO implements Serializable {

    private String diseases;
    private Double rate;
    private String url;
    private Integer yield;
    private String phenology;
    private String growth;
    private String impact;
    private String disease;
    private Double potassium;
    private Double phosphorus;
    private Double nitrogen;
}
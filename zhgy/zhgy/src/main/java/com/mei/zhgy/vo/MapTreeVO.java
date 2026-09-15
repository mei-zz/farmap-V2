package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MapTreeVO {
    private double treeHeight;
    private double treeWidth;
    private double treeVolume;
    private double leafTransmittance;
    private String treeImg;

    private double treeYield;
    private double treeFruit;

    private double pestRate;
    private String pestTypes;
    private String severity;

}

package com.mei.zhgy.dto;

import io.swagger.annotations.ApiModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "地图控制界面的数据模型")
public class MapTreeDTO {
    //冠层特征，单树产量，病害虫率 分别为0,1,2
    private Integer canopyYieldPestChoice;
    //作物，农场 分别为0.1
    private Integer cropFarmChoice;
    private double slideMin;
    private double slideMax;

}

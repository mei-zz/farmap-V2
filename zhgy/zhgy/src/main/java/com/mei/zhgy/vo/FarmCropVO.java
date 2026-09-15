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
public class FarmCropVO implements Serializable {

    private Integer id;
    private Double longitude;
    private Double latitude;
    private CropInfoVO info;
}
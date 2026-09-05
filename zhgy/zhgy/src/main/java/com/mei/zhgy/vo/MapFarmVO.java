package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MapFarmVO {
    //图标的名字
    private String farmName;
    //监控视频的连接地址
    private String farmVideo;
}

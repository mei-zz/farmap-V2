package com.mei.zhgy.service;

import com.mei.zhgy.dto.MapTreeDTO;
import com.mei.zhgy.result.NoPageResult;
import org.springframework.stereotype.Service;

public interface MapService {
    /**
     * 地图控制界面，作物-信息查询
     * @param mapTree
     * @return
     */
    NoPageResult queryTree(MapTreeDTO mapTree);

    /**
     * 地图控制界面，农场-信息查询
     * @param map
     * @return
     */
    NoPageResult queryFarm(MapTreeDTO map);
}

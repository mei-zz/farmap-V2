package com.mei.zhgy.service.impl;

import com.mei.zhgy.dto.MapTreeDTO;
import com.mei.zhgy.mapper.MapMapper;
import com.mei.zhgy.result.NoPageResult;
import com.mei.zhgy.vo.MapFarmVO;
import com.mei.zhgy.vo.MapTreeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MapService implements com.mei.zhgy.service.MapService {
    @Autowired
    private MapMapper mapMapper;
    /**
     * 地图控制界面信息查询
     * @param mapTree
     * @return
     */
    @Override
    public NoPageResult queryTree(MapTreeDTO mapTree) {
        List<MapTreeVO> treeList = mapMapper.selectTree(mapTree);
        NoPageResult res = new NoPageResult();
        res.setRecords(treeList);
        return res;
    }

    /**
     * 地图控制界面，农场-信息查询
     * @param map
     * @return
     */
    @Override
    public NoPageResult queryFarm(MapTreeDTO map) {
        List<MapFarmVO> farmList = mapMapper.selectFarm(map);
        NoPageResult res = new NoPageResult();
        res.setRecords(farmList);
        return res;
    }
}

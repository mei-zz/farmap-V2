package com.mei.zhgy.service;

import com.mei.zhgy.entity.FarmLocation;

import java.util.List;

public interface FarmLocationService {
    
    /**
     * 根据农场ID获取农场位置信息
     * @param farmId
     * @return
     */
    List<FarmLocation> getLocationsByFarmId(Integer farmId);
    
    /**
     * 获取所有农场位置信息
     * @return
     */
    List<FarmLocation> getAllLocations();
}
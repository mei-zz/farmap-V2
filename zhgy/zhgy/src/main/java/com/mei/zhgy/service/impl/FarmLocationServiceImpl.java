package com.mei.zhgy.service.impl;

import com.mei.zhgy.entity.FarmLocation;
import com.mei.zhgy.mapper.FarmLocationMapper;
import com.mei.zhgy.service.FarmLocationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class FarmLocationServiceImpl implements FarmLocationService {
    
    @Autowired
    private FarmLocationMapper farmLocationMapper;
    
    /**
     * 根据农场ID获取农场位置信息
     * @param farmId
     * @return
     */
    @Override
    public List<FarmLocation> getLocationsByFarmId(Integer farmId) {
        return farmLocationMapper.getLocationsByFarmId(farmId);
    }
    
    /**
     * 获取所有农场位置信息
     * @return
     */
    @Override
    public List<FarmLocation> getAllLocations() {
        return farmLocationMapper.getAllLocations();
    }
}
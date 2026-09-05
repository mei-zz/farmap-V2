package com.mei.zhgy.service.impl;

import com.mei.zhgy.entity.Weather;
import com.mei.zhgy.mapper.WeatherMapper;
import com.mei.zhgy.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class WeatherServiceImpl implements WeatherService {
    
    @Autowired
    private WeatherMapper weatherMapper;
    
    /**
     * 根据农场ID和月份获取天气介绍
     * @param farmId
     * @param month
     * @return
     */
    @Override
    public List<Weather> getByFarmIdAndMonth(Integer farmId, Integer month) {
        return weatherMapper.getByFarmIdAndMonth(farmId, month);
    }
    
    /**
     * 根据农场类型和月份获取天气介绍
     * @param farmType
     * @param month
     * @return
     */
    @Override
    public List<Weather> getByFarmTypeAndMonth(String farmType, Integer month) {
        return weatherMapper.getByFarmTypeAndMonth(farmType, month);
    }
}
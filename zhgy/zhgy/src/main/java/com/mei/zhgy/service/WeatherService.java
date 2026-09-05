package com.mei.zhgy.service;

import com.mei.zhgy.entity.Weather;

import java.util.List;

public interface WeatherService {
    
    /**
     * 根据农场ID和月份获取天气介绍
     * @param farmId
     * @param month
     * @return
     */
    List<Weather> getByFarmIdAndMonth(Integer farmId, Integer month);
    
    /**
     * 根据农场类型和月份获取天气介绍
     * @param farmType
     * @param month
     * @return
     */
    List<Weather> getByFarmTypeAndMonth(String farmType, Integer month);
}
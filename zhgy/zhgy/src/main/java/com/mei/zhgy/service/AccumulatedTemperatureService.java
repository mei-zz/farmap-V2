package com.mei.zhgy.service;

import com.mei.zhgy.entity.AccumulatedTemperature;

import java.time.LocalDate;
import java.util.List;

public interface AccumulatedTemperatureService {
    
    /**
     * 保存积温记录
     * @param accumulatedTemperature
     */
    void save(AccumulatedTemperature accumulatedTemperature);
    
    /**
     * 批量保存积温记录
     * @param temperatures
     */
    void batchSave(List<AccumulatedTemperature> temperatures);
    
    /**
     * 根据农场ID和日期范围获取积温记录
     * @param farmId
     * @param startDate
     * @param endDate
     * @return
     */
    List<AccumulatedTemperature> getByFarmIdAndDateRange(Integer farmId, LocalDate startDate, LocalDate endDate);
    
    /**
     * 根据农场ID和年份获取积温记录
     * @param farmId
     * @param year
     * @return
     */
    List<AccumulatedTemperature> getByFarmIdAndYear(Integer farmId, Integer year);
}
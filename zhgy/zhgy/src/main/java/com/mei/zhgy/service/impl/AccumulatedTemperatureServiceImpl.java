package com.mei.zhgy.service.impl;

import com.mei.zhgy.entity.AccumulatedTemperature;
import com.mei.zhgy.mapper.AccumulatedTemperatureMapper;
import com.mei.zhgy.service.AccumulatedTemperatureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
public class AccumulatedTemperatureServiceImpl implements AccumulatedTemperatureService {
    
    @Autowired
    private AccumulatedTemperatureMapper accumulatedTemperatureMapper;
    
    /**
     * 保存积温记录
     * @param accumulatedTemperature
     */
    @Override
    public void save(AccumulatedTemperature accumulatedTemperature) {
        accumulatedTemperatureMapper.insert(accumulatedTemperature);
    }
    
    /**
     * 批量保存积温记录
     * @param temperatures
     */
    @Override
    public void batchSave(List<AccumulatedTemperature> temperatures) {
        accumulatedTemperatureMapper.batchInsert(temperatures);
    }
    
    /**
     * 根据农场ID和日期范围获取积温记录
     * @param farmId
     * @param startDate
     * @param endDate
     * @return
     */
    @Override
    public List<AccumulatedTemperature> getByFarmIdAndDateRange(Integer farmId, LocalDate startDate, LocalDate endDate) {
        return accumulatedTemperatureMapper.getByFarmIdAndDateRange(farmId, startDate, endDate);
    }
    
    /**
     * 根据农场ID和年份获取积温记录
     * @param farmId
     * @param year
     * @return
     */
    @Override
    public List<AccumulatedTemperature> getByFarmIdAndYear(Integer farmId, Integer year) {
        return accumulatedTemperatureMapper.getByFarmIdAndYear(farmId, year);
    }
}
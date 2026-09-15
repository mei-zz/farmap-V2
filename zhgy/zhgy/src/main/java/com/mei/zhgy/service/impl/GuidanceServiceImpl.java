package com.mei.zhgy.service.impl;

import com.mei.zhgy.entity.Guidance;
import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.mapper.GuidanceMapper;
import com.mei.zhgy.service.GuidanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class GuidanceServiceImpl implements GuidanceService {
    
    @Autowired
    private GuidanceMapper guidanceMapper;
    
    @Autowired
    private FarmMapper farmMapper;
    
    /**
    	* 根据农场ID和月份获取指导信息
    	* @param farmId
    	* @param month
    	* @return
    	*/
    @Override
    public List<Guidance> getByFarmIdAndMonth(Integer farmId, Integer month) {
        return guidanceMapper.getByFarmIdAndMonth(farmId, month);
    }
    
    /**
    	* 根据农场ID、月份和指导类型获取指导信息
    	* @param farmId
    	* @param month
    	* @param guidanceType
    	* @return
    	*/
    @Override
    public List<Guidance> getByFarmIdAndMonthAndType(Integer farmId, Integer month, String guidanceType) {
        return guidanceMapper.getByFarmIdAndMonthAndType(farmId, month, guidanceType);
    }
    
    /**
    	* 根据农场ID获取所有指导信息
    	* @param farmId
    	* @return
    	*/
    @Override
    public List<Guidance> getByFarmId(Integer farmId) {
        return guidanceMapper.getByFarmId(farmId);
    }
    
    /**
    	* 根据农场类型和月份获取指导信息
    	* @param farmType
    	* @param month
    	* @return
    	*/
    @Override
    public List<Guidance> getByFarmTypeAndMonth(String farmType, Integer month) {
        return guidanceMapper.getByFarmTypeAndMonth(farmType, month);
    }
    
    /**
    	* 根据农场类型、月份和指导类型获取指导信息
    	* @param farmType
    	* @param month
    	* @param guidanceType
    	* @return
    	*/
    @Override
    public List<Guidance> getByFarmTypeAndMonthAndType(String farmType, Integer month, String guidanceType) {
        return guidanceMapper.getByFarmTypeAndMonthAndType(farmType, month, guidanceType);
    }
    
    /**
    	* 更新农场指定类型的指导信息
    	* @param farmId
    	* @param guidanceType
    	* @param month
    	* @param guidances
    	*/
    @Override
    @Transactional
    public void updateGuidanceByFarmIdAndType(Integer farmId, String guidanceType, Integer month, List<Guidance> guidances) {
        // 删除原有数据
        guidanceMapper.deleteByFarmIdAndType(farmId, guidanceType);
        
        // 获取农场类型
        String farmType = farmMapper.getFarmTypeById(farmId);
        
        // 插入新数据
        for (Guidance guidance : guidances) {
            guidance.setFarmId(farmId);
            guidance.setFarmType(farmType);
            guidance.setMonth(month);
            guidance.setGuidanceType(guidanceType);
            guidanceMapper.insert(guidance);
        }
    }
}
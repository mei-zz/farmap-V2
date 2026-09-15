package com.mei.zhgy.service;

import com.mei.zhgy.entity.Guidance;

import java.util.List;

public interface GuidanceService {
    
    /**
    	* 根据农场ID和月份获取指导信息
    	* @param farmId
    	* @param month
    	* @return
    	*/
    List<Guidance> getByFarmIdAndMonth(Integer farmId, Integer month);
    
    /**
    	* 根据农场ID、月份和指导类型获取指导信息
    	* @param farmId
    	* @param month
    	* @param guidanceType
    	* @return
    	*/
    List<Guidance> getByFarmIdAndMonthAndType(Integer farmId, Integer month, String guidanceType);
    
    /**
    	* 根据农场ID获取所有指导信息
    	* @param farmId
    	* @return
    	*/
    List<Guidance> getByFarmId(Integer farmId);
    
    /**
    	* 根据农场类型和月份获取指导信息
    	* @param farmType
    	* @param month
    	* @return
    	*/
    List<Guidance> getByFarmTypeAndMonth(String farmType, Integer month);
    
    /**
    	* 根据农场类型、月份和指导类型获取指导信息
    	* @param farmType
    	* @param month
    	* @param guidanceType
    	* @return
    	*/
    List<Guidance> getByFarmTypeAndMonthAndType(String farmType, Integer month, String guidanceType);
    
    /**
    	* 更新农场指定类型的指导信息
    	* @param farmId
    	* @param guidanceType
    	* @param month
    	* @param guidances
    	*/
    void updateGuidanceByFarmIdAndType(Integer farmId, String guidanceType, Integer month, List<Guidance> guidances);
}
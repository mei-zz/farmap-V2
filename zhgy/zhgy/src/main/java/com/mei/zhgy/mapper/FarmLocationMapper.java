package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.FarmLocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FarmLocationMapper {
    
    /**
     * 根据农场ID查询农场位置信息
     * @param farmId
     * @return
     */
    @Select("SELECT id, longitude, latitude FROM farmlocation WHERE farm_id = #{farmId}")
    List<FarmLocation> getLocationsByFarmId(Integer farmId);
    
    /**
     * 查询所有农场位置信息
     * @return
     */
    @Select("SELECT id, longitude, latitude FROM farmlocation")
    List<FarmLocation> getAllLocations();
}
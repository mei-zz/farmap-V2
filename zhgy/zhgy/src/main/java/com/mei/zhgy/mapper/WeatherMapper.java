package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.Weather;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface WeatherMapper {
    
    /**
     * 根据农场ID和月份查询天气介绍
     * @param farmId
     * @param month
     * @return
     */
    @Select("SELECT * FROM weather WHERE farm_id = #{farmId} AND month = #{month}")
    List<Weather> getByFarmIdAndMonth(@Param("farmId") Integer farmId, @Param("month") Integer month);
    
    /**
     * 根据农场类型和月份查询天气介绍
     * @param farmType
     * @param month
     * @return
     */
    @Select("SELECT * FROM weather WHERE farm_type = #{farmType} AND month = #{month}")
    List<Weather> getByFarmTypeAndMonth(@Param("farmType") String farmType, @Param("month") Integer month);
}
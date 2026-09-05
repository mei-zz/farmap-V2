package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.AccumulatedTemperature;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AccumulatedTemperatureMapper {
    
    /**
     * 插入积温记录
     * @param accumulatedTemperature
     */
    @Insert("INSERT INTO accumulated_temperature (farm_id, date, temperature, accumulated_temp) " +
            "VALUES (#{farmId}, #{date}, #{temperature}, #{accumulatedTemp})")
    void insert(AccumulatedTemperature accumulatedTemperature);
    
    /**
     * 批量插入积温记录
     * @param temperatures
     */
    @Insert("<script>" +
            "INSERT INTO accumulated_temperature (farm_id, date, temperature, accumulated_temp) VALUES " +
            "<foreach collection='temperatures' item='temp' separator=','>" +
            "(#{temp.farmId}, #{temp.date}, #{temp.temperature}, #{temp.accumulatedTemp})" +
            "</foreach>" +
            "</script>")
    void batchInsert(@Param("temperatures") List<AccumulatedTemperature> temperatures);
    
    /**
     * 根据农场ID和日期范围查询积温记录
     * @param farmId
     * @param startDate
     * @param endDate
     * @return
     */
    @Select("SELECT id, farm_id as farmId, date, temperature, accumulated_temp as accumulatedTemp " +
            "FROM accumulated_temperature " +
            "WHERE farm_id = #{farmId} AND date >= #{startDate} AND date <= #{endDate} " +
            "ORDER BY date")
    List<AccumulatedTemperature> getByFarmIdAndDateRange(
            @Param("farmId") Integer farmId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
    
    /**
     * 根据农场ID查询指定年份的积温记录
     * @param farmId
     * @param year
     * @return
     */
    @Select("SELECT id, farm_id as farmId, date, temperature, accumulated_temp as accumulatedTemp " +
            "FROM accumulated_temperature " +
            "WHERE farm_id = #{farmId} AND YEAR(date) = #{year} " +
            "ORDER BY date")
    List<AccumulatedTemperature> getByFarmIdAndYear(
            @Param("farmId") Integer farmId,
            @Param("year") Integer year);
}
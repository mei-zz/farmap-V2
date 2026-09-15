package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.Guidance;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface GuidanceMapper {
    
    /**
     * 根据农场ID和月份查询指导信息
     * @param farmId
     * @param month
     * @return
     */
    @Select("SELECT * FROM guidances WHERE farm_id = #{farmId} AND month = #{month}")
    List<Guidance> getByFarmIdAndMonth(@Param("farmId") Integer farmId, @Param("month") Integer month);
    
    /**
     * 根据农场ID、月份和指导类型查询指导信息
     * @param farmId
     * @param month
     * @param guidanceType
     * @return
     */
    @Select("SELECT * FROM guidances WHERE farm_id = #{farmId} AND month = #{month} AND guidance_type = #{guidanceType}")
    List<Guidance> getByFarmIdAndMonthAndType(@Param("farmId") Integer farmId, @Param("month") Integer month, @Param("guidanceType") String guidanceType);
    
    /**
     * 根据农场ID查询所有指导信息
     * @param farmId
     * @return
     */
    @Select("SELECT * FROM guidances WHERE farm_id = #{farmId}")
    List<Guidance> getByFarmId(@Param("farmId") Integer farmId);
    
    /**
     * 根据农场类型和月份查询指导信息
     * @param farmType
     * @param month
     * @return
     */
    @Select("SELECT * FROM guidances WHERE farm_type = #{farmType} AND month = #{month}")
    List<Guidance> getByFarmTypeAndMonth(@Param("farmType") String farmType, @Param("month") Integer month);
    
    /**
     * 根据农场类型、月份和指导类型查询指导信息
     * @param farmType
     * @param month
     * @param guidanceType
     * @return
     */
    @Select("SELECT * FROM guidances WHERE farm_type = #{farmType} AND month = #{month} AND guidance_type = #{guidanceType}")
    List<Guidance> getByFarmTypeAndMonthAndType(@Param("farmType") String farmType, @Param("month") Integer month, @Param("guidanceType") String guidanceType);
    
    /**
     * 根据农场ID和指导类型删除指导信息
     * @param farmId
     * @param guidanceType
     */
    @Delete("DELETE FROM guidances WHERE farm_id = #{farmId} AND guidance_type = #{guidanceType}")
    void deleteByFarmIdAndType(@Param("farmId") Integer farmId, @Param("guidanceType") String guidanceType);
    
    /**
     * 插入指导信息
     * @param guidance
     */
    @Insert("INSERT INTO guidances (farm_id, farm_type, month, guidance_type, text, is_formula) " +
            "VALUES (#{farmId}, #{farmType}, #{month}, #{guidanceType}, #{text}, #{isFormula})")
    void insert(Guidance guidance);
}
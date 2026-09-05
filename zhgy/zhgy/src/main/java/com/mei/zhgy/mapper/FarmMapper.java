package com.mei.zhgy.mapper;

import com.mei.zhgy.vo.FarmCropVO;
import com.mei.zhgy.vo.SimplifiedFarmVO;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FarmMapper {
    
    /**
     * 根据农场ID查询农场基本信息
     * @param farmId
     * @return
     */
    @Select("SELECT id, name, type, address, zoom, center, username, user_id FROM farm WHERE id = #{farmId}")
    UserLoginResponseVO.FarmVO getFarmById(Integer farmId);
    
    /**
     * 根据农场类型查询农场基本信息列表
     * @param farmType
     * @return
     */
    @Select("SELECT id, name, type, address, zoom, center, username, user_id FROM farm WHERE type = #{farmType}")
    List<UserLoginResponseVO.FarmVO> getFarmsByType(@Param("farmType") String farmType);
    
    /**
     * 根据农场类型查询农场基本信息
     * @param farmType
     * @return
     */
    @Select("SELECT id, name, type, address, zoom, center, username, user_id FROM farm WHERE type = #{farmType} LIMIT 1")
    UserLoginResponseVO.FarmVO getFarmByType(@Param("farmType") String farmType);
    
    /**
     * 查询农场的组件列表
     * @param farmId
     * @return
     */
    @Select("SELECT c.name FROM component c JOIN farm_component fc ON c.id = fc.component_id WHERE fc.farm_id = #{farmId}")
    List<String> getComponentsByFarmId(Integer farmId);
    
    /**
     * 查询农场的位置信息
     * @param farmId
     * @return
     */
    @Select("SELECT id, longitude, latitude FROM location WHERE farm_id = #{farmId}")
    List<UserLoginResponseVO.FarmVO.LocationVO> getLocationsByFarmId(Integer farmId);
    
    /**
     * 查询农场的作物信息
     * @param farmId
     * @return
     */
    List<FarmCropVO> getCropsByFarmId(Integer farmId);
    
    /**
     * 根据农场ID获取农场类型
     * @param farmId
     * @return
     */
    @Select("SELECT type FROM farm WHERE id = #{farmId}")
    String getFarmTypeById(@Param("farmId") Integer farmId);
    
    /**
     * 根据农场ID查询农场简要信息（只包含id和name）
     * @param farmId
     * @return
     */
    @Select("SELECT id, name FROM farm WHERE id = #{farmId}")
    SimplifiedFarmVO getSimpleFarmById(Integer farmId);
    
    /**
     * 根据经纬度获取城市编码
     * 注意：实际应用中可能需要调用高德地图的逆地理编码API来获取adcode
     * 这里简化处理，假设数据库中存储了adcode信息
     * @param longitude 经度
     * @param latitude 纬度
     * @return 城市编码
     */
    @Select("SELECT adcode FROM farm WHERE id = (SELECT farm_id FROM location WHERE longitude = #{longitude} AND latitude = #{latitude} LIMIT 1)")
    String getAdcodeByLocation(@Param("longitude") Double longitude, @Param("latitude") Double latitude);
}
package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.Model;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ModelMapper {
    
    /**
     * 根据农场ID获取模型列表
     * @param farmId 农场ID
     * @return 模型列表
     */
    @Select("SELECT id, farm_id, model_order, name, url, req_type, res_type FROM models WHERE farm_id = #{farmId} ORDER BY model_order")
    List<Model> getModelsByFarmId(@Param("farmId") Integer farmId);
    
    /**
     * 批量插入模型
     * @param models 模型列表
     * @return 插入的记录数
     */
    @Insert("<script>INSERT INTO models (farm_id, model_order, name, url, req_type, res_type) VALUES " +
            "<foreach collection='models' item='model' separator=','>" +
            "(#{model.farmId}, #{model.modelOrder}, #{model.name}, #{model.url}, #{model.reqType}, #{model.resType})" +
            "</foreach>" +
            "</script>")
    int batchInsertModels(@Param("models") List<Model> models);
    
    /**
     * 根据农场ID删除模型
     * @param farmId 农场ID
     * @return 删除的记录数
     */
    @Delete("DELETE FROM models WHERE farm_id = #{farmId}")
    int deleteModelsByFarmId(@Param("farmId") Integer farmId);
    
    /**
     * 根据ID更新模型
     * @param model 模型对象
     * @return 更新的记录数
     */
    @Update("UPDATE models SET model_order = #{modelOrder}, name = #{name}, url = #{url}, req_type = #{reqType}, res_type = #{resType} WHERE id = #{id}")
    int updateModelById(Model model);
    
    /**
     * 根据农场ID获取模型数量（用于检查顺序是否重复）
     * @param farmId 农场ID
     * @param modelOrder 模型顺序
     * @return 指定顺序的模型数量
     */
    @Select("SELECT COUNT(*) FROM models WHERE farm_id = #{farmId} AND model_order = #{modelOrder}")
    int countByFarmIdAndOrder(@Param("farmId") Integer farmId, @Param("modelOrder") Integer modelOrder);
    
    /**
     * 根据ID和农场ID获取模型（用于验证模型是否属于指定农场）
     * @param id 模型ID
     * @param farmId 农场ID
     * @return 模型对象
     */
    @Select("SELECT id, farm_id, model_order, name, url, req_type, res_type FROM models WHERE id = #{id} AND farm_id = #{farmId}")
    Model getModelByIdAndFarmId(@Param("id") Integer id, @Param("farmId") Integer farmId);
    
    /**
     * 根据ID和URL获取模型（用于验证删除请求）
     * @param id 模型ID
     * @param url 模型URL
     * @return 模型对象
     */
    @Select("SELECT id, farm_id, model_order, name, url, req_type, res_type FROM models WHERE id = #{id} AND url = #{url}")
    Model getModelByIdAndUrl(@Param("id") Integer id, @Param("url") String url);
    
    /**
     * 根据ID删除模型
     * @param id 模型ID
     * @return 删除的记录数
     */
    @Delete("DELETE FROM models WHERE id = #{id}")
    int deleteModelById(@Param("id") Integer id);
}
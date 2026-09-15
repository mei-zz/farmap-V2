package com.mei.zhgy.service;

import com.mei.zhgy.entity.Model;

import java.util.List;

public interface ModelService {
    
    /**
     * 根据农场ID获取模型列表
     * @param farmId 农场ID
     * @return 模型列表
     */
    List<Model> getModelsByFarmId(Integer farmId);
    
    /**
     * 创建模型列表
     * @param farmId 农场ID
     * @param models 模型列表
     * @return 是否创建成功
     */
    boolean createModelList(Integer farmId, List<Model> models);
    
    /**
     * 更新模型列表
     * @param farmId 农场ID
     * @param models 模型列表
     * @return 是否更新成功
     * @throws IllegalArgumentException 当模型顺序重复时抛出异常
     */
    boolean updateModelList(Integer farmId, List<Model> models) throws IllegalArgumentException;
    
    /**
     * 删除模型
     * @param id 模型ID
     * @param url 模型URL
     * @return 是否删除成功
     * @throws IllegalArgumentException 当ID和URL不匹配时抛出异常
     */
    boolean deleteModel(Integer id, String url) throws IllegalArgumentException;
}
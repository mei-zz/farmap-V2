package com.mei.zhgy.service.impl;

import com.mei.zhgy.entity.Model;
import com.mei.zhgy.mapper.ModelMapper;
import com.mei.zhgy.service.ModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ModelServiceImpl implements ModelService {
    
    @Autowired
    private ModelMapper modelMapper;
    
    @Override
    public List<Model> getModelsByFarmId(Integer farmId) {
        return modelMapper.getModelsByFarmId(farmId);
    }
    
    @Override
    @Transactional
    public boolean createModelList(Integer farmId, List<Model> models) {
        // 先删除该农场下原有的模型
        modelMapper.deleteModelsByFarmId(farmId);
        
        // 设置模型的农场ID
        for (Model model : models) {
            model.setFarmId(farmId);
        }
        
        // 批量插入新模型
        if (!models.isEmpty()) {
            modelMapper.batchInsertModels(models);
        }
        
        return true;
    }
    
    @Override
    @Transactional
    public boolean updateModelList(Integer farmId, List<Model> models) throws IllegalArgumentException {
        // 检查模型顺序是否有重复
        for (Model model : models) {
            // 首先验证模型是否属于指定的农场
            Model existingModel = modelMapper.getModelByIdAndFarmId(model.getId(), farmId);
            if (existingModel == null) {
                throw new IllegalArgumentException("模型ID " + model.getId() + " 不属于农场ID " + farmId);
            }
            
            // 检查是否有其他模型使用了相同的顺序
            int count = modelMapper.countByFarmIdAndOrder(farmId, model.getModelOrder());
            if (count > 0) {
                // 需要检查是否是当前正在更新的模型占用了这个顺序
                boolean orderUsedByOther = false;
                for (Model otherModel : models) {
                    if (!otherModel.getId().equals(model.getId()) && otherModel.getModelOrder().equals(model.getModelOrder())) {
                        orderUsedByOther = true;
                        break;
                    }
                }
                
                if (orderUsedByOther) {
                    throw new IllegalArgumentException("农场ID " + farmId + " 的模型顺序 " + model.getModelOrder() + " 存在重复");
                }
            }
        }
        
        // 更新所有模型
        for (Model model : models) {
            modelMapper.updateModelById(model);
        }
        
        return true;
    }
    
    @Override
    @Transactional
    public boolean deleteModel(Integer id, String url) throws IllegalArgumentException {
        // 验证ID和URL是否匹配
        Model model = modelMapper.getModelByIdAndUrl(id, url);
        if (model == null) {
            throw new IllegalArgumentException("模型ID " + id + " 与URL " + url + " 不匹配");
        }
        
        // 删除模型
        modelMapper.deleteModelById(id);
        return true;
    }
}
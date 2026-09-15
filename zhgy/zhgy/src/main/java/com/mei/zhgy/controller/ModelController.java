package com.mei.zhgy.controller;

import com.mei.zhgy.entity.Model;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.ModelService;
import com.mei.zhgy.vo.ModelVO;
import com.mei.zhgy.dto.CreateModelListRequest;
import com.mei.zhgy.dto.UpdateModelListRequest;
import com.mei.zhgy.dto.DeleteModelRequest;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/model")
@Api(tags = "模型相关接口")
public class ModelController {
    
    @Autowired
    private ModelService modelService;
    
    /**
     * 获取模型列表
     * @param farmId 农场ID
     * @return 模型列表
     */
    @GetMapping("/list")
    @ApiOperation(value = "获取模型列表")
    public Result<ModelVO> getModelList(@RequestParam Integer farmId) {
        log.info("获取模型列表：农场ID={}", farmId);
        
        try {
            // 获取模型列表
            List<Model> models = modelService.getModelsByFarmId(farmId);
            
            // 转换为VO对象
            List<ModelVO.ModelItem> modelItems = new ArrayList<>();
            for (Model model : models) {
                ModelVO.ModelItem item = new ModelVO.ModelItem();
                item.setId(model.getId());
                item.setName(model.getName());
                item.setOrder(model.getModelOrder());
                item.setReqType(model.getReqType());
                item.setResType(model.getResType());
                item.setUrl(model.getUrl());
                modelItems.add(item);
            }
            
            ModelVO modelVO = ModelVO.builder()
                    .message("Successed to read model list")
                    .status(0)
                    .models(modelItems)
                    .build();
            
            return Result.success(modelVO);
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
            return Result.error("获取模型列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建模型列表
     * @param request 创建模型列表请求
     * @return 创建结果
     */
    @PostMapping("/list")
    @ApiOperation(value = "创建模型列表")
    public Result<ModelVO> createModelList(@RequestBody CreateModelListRequest request) {
        log.info("创建模型列表：农场ID={}", request.getFarmId());
        
        try {
            // 转换DTO为实体
            List<Model> models = new ArrayList<>();
            for (CreateModelListRequest.ModelItem item : request.getModels()) {
                Model model = new Model();
                model.setName(item.getName());
                model.setModelOrder(item.getOrder());
                model.setReqType(item.getReqType());
                model.setResType(item.getResType());
                model.setUrl(item.getUrl());
                models.add(model);
            }
            
            // 创建模型列表
            modelService.createModelList(request.getFarmId(), models);
            
            // 返回成功响应
            ModelVO modelVO = ModelVO.builder()
                    .message("Successed to create model list")
                    .status(0)
                    .build();
            
            return Result.success(modelVO);
        } catch (Exception e) {
            log.error("创建模型列表失败", e);
            return Result.error("创建模型列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新模型列表
     * @param request 更新模型列表请求
     * @return 更新结果
     */
    @PutMapping("/list")
    @ApiOperation(value = "更新模型列表")
    public Result<ModelVO> updateModelList(@RequestBody UpdateModelListRequest request) {
        log.info("更新模型列表：农场ID={}", request.getFarmId());
        
        try {
            // 转换DTO为实体
            List<Model> models = new ArrayList<>();
            for (UpdateModelListRequest.ModelItem item : request.getNewModels()) {
                Model model = new Model();
                model.setId(item.getId());
                model.setName(item.getName());
                model.setModelOrder(item.getOrder());
                model.setReqType(item.getReqType());
                model.setResType(item.getResType());
                model.setUrl(item.getUrl());
                models.add(model);
            }
            
            // 更新模型列表
            modelService.updateModelList(request.getFarmId(), models);
            
            // 返回成功响应
            ModelVO modelVO = ModelVO.builder()
                    .message("Successed to update model list")
                    .status(0)
                    .build();
            
            return Result.success(modelVO);
        } catch (IllegalArgumentException e) {
            log.warn("更新模型列表失败：模型顺序重复或其他参数错误", e);
            return Result.error("更新模型列表失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("更新模型列表失败", e);
            return Result.error("更新模型列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除模型
     * @param request 删除模型请求
     * @return 删除结果
     */
    @DeleteMapping("/item")
    @ApiOperation(value = "删除模型")
    public Result<ModelVO> deleteModel(@RequestBody DeleteModelRequest request) {
        log.info("删除模型：ID={}", request.getId());
        
        try {
            // 删除模型
            modelService.deleteModel(request.getId(), request.getUrl());
            
            // 返回成功响应
            ModelVO modelVO = ModelVO.builder()
                    .message("Successed to delete a model")
                    .status(0)
                    .build();
            
            return Result.success(modelVO);
        } catch (IllegalArgumentException e) {
            log.warn("删除模型失败：ID与URL不匹配", e);
            return Result.error("删除模型失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("删除模型失败", e);
            return Result.error("删除模型失败: " + e.getMessage());
        }
    }
}
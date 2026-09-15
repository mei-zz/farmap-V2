package com.mei.zhgy.service;

import com.mei.zhgy.entity.Case;
import com.mei.zhgy.entity.InitialResult;
import com.mei.zhgy.entity.UserRequest;

import java.util.List;

public interface AIModelService {
    
    /**
     * 处理AI模型请求
     * @param imageUrls 图片URL列表（1-5张）
     * @param userId 用户ID
     * @return 初始结果
     */
    InitialResult processAIModelRequest(List<String> imageUrls, String userId) throws Exception;
    
    /**
     * 处理AI模型请求
     * @param imageUrls 图片URL列表（1-5张）
     * @param userId 用户ID
     * @param apiKey API密钥
     * @return 初始结果
     */
    InitialResult processAIModelRequest(List<String> imageUrls, String userId, String apiKey) throws Exception;
    
    /**
     * 保存用户请求信息
     * @param imageUrls 图片URL列表
     * @param userId 用户ID
     * @return 用户请求实体
     */
    UserRequest saveUserRequest(List<String> imageUrls, String userId) throws Exception;
    
    /**
     * 调用腾讯混元AI模型进行分析
     * @param imageUrls 图片URL列表
     * @return 分析结果的JSON字符串
     */
    String callHunyuanModel(List<String> imageUrls) throws Exception;
    
    /**
     * 保存初始结果
     * @param requestId 请求ID
     * @param jsonData JSON数据
     * @return 初始结果实体
     */
    InitialResult saveInitialResult(String requestId, String jsonData);
    
    /**
     * 使用RAG技术调用腾讯混元AI模型进行分析
     * @param imageUrls 图片URL列表
     * @param query 查询内容（用于检索相关知识）
     * @return 分析结果的JSON字符串
     */
    String callHunyuanModelWithRAG(List<String> imageUrls, String query) throws Exception;
    
    /**
     * 使用相似案例检索增强生成技术调用AI模型
     * @param imageUrls 图片URL列表
     * @param requestId 请求ID
     * @return 分析结果的JSON字符串
     */
    String callHunyuanModelWithCases(List<String> imageUrls, String requestId) throws Exception;
    
    /**
     * 保存专家修正后的案例
     * @param caseEntity 案例实体
     * @return 保存后的案例
     */
    Case saveExpertCase(Case caseEntity);
    
    /**
     * 获取案例信息
     * @param requestId 请求ID
     * @return 案例信息
     */
    Case getCaseByRequestId(String requestId);
}
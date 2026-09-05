package com.mei.zhgy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public interface AIJsonModificationService {
    
    /**
     * 使用AI大模型修改JSON数据
     * @param originalJson 原始JSON数据
     * @param modificationText 修改文本描述
     * @return 修改后的JSON数据
     */
    String modifyJsonWithAI(String originalJson, String modificationText) throws Exception;
}
package com.mei.zhgy.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.AIJsonModificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class AIJsonModificationServiceImpl implements AIJsonModificationService {
    
    @Value("${hunyuan.api.key:}")
    private String hunyuanApiKey;
    
    @Value("${hunyuan.api.url:https://api.hunyuan.cloud.tencent.com/v1/chat/completions}")
    private String hunyuanApiUrl;

    @Value("${hunyuan.api.text-model:hunyuan-a13b}")
    private String hunyuanTextModel;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 使用AI大模型修改JSON数据
     * @param originalJson 原始JSON数据
     * @param modificationText 修改文本描述
     * @return 修改后的JSON数据
     */
    @Override
    public String modifyJsonWithAI(String originalJson, String modificationText) throws Exception {
        // 构造发送给大模型的提示词
        String prompt = constructPrompt(originalJson, modificationText);
        
        // 调用混元大模型API
        String modifiedJson = callHunyuanAPI(prompt);
        
        return modifiedJson;
    }
    
    /**
     * 构造发送给大模型的提示词
     * @param originalJson 原始JSON
     * @param modificationText 修改文本
     * @return 构造的提示词
     */
    private String constructPrompt(String originalJson, String modificationText) {
        return "你是一个专业的农业专家和JSON数据分析师。请根据以下文本描述修改JSON数据中相关的字段。\n\n" +
                "原始JSON数据:\n" + originalJson + "\n\n" +
                "修改要求:\n" + modificationText + "\n\n" +
                "请仔细分析修改要求，只修改与之相关的JSON字段，保持其他字段不变。\n" +
                "严格按照原始JSON的结构和格式返回修改后的JSON，不要添加任何额外的解释或文本，只返回JSON数据。";
    }
    
    /**
     * 调用混元大模型API
     * @param prompt 提示词
     * @return 大模型返回的结果
     */
    private String callHunyuanAPI(String prompt) throws Exception {
        try {
            RestTemplate restTemplate = new RestTemplate();
            
            // 构造请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + hunyuanApiKey);
            
            // 构造请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", hunyuanTextModel);
            requestBody.put("messages", new Object[]{
                Map.of("role", "user", "content", prompt)
            });
            requestBody.put("temperature", 0.3);
            requestBody.put("top_p", 1.0);
            requestBody.put("stream", false);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            
            // 发送请求
            ResponseEntity<String> response = restTemplate.postForEntity(hunyuanApiUrl, request, String.class);
            
            // 解析响应
            return parseHunyuanResponse(response.getBody());
        } catch (Exception e) {
            log.error("调用混元大模型API失败", e);
            throw new Exception("调用混元大模型API失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析混元大模型的响应
     * @param response 原始响应
     * @return 提取的JSON数据
     */
    private String parseHunyuanResponse(String response) throws Exception {
        try {
            // 解析响应并提取JSON数据
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode choices = rootNode.get("choices");
            
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null) {
                        String contentStr = content.asText();
                        // 尝试提取JSON部分
                        return extractJsonFromContent(contentStr);
                    }
                }
            }
            
            throw new Exception("无法从响应中提取JSON数据");
        } catch (Exception e) {
            log.error("解析混元大模型响应失败", e);
            throw new Exception("解析混元大模型响应失败: " + e.getMessage());
        }
    }
    
    /**
     * 从内容中提取JSON数据
     * @param content 内容字符串
     * @return 提取的JSON数据
     */
    private String extractJsonFromContent(String content) {
        // 查找第一个 { 和最后一个 } 之间的内容
        int startIndex = content.indexOf('{');
        int endIndex = content.lastIndexOf('}');
        
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return content.substring(startIndex, endIndex + 1);
        }
        
        return content; // 如果没找到，返回原内容
    }
}

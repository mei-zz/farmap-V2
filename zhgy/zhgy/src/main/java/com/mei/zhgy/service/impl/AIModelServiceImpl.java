package com.mei.zhgy.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mei.zhgy.entity.InitialResult;
import com.mei.zhgy.entity.UserRequest;
import com.mei.zhgy.mapper.ExpertMapper;
import com.mei.zhgy.mapper.InitialResultMapper;
import com.mei.zhgy.mapper.UserRequestMapper;
import com.mei.zhgy.service.AIModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.Base64;
import java.net.http.*;

@Slf4j
@Service
public class AIModelServiceImpl implements AIModelService {
    
    @Value("${hunyuan.api.key:}")
    private String hunyuanApiKey;

    @Value("${model.api.url:http://tree-analyzer:5000/analyze}")
    private String modelApiUrl;

    @Value("${model.api.key:}")
    private String modelApiKey;
    
    @Autowired
    private UserRequestMapper userRequestMapper;
    
    @Autowired
    private ExpertMapper expertMapper;
    
    @Autowired
    private InitialResultMapper initialResultMapper;
    
    @Value("${hunyuan.api.url:https://api.hunyuan.cloud.tencent.com/v1}")
    private String hunyuanApiUrl;

    private RestTemplate restTemplate;
    
    @PostConstruct
    public void init() {
        this.restTemplate = new RestTemplate();
    }
    
    @Override
    @Transactional
    public InitialResult processAIModelRequest(List<String> imageUrls, String userId) throws Exception {
        log.info("开始处理AI模型请求，用户ID: {}, 图片数量: {}", userId, imageUrls != null ? imageUrls.size() : 0);
        
        try {
            // 1. 保存用户请求信息
            UserRequest userRequest = saveUserRequest(imageUrls, userId);
            log.info("用户请求信息保存完成，请求ID: {}", userRequest.getRequestId());
            
            // 2. 直接调用AI模型进行分析（不使用相似案例增强）
            log.info("开始直接调用腾讯混元AI模型");
            String jsonData = callHunyuanModelDirectly(imageUrls);
            log.info("腾讯混元AI模型调用完成，返回数据长度: {}", jsonData != null ? jsonData.length() : 0);
            
            // 3. 保存初始结果
            InitialResult initialResult = saveInitialResult(userRequest.getRequestId(), jsonData);
            log.info("初始结果保存完成，结果ID: {}", initialResult.getResultId());
            
            // 4. 更新用户请求状态
            userRequest.setStatus(1); // 已生成初始结果
            userRequestMapper.updateStatus(userRequest);
            log.info("用户请求状态更新完成");
            
            return initialResult;
        } catch (Exception e) {
            log.error("处理AI模型请求时发生异常", e);
            throw e;
        }
    }
    
    @Override
    @Transactional
    public InitialResult processAIModelRequest(List<String> imageUrls, String userId, String apiKey) throws Exception {
        log.info("开始处理AI模型请求，用户ID: {}, 图片数量: {}", userId, imageUrls != null ? imageUrls.size() : 0);
        
        try {
            // 1. 保存用户请求信息
            UserRequest userRequest = saveUserRequest(imageUrls, userId);
            log.info("用户请求信息保存完成，请求ID: {}", userRequest.getRequestId());
            
            // 2. 直接调用AI模型进行分析（不使用相似案例增强）
            log.info("开始直接调用腾讯混元AI模型");
            String jsonData = callHunyuanModelDirectly(imageUrls, apiKey);
            log.info("腾讯混元AI模型调用完成，返回数据长度: {}", jsonData != null ? jsonData.length() : 0);
            
            // 3. 保存初始结果
            InitialResult initialResult = saveInitialResult(userRequest.getRequestId(), jsonData);
            log.info("初始结果保存完成，结果ID: {}", initialResult.getResultId());
            
            // 4. 更新用户请求状态
            userRequest.setStatus(1); // 已生成初始结果
            userRequestMapper.updateStatus(userRequest);
            log.info("用户请求状态更新完成");
            
            return initialResult;
        } catch (Exception e) {
            log.error("处理AI模型请求时发生异常", e);
            throw e;
        }
    }
    
    @Override
    @Transactional
    public UserRequest saveUserRequest(List<String> imageUrls, String userId) throws Exception {
        log.info("保存用户请求信息，用户ID: {}, 图片数量: {}", userId, imageUrls != null ? imageUrls.size() : 0);
        
        if (imageUrls == null || imageUrls.isEmpty() || imageUrls.size() > 5) {
            throw new IllegalArgumentException("请提供1-5张图片URL");
        }
        
        // 创建用户请求记录
        UserRequest userRequest = UserRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .userId(userId)
                .imageUrls(String.join(",", imageUrls))
                .imageCount(imageUrls.size())
                .uploadTime(LocalDateTime.now())
                .status(0) // 待处理
                .build();
        
        userRequestMapper.insert(userRequest);
        log.info("用户请求信息保存成功，请求ID: {}", userRequest.getRequestId());
        return userRequest;
    }
    
    @Override
    public String callHunyuanModel(List<String> imageUrls) throws Exception {
        // 直接调用AI模型
        return callHunyuanModelDirectly(imageUrls);
    }

    @Override
    public String callHunyuanModelWithRAG(List<String> imageUrls, String query) throws Exception {
        // 直接调用AI模型，不使用RAG
        return callHunyuanModelDirectly(imageUrls);
    }
    
    @Override
    public String callHunyuanModelWithCases(List<String> imageUrls, String requestId) throws Exception {
        // 直接调用AI模型，不使用案例
        return callHunyuanModelDirectly(imageUrls);
    }
    
    /**
     * 直接调用AI模型分析图片，不使用特征提取和相似案例检索
     * @param imageUrls 图片URL列表
     * @return AI模型返回的JSON结果
     * @throws Exception 调用过程中可能发生的异常
     */
    public String callHunyuanModelDirectly(List<String> imageUrls) throws Exception {
        return callHunyuanModelDirectly(imageUrls, null);
    }
    
    /**
     * 直接调用AI模型分析图片，不使用特征提取和相似案例检索
     * @param imageUrls 图片URL列表
     * @param apiKey API密钥
     * @return AI模型返回的JSON结果
     * @throws Exception 调用过程中可能发生的异常
     */
    public String callHunyuanModelDirectly(List<String> imageUrls, String apiKey) throws Exception {
        log.info("直接调用AI模型分析图片，图片数量: {}", imageUrls.size());

        try {
            // 构造请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("image_urls", imageUrls);

            // 发送请求
            String requestBodyJson = JSONObject.toJSONString(requestBody);
            log.info("发送请求到AI模型API，请求体大小: {} 字节", requestBodyJson.length());

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            // 使用传入的API Key或从配置中获取
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.set("X-API-Key", apiKey);
            } else {
                headers.set("X-API-Key", modelApiKey);
            }

            HttpEntity<String> requestEntity = new HttpEntity<>(requestBodyJson, headers);

            long startTime = System.currentTimeMillis();
            ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                modelApiUrl, requestEntity, String.class);
            long endTime = System.currentTimeMillis();

            log.info("AI模型API调用完成，耗时: {} ms", endTime - startTime);

            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                String responseBody = responseEntity.getBody();
                log.debug("API响应体: {}", responseBody);
                return responseBody;
            } else {
                log.error("API调用失败，状态码: {}，响应体: {}", responseEntity.getStatusCode(), responseEntity.getBody());
                throw new Exception("调用AI模型API失败，状态码: " + responseEntity.getStatusCode());
            }
        } catch (Exception e) {
            log.error("直接调用AI模型时发生异常", e);
            throw e;
        }
    }
    
    /**
     * 将图片转换为Base64编码
     * @param imageUrl 图片URL
     * @return Base64编码的图片数据
     */
    private String encodeImageToBase64(String imageUrl) throws IOException {
        log.info("开始将图片转换为Base64编码，URL: {}", imageUrl);
        
        try {
            // 创建HTTP客户端
            HttpClient httpClient = HttpClient.newHttpClient();
            
            // 构建请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            
            // 发送请求并获取响应
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            
            if (response.statusCode() == 200) {
                byte[] imageBytes = response.body();
                String base64String = Base64.getEncoder().encodeToString(imageBytes);
                log.info("图片转换为Base64编码成功，数据大小: {} 字节", imageBytes.length);
                return base64String;
            } else {
                log.error("获取图片失败，HTTP状态码: {}", response.statusCode());
                throw new IOException("获取图片失败，HTTP状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("图片转换为Base64编码时发生错误: {}", e.getMessage(), e);
            throw new IOException("图片转换失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 调用腾讯混元AI模型
     * @param systemPrompt 系统提示词
     * @param contentList 内容列表
     * @return AI模型返回的JSON结果
     */
    private String callHunyuanModel(String systemPrompt, List<Map<String, Object>> contentList) throws Exception {
        // 构造messages
        List<Map<String, Object>> messages = new ArrayList<>();
        
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);
        messages.add(systemMessage);
        
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", contentList);
        messages.add(userMessage);
        
        // 构造请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "hunyuan-turbo-vision");
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0);
        
        // 发送请求
        String requestBodyJson = JSONObject.toJSONString(requestBody);
        log.info("发送请求到腾讯混元API，请求体大小: {} 字节", requestBodyJson.length());
        
        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + hunyuanApiKey);
        
        HttpEntity<String> requestEntity = new HttpEntity<>(requestBodyJson, headers);
        
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(hunyuanApiUrl, requestEntity, String.class);
        long endTime = System.currentTimeMillis();
        
        log.info("腾讯混元API调用完成，耗时: {} ms", endTime - startTime);
        
        if (responseEntity.getStatusCode().is2xxSuccessful()) {
            String responseBody = responseEntity.getBody();
            log.debug("API响应体: {}", responseBody);
            
            // 解析响应
            JSONObject responseJson = JSONObject.parseObject(responseBody);
            JSONObject choice = responseJson.getJSONArray("choices").getJSONObject(0);
            String resultText = choice.getJSONObject("message").getString("content");
            
            log.debug("原始返回结果：\n{}", resultText);
            
            // 去除前后的代码块标记
            resultText = resultText.trim();
            if (resultText.startsWith("``json")) {
                resultText = resultText.substring(7).trim(); // 去掉 ```json
            }
            if (resultText.endsWith("```")) {
                resultText = resultText.substring(0, resultText.length() - 3).trim(); // 去掉 ```
            }
            
            // 验证是否为有效的JSON
            try {
                JSONObject parsedJson = JSONObject.parseObject(resultText);
                log.info("JSON解析成功");
                return resultText;
            } catch (Exception e) {
                log.error("返回内容不是合法JSON，解析失败：{}\n原始内容：{}", e.getMessage(), resultText);
                throw new Exception("AI模型返回的内容不是有效的JSON格式");
            }
        } else {
            log.error("API调用失败，状态码: {}，响应体: {}", responseEntity.getStatusCode(), responseEntity.getBody());
            throw new Exception("调用腾讯混元API失败，状态码: " + responseEntity.getStatusCode());
        }
    }

    /**
     * 添加JSON模板到用户提示词
     * @param builder StringBuilder对象
     */
    private void appendJsonTemplate(StringBuilder builder) {
        // 构建符合要求的JSON模板，严格按照指定顺序
        JSONObject jsonTemplate = new JSONObject(true); // 使用有序的JSONObject
        
        // 1. 树种识别
        JSONObject treeType = new JSONObject(true);
        treeType.put("种类", "具体树种名称（如'脆李'、'脐橙'）");
        treeType.put("置信度", "高/中/低（基于图像特征判断）");
        jsonTemplate.put("树种识别", treeType);
        
        // 2. 图像质量诊断
        JSONObject imageQuality = new JSONObject(true);
        imageQuality.put("图像完整性", "是否展示关键部位或需要补充拍摄（全树/枝叶/果实）");
        imageQuality.put("图像清晰度", "具体描述（如'叶脉清晰可见'/'果实细节模糊'）");
        imageQuality.put("光照条件", "自然光/逆光/阴影占比等");
        imageQuality.put("拍摄建议", "具体改进建议（如'避开强光时段拍摄'）");
        jsonTemplate.put("图像质量诊断", imageQuality);
        
        // 3. 当前生长阶段
        jsonTemplate.put("当前生长阶段", "典型物候阶段（如'休眠期BBCH[00]'、'萌芽期BBCH[01-07]'、'展叶期BBCH[09-11]'、'花蕾期BBCH[51-59]'、'开花期BBCH[60-69]'、'谢花期BBCH[69-71]'、'幼果期BBCH[71-73]'、'膨大期BBCH[75-79]'、'转色期BBCH[81-83]'、'成熟期BBCH[87-89]'、'采后期BBCH[91-97]'）");
        
        // 4. 长势诊断
        JSONObject growthDiagnosis = new JSONObject(true);
        growthDiagnosis.put("冠层结构", "主枝粗细和通直度、冠幅大小、完整度");
        growthDiagnosis.put("枝条形态", "枝条密度与分布均匀度、枝条分布角度");
        growthDiagnosis.put("新稍生长", "新梢数量、长度和木质化程度");
        growthDiagnosis.put("花芽生长", "花芽数量（过多/适宜/过少），花芽饱满度（高/中/底）");
        growthDiagnosis.put("长势综合判断", "强/中/弱（结合多项指标）");
        jsonTemplate.put("长势诊断", growthDiagnosis);
        
        // 5. 叶部状态诊断
        JSONObject leafDiagnosis = new JSONObject(true);
        leafDiagnosis.put("叶色", "具体颜色描述（如'黄绿色'、'深绿带紫边'）");
        leafDiagnosis.put("叶面积大小", "与标准对比（偏大/正常/偏小）");
        leafDiagnosis.put("叶面病斑比例", "估算百分比（0-100%）");
        leafDiagnosis.put("叶片状态总结", "病斑特征（形状/颜色/分布）");
        jsonTemplate.put("叶部状态诊断", leafDiagnosis);
        
        // 6. 果实状态诊断
        JSONObject fruitDiagnosis = new JSONObject(true);
        fruitDiagnosis.put("挂果量", "稀疏/中等/密集（或估算数量）");
        fruitDiagnosis.put("果实大小", "与品种标准对比");
        fruitDiagnosis.put("果实色泽", "具体描述（如'青绿带红晕'）");
        fruitDiagnosis.put("异常果实比例", "畸形果/病斑果占比");
        jsonTemplate.put("果实状态诊断", fruitDiagnosis);
        
        // 7. 营养状况诊断
        JSONObject nutritionDiagnosis = new JSONObject(true);
        nutritionDiagnosis.put("氮素状态", "根据叶色、新梢生长判断");
        nutritionDiagnosis.put("磷素状态", "根据根系、花芽分化判断");
        nutritionDiagnosis.put("钾素状态", "根据果实品质、抗逆性判断");
        nutritionDiagnosis.put("中微量元素", "具体缺乏元素（如铁、锌）及症状");
        jsonTemplate.put("营养状况诊断", nutritionDiagnosis);
        
        // 8. 病虫害诊断
        JSONObject pestDiagnosis = new JSONObject(true);
        pestDiagnosis.put("疑似病害", "具体病害名称（如'黑星病'、'白粉病'）");
        pestDiagnosis.put("病斑描述", "大小/形状/颜色/病征（如霉层）");
        pestDiagnosis.put("虫害迹象", "虫孔/分泌物/虫体描述");
        pestDiagnosis.put("病害严重度", "分级（轻度<30%/中度30-60%/重度>60%）");
        jsonTemplate.put("病虫害诊断", pestDiagnosis);
        
        // 9. 果叶比与树体评估
        JSONObject fruitLeafRatio = new JSONObject(true);
        fruitLeafRatio.put("可见叶片数估计", "大致数量（如150片）");
        fruitLeafRatio.put("可见果实数估计", "大致数量（如30个）");
        fruitLeafRatio.put("果叶比估计", "比例（如1:15）");
        fruitLeafRatio.put("是否合理", "判断依据（品种标准/树势）");
        jsonTemplate.put("果叶比与树体评估", fruitLeafRatio);
        
        // 10. 综合建议
        JSONObject comprehensiveAdvice = new JSONObject(true);
        comprehensiveAdvice.put("施肥建议", "肥料类型/用量/施用时间");
        comprehensiveAdvice.put("病害处理建议", "药剂名称/浓度/施用频率");
        comprehensiveAdvice.put("树势提升建议", "修剪方案/土壤改良措施");
        comprehensiveAdvice.put("补充说明", "需注意事项/后续观察要点");
        jsonTemplate.put("综合建议", comprehensiveAdvice);
        
        builder.append("输出格式模板：\n").append(jsonTemplate.toJSONString());
    }
    
    @Override
    public InitialResult saveInitialResult(String requestId, String jsonData) {
        log.info("保存初始结果，请求ID: {}", requestId);
        
        InitialResult initialResult = InitialResult.builder()
                .resultId(UUID.randomUUID().toString())
                .requestId(requestId)
                .jsonData(jsonData)
                .generateTime(LocalDateTime.now())
                .modelVersion("hunyuan-turbo-vision") // 添加模型版本信息
                .build();
        
        initialResultMapper.insert(initialResult);
        log.info("初始结果保存成功，结果ID: {}", initialResult.getResultId());
        
        return initialResult;
    }

    @Override
    public com.mei.zhgy.entity.Case saveExpertCase(com.mei.zhgy.entity.Case caseEntity) {
        // 空实现
        return caseEntity;
    }

    @Override
    public com.mei.zhgy.entity.Case getCaseByRequestId(String requestId) {
        // 空实现
        return null;
    }
}

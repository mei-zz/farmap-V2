package com.mei.zhgy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.constant.JwtClaimsConstant;
import com.mei.zhgy.dto.JsonModificationRequest;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.AIJsonModificationService;
import com.mei.zhgy.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/ai-json")
@Api(tags = "AI JSON修改接口")
public class AIJsonModificationController {
    
    @Autowired
    private AIJsonModificationService aiJsonModificationService;
    
    @Value("${zhgy.jwt.user-secret-key}")
    private String userSecretKey;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @PostMapping("/modify")
    @ApiOperation(value = "使用AI大模型修改JSON数据")
    public Result<Object> modifyJson(
            @RequestBody JsonModificationRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("接收到JSON修改请求");
        
        // 验证JWT令牌
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return Result.error("未提供有效的认证令牌");
            }
            
            String token = authorization.substring(7);
            Claims claims = JwtUtil.parseJWT(userSecretKey, token);
            Integer userId = Integer.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            String role = claims.get(JwtClaimsConstant.ROLE).toString();
            
            log.info("用户认证成功，用户ID: {}, 角色: {}", userId, role);
        } catch (Exception e) {
            log.warn("JWT令牌验证失败: {}", e.getMessage());
            return Result.error("认证失败: 无效的令牌");
        }
        
        try {
            // 将JSON数据转换为字符串
            String originalJson = objectMapper.writeValueAsString(request.getJsonData());
            
            // 调用AI服务修改JSON
            String modifiedJson = aiJsonModificationService.modifyJsonWithAI(
                originalJson, 
                request.getModificationText()
            );
            
            // 将修改后的JSON字符串转换为对象
            Object result = objectMapper.readValue(modifiedJson, Object.class);
            
            log.info("JSON修改成功");
            return Result.success(result);
        } catch (Exception e) {
            log.error("JSON修改失败", e);
            return Result.error("JSON修改失败: " + e.getMessage());
        }
    }
}
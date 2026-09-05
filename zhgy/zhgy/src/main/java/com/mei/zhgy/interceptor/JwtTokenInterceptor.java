package com.mei.zhgy.interceptor;

import com.mei.zhgy.constant.JwtClaimsConstant;
import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.properties.JwtProperties;
import com.mei.zhgy.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * jwt令牌校验的拦截器
 */
@Component
@Slf4j
public class JwtTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 校验jwt
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断当前拦截到的是Controller的方法还是其他资源
        if (!(handler instanceof HandlerMethod)) {
            //当前拦截到的不是动态方法，直接放行
            log.info("拦截到的不是Controller方法，直接放行");
            return true;
        }

        //记录请求信息
        log.info("拦截到请求 - URL: {}, Method: {}", request.getRequestURL(), request.getMethod());
        
        //1、从请求头中获取令牌
        // 先尝试获取authentication header (用于AI模型接口)
        String token = request.getHeader("authentication");
        log.info("从authentication header获取token: {}", token == null || token.isEmpty() ? "ABSENT" : "PRESENT");
        
        // 如果authentication header不存在，则尝试获取Authorization header (用于其他接口)
        if (token == null || token.isEmpty()) {
            token = request.getHeader("Authorization");
            log.info("从Authorization header获取token: {}", token == null || token.isEmpty() ? "ABSENT" : "PRESENT");
        }
        
        // 如果是Bearer格式，需要去掉前缀
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            log.info("处理Bearer格式token，已提取令牌内容");
        }

        //2、校验令牌
        try {
            log.info("开始jwt校验，令牌状态: {}", token == null || token.isEmpty() ? "ABSENT" : "PRESENT");
            if (token == null || token.isEmpty()) {
                log.warn("令牌为空，拒绝访问");
                response.setStatus(401);
                try {
                    response.setContentType("application/json;charset=utf-8");
                    response.getWriter().write("{\"code\": 0, \"msg\": \"Token is empty\"}");
                } catch (Exception writeEx) {
                    log.error("写入响应失败: {}", writeEx.getMessage());
                }
                return false;
            }
            
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            Integer userId = Integer.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            String role = claims.get(JwtClaimsConstant.ROLE).toString();

            log.info("当前用户id：{}, 角色: {}", userId, role);
            BaseContext.setCurrentId((long) userId);
            //3、通过，放行
            log.info("jwt校验通过，放行请求");
            return true;
        } catch (Exception ex) {
            log.error("jwt校验失败: {}", ex.getMessage());
            //4、不通过，响应401状态码和错误信息
            response.setStatus(401);
            try {
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"code\": 0, \"msg\": \"Token validation failed: " + ex.getMessage() + "\"}");
            } catch (Exception writeEx) {
                log.error("写入响应失败: {}", writeEx.getMessage());
            }
            return false;
        }
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理ThreadLocal，防止内存泄漏
        BaseContext.removeCurrentId();
        log.info("请求处理完成，清理ThreadLocal");
    }
}

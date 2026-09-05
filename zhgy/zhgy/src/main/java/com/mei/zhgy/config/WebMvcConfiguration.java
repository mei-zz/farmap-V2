package com.mei.zhgy.config;

import com.mei.zhgy.interceptor.JwtTokenInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Slf4j
public class WebMvcConfiguration implements WebMvcConfigurer {
    
    @Autowired
    private JwtTokenInterceptor jwtTokenInterceptor;

    @Value("${zhgy.upload.dir:./uploads}")
    private String uploadDir;
    
    /**
     * 注册自定义拦截器
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        registry.addInterceptor(jwtTokenInterceptor)
                .addPathPatterns("/ai-model/**") // 拦截AI模型相关路径
                .addPathPatterns("/farm/**")     // 拦截农场相关路径
                .addPathPatterns("/user/**")     // 拦截用户相关路径
                .addPathPatterns("/expert/**")   // 拦截专家相关路径
                .addPathPatterns("/weather/**")  // 拦截天气相关路径
                .addPathPatterns("/diagnosis/**") // 拦截结构化多模态诊断
                .excludePathPatterns("/user/login")  // 放行登录接口
                .excludePathPatterns("/user/register")  // 放行注册接口
                .excludePathPatterns("/user/validate-token")  // 放行token验证接口
                .excludePathPatterns("/doc.html")  // 放行Swagger文档接口
                .excludePathPatterns("/webjars/**")  // 放行Swagger静态资源
                .excludePathPatterns("/swagger-resources/**")  // 放行Swagger资源
                .excludePathPatterns("/v2/api-docs");  // 放行Swagger API文档
        log.info("自定义拦截器注册完成");
    }
    
    /**
     * 配置跨域支持
     * @param registry 跨域注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("开始注册跨域配置...");
        registry.addMapping("/**") // 所有路径都支持跨域
                .allowedOriginPatterns("*") // 允许所有来源
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的请求方法
                .allowedHeaders("*") // 允许所有请求头
                .allowCredentials(true) // 允许携带凭证
                .maxAge(3600); // 预检请求的有效期（秒）
        log.info("跨域配置注册完成");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + new java.io.File(uploadDir).getAbsolutePath() + java.io.File.separator;
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}

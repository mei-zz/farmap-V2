package com.mei.zhgy.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class MilvusConfig {
    
    @Value("${milvus.host:129.211.214.104}")
    private String host;
    
    @Value("${milvus.port:24004}")
    private Integer port;
    
    @Value("${milvus.username:}")
    private String username;
    
    @Value("${milvus.password:}")
    private String password;
    
    @Value("${milvus.connect-timeout-ms:30000}")
    private Integer connectTimeoutMs;
    
    @Value("${milvus.keep-alive-time-ms:30000}")
    private Integer keepAliveTimeMs;
    
    @Value("${milvus.idle-timeout-ms:30000}")
    private Integer idleTimeoutMs;
    
    @Value("${milvus.rpc-deadline-ms:30000}")
    private Long rpcDeadlineMs;
    
    @Bean
    public MilvusServiceClient milvusClient() {
        try {
            log.info("初始化Milvus客户端，host: {}, port: {}", host, port);
            
            ConnectParam.Builder builder = ConnectParam.newBuilder()
                    .withHost(host)
                    .withPort(port)
                    .withConnectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                    .withKeepAliveTime(keepAliveTimeMs, TimeUnit.MILLISECONDS)
                    .withKeepAliveTimeout(idleTimeoutMs, TimeUnit.MILLISECONDS)
                    .withRpcDeadline(rpcDeadlineMs, TimeUnit.MILLISECONDS);
            
            // 如果配置了用户名和密码，则添加认证信息
            if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
                builder.withAuthorization(username, password);
                log.info("Milvus客户端启用认证，用户名: {}", username);
            }
            
            ConnectParam connectParam = builder.build();
            MilvusServiceClient client = new MilvusServiceClient(connectParam);
            
            // 注：不在此处调用 client.getVersion() 做连通性测试。
            // 原实现在连接失败时返回 null，而 HealthController/AIModelService 等以
            // @Autowired(required=true) 注入本 Bean，null 会触发 NoSuchBeanDefinition 导致整个应用启动失败。
            // MilvusServiceClient 为懒连接：构造时不建连，首次 RPC 时才真正连接。
            // 故去掉启动期测试，让应用在 Milvus 暂不可用时也能启动；AI 相关功能在真正调用时再连。
            log.info("MilvusServiceClient 已创建，连接将在首次调用时建立");
            return client;
        } catch (Exception e) {
            log.error("Milvus客户端连接失败: {}", e.getMessage());
            log.warn("Milvus客户端连接失败，应用将继续运行但AI相关功能可能受限");
            // 返回null而不是抛出异常，让应用能够启动
            return null;
        }
    }
}
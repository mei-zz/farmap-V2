package com.mei.zhgy.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.concurrent.TimeUnit;

/**
 * MongoDB配置类
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "spring.data.mongodb")
public class MongoConfig {
    
    private String host = "129.211.214.104";
    private int port = 27017;
    private String database = "zhgy";
    private String username;
    private String password;
    private String authenticationDatabase = "admin";
    
    @Bean
    public MongoClient mongoClient() {
        try {
            log.info("初始化MongoDB客户端，连接地址: {}:{}", host, port);
            
            // 构建连接字符串
            String connectionString;
            if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
                connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s", 
                        username, password, host, port, database, authenticationDatabase);
                log.info("使用认证信息连接MongoDB，用户名: {}, 数据库: {}, 认证数据库: {}", username, database, authenticationDatabase);
            } else {
                connectionString = String.format("mongodb://%s:%d/%s", host, port, database);
            }
            
            ConnectionString connString = new ConnectionString(connectionString);
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connString)
                    .applyToSocketSettings(builder -> {
                        builder.connectTimeout(30000, TimeUnit.MILLISECONDS);
                        builder.readTimeout(60000, TimeUnit.MILLISECONDS);
                    })
                    .applyToClusterSettings(builder -> {
                        builder.serverSelectionTimeout(120000, TimeUnit.MILLISECONDS);
                    })
                    .build();
            
            MongoClient client = MongoClients.create(settings);
            log.info("MongoDB客户端初始化完成");
            return client;
        } catch (Exception e) {
            log.error("MongoDB客户端初始化失败", e);
            throw new RuntimeException("MongoDB客户端初始化失败", e);
        }
    }
    
    @Bean
    public MongoTemplate mongoTemplate() {
        return new MongoTemplate(mongoClient(), database);
    }
}
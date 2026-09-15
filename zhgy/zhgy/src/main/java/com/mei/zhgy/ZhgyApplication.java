package com.mei.zhgy;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@MapperScan("com.mei.zhgy.mapper")
@EnableScheduling
public class ZhgyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhgyApplication.class, args);
        log.info("智慧果园应用启动成功!");
    }

}
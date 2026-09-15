package com.mei.zhgy.service.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationReadinessVerifierMysqlTest {
    @Test
    void verifiesTheInstalledProductLoopSchemaWithoutMutatingIt() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")),
                "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        ds.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        var report = new MigrationReadinessVerifier(ds).verify();
        assertEquals("READY", report.get("status"), report.toString());
    }
}

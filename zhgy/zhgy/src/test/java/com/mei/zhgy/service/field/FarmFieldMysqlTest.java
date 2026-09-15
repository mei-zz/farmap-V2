package com.mei.zhgy.service.field;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmFieldMysqlTest {
    @Test
    void fieldMasterHasStableIdentityAndUniqueFarmNameBoundary() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")), "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        ds.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String fieldId = "field-test-" + UUID.randomUUID();
        try {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='farm_fields'", Integer.class));
            jdbc.update("INSERT INTO farm_fields(field_id,farm_id,name,status,source_mode,created_at,updated_at) VALUES (?,?,?,'ACTIVE','MYSQL',NOW(3),NOW(3))", fieldId, 1, "test-" + fieldId);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM farm_fields WHERE field_id=?", Integer.class, fieldId));
        } finally {
            jdbc.update("DELETE FROM farm_fields WHERE field_id=?", fieldId);
        }
    }
}

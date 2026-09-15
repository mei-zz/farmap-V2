package com.mei.zhgy.service.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMysqlPersistenceTest {
    @Test void persistenceModeIsExplicit() {
        String mode = System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file");
        assertEquals(true, "mysql".equals(mode) || "file".equals(mode));
    }

    @Test void mysqlCreateRefreshAndRestartWhenEnabled() {
        String mode = System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file");
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(mode), "run with FARMAP_AGENT_PERSISTENCE=mysql");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcAgentRunRepository repository = new JdbcAgentRunRepository(jdbc);
        repository.ensureTable();
        String runId = "mysql-test-" + UUID.randomUUID();
        Instant now = Instant.now();
        try {
            AgentRun run = AgentRun.builder().runId(runId).goal("mysql persistence test").mode("analysis")
                    .status(RunStatus.CREATED).createdAt(now).updatedAt(now).build();
            repository.save(run);
            assertEquals(RunStatus.CREATED, repository.find(runId).orElseThrow().getStatus());

            run.setStatus(RunStatus.COMPLETED);
            run.setUpdatedAt(Instant.now());
            repository.save(run);

            JdbcAgentRunRepository restartedRepository = new JdbcAgentRunRepository(new JdbcTemplate(dataSource));
            assertEquals(RunStatus.COMPLETED, restartedRepository.find(runId).orElseThrow().getStatus());
        } finally {
            jdbc.update("DELETE FROM agent_runs WHERE run_id=?", runId);
        }
    }
}

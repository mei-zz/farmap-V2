package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Optional MySQL persistence using the existing datasource; enabled with FARMAP_AGENT_PERSISTENCE=mysql. */
@Repository
@ConditionalOnProperty(name = "farmap.agent.persistence", havingValue = "mysql")
public class JdbcAgentRunRepository implements AgentRunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    public JdbcAgentRunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_runs (run_id VARCHAR(96) PRIMARY KEY, payload LONGTEXT NOT NULL, created_at TIMESTAMP(3) NULL, updated_at TIMESTAMP(3) NULL)");
    }

    @Override public AgentRun save(AgentRun run) {
        try {
            String payload = mapper.writeValueAsString(run);
            jdbc.update("INSERT INTO agent_runs(run_id,payload,created_at,updated_at) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload),updated_at=VALUES(updated_at)", run.getRunId(), payload, timestamp(run.getCreatedAt()), timestamp(run.getUpdatedAt()));
            return run;
        } catch (Exception error) { throw new IllegalStateException("Agent run persistence failed", error); }
    }

    @Override public Optional<AgentRun> find(String runId) {
        List<AgentRun> result = jdbc.query("SELECT payload FROM agent_runs WHERE run_id=?", (rs, row) -> readPayload(rs.getString(1)), runId);
        return result.stream().findFirst();
    }
    @Override public List<AgentRun> findAll() { return jdbc.query("SELECT payload FROM agent_runs ORDER BY created_at DESC", (rs, row) -> readPayload(rs.getString(1))); }
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private AgentRun readPayload(String payload) {
        try { return mapper.readValue(payload, AgentRun.class); }
        catch (Exception error) { throw new IllegalStateException("Invalid persisted agent run", error); }
    }
}

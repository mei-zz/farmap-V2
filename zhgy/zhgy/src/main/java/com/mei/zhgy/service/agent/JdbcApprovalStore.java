package com.mei.zhgy.service.agent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

/** MySQL-backed approval store. Database errors are propagated as fail-closed errors. */
public class JdbcApprovalStore implements ApprovalStore {
    private final DataSource dataSource;
    private volatile boolean schemaChecked;

    public JdbcApprovalStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Decision decide(String runId, String actionId, ApprovalState state, String actorId, Instant decidedAt) {
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO agent_approvals (run_id,action_id,decision_state,execution_state,actor_id,decided_at) VALUES (?,?,?,?,?,?)")) {
                insert.setString(1, runId); insert.setString(2, actionId); insert.setString(3, state.name());
                insert.setString(4, state == ApprovalState.APPROVED ? ApprovalExecutionState.PENDING.name() : ApprovalExecutionState.NONE.name());
                insert.setString(5, actorId); insert.setTimestamp(6, timestamp(decidedAt));
                insert.executeUpdate();
                connection.commit();
                return new Decision(ApprovalRecord.builder().runId(runId).actionId(actionId).decision(state)
                        .executionState(state == ApprovalState.APPROVED ? ApprovalExecutionState.PENDING : ApprovalExecutionState.NONE)
                        .actorId(actorId).decidedAt(decidedAt).build(), true);
            } catch (SQLException error) {
                rollback(connection);
                if (!isDuplicate(error)) throw ApprovalException.persistenceUnavailable(runId, actionId, error);
                ApprovalRecord existing = read(connection, runId, actionId);
                if (existing == null) throw ApprovalException.persistenceUnavailable(runId, actionId, error);
                if (existing.getDecision() != state) throw ApprovalException.conflict(runId, actionId, "动作已经有相反的审批决定");
                return new Decision(existing, false);
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        } catch (ApprovalException error) {
            throw error;
        } catch (Exception error) {
            throw ApprovalException.persistenceUnavailable(runId, actionId, error);
        }
    }

    @Override
    public ApprovalRecord find(String runId, String actionId) {
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            return read(connection, runId, actionId);
        } catch (Exception error) {
            throw ApprovalException.persistenceUnavailable(runId, actionId, error);
        }
    }

    @Override
    public boolean claimExecution(String runId, String actionId) {
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE agent_approvals SET execution_state='EXECUTING' WHERE run_id=? AND action_id=? AND decision_state='APPROVED' AND execution_state='PENDING'")) {
                statement.setString(1, runId); statement.setString(2, actionId);
                return statement.executeUpdate() == 1;
            }
        } catch (Exception error) {
            throw ApprovalException.persistenceUnavailable(runId, actionId, error);
        }
    }

    @Override
    public void completeExecution(String runId, String actionId, String taskId) {
        updateExecution(runId, actionId, "COMPLETED", taskId, null, null);
    }

    @Override
    public void failExecution(String runId, String actionId, String failureCode, String failureMessage) {
        updateExecution(runId, actionId, "FAILED", null, failureCode, failureMessage);
    }

    private void updateExecution(String runId, String actionId, String state, String taskId, String failureCode, String failureMessage) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE agent_approvals SET execution_state=?,task_id=?,failure_code=?,failure_message=? WHERE run_id=? AND action_id=?")) {
            ensureSchema(connection);
            statement.setString(1, state); statement.setString(2, taskId); statement.setString(3, failureCode); statement.setString(4, failureMessage);
            statement.setString(5, runId); statement.setString(6, actionId);
            if (statement.executeUpdate() != 1) throw ApprovalException.actionNotFound(runId, actionId);
        } catch (ApprovalException error) {
            throw error;
        } catch (Exception error) {
            throw ApprovalException.persistenceUnavailable(runId, actionId, error);
        }
    }

    private ApprovalRecord read(Connection connection, String runId, String actionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT run_id,action_id,decision_state,execution_state,actor_id,decided_at,task_id,failure_code,failure_message FROM agent_approvals WHERE run_id=? AND action_id=?")) {
            statement.setString(1, runId); statement.setString(2, actionId);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? row(rs) : null; }
        }
    }

    private ApprovalRecord row(ResultSet rs) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return ApprovalRecord.builder().runId(rs.getString("run_id")).actionId(rs.getString("action_id"))
                .decision(ApprovalState.valueOf(rs.getString("decision_state")))
                .executionState(ApprovalExecutionState.valueOf(rs.getString("execution_state")))
                .actorId(rs.getString("actor_id")).decidedAt(decidedAt == null ? null : decidedAt.toInstant())
                .taskId(rs.getString("task_id")).failureCode(rs.getString("failure_code")).failureMessage(rs.getString("failure_message")).build();
    }

    private void ensureSchema(Connection connection) throws SQLException {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS agent_approvals (run_id VARCHAR(128) NOT NULL,action_id VARCHAR(180) NOT NULL,decision_state VARCHAR(32) NOT NULL,execution_state VARCHAR(32) NOT NULL,actor_id VARCHAR(128),decided_at TIMESTAMP(3) NULL,task_id VARCHAR(128),failure_code VARCHAR(64),failure_message TEXT,PRIMARY KEY (run_id,action_id))");
                schemaChecked = true;
            }
        }
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private void rollback(Connection connection) { try { connection.rollback(); } catch (SQLException ignored) { } }
    private boolean isDuplicate(SQLException error) {
        SQLException current = error;
        while (current != null) {
            if ("23000".equals(current.getSQLState()) || current.getErrorCode() == 1062) return true;
            current = current.getNextException();
        }
        return false;
    }
}

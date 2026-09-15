package com.mei.zhgy.service.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;

@Service
public class ApprovalManager {
    private final ApprovalStore store;

    /** Direct construction remains available for deterministic unit tests only. */
    public ApprovalManager() { this.store = new InMemoryApprovalStore(); }

    public ApprovalManager(ApprovalStore store) { this.store = store; }

    /** Spring production path always uses the durable store or fails closed when no datasource exists. */
    @Autowired
    public ApprovalManager(DataSource dataSource) {
        this.store = dataSource == null ? new FailClosedApprovalStore() : new JdbcApprovalStore(dataSource);
    }

    public Decision approveDecision(AgentRun run, String actionId) {
        ProposedAction action = action(run, actionId);
        ApprovalRecord existing = store.find(run.getRunId(), actionId);
        if (action.getApprovalState() == ApprovalState.REJECTED) throw ApprovalException.alreadyRejected(run.getRunId(), actionId);
        if (action.getApprovalState() == ApprovalState.APPROVED && existing == null) throw ApprovalException.alreadyApproved(run.getRunId(), actionId);
        if (action.getApprovalState() == ApprovalState.REQUIRED && terminal(run)) throw ApprovalException.invalidState(run.getRunId(), actionId, "已完成、失败或取消的运行不能批准动作");
        if (action.getApprovalState() != ApprovalState.REQUIRED && action.getApprovalState() != ApprovalState.APPROVED) {
            throw ApprovalException.invalidState(run.getRunId(), actionId, "当前动作状态不允许批准");
        }
        ApprovalStore.Decision decision = store.decide(run.getRunId(), actionId, ApprovalState.APPROVED, "current-user", Instant.now());
        action.setApprovalState(ApprovalState.APPROVED);
        run.setApprovalState(ApprovalState.APPROVED);
        return new Decision(action, decision.record(), decision.created());
    }

    public ProposedAction approve(AgentRun run, String actionId) {
        return approveDecision(run, actionId).action();
    }

    public Decision rejectDecision(AgentRun run, String actionId) {
        ProposedAction action = action(run, actionId);
        ApprovalRecord existing = store.find(run.getRunId(), actionId);
        if (action.getApprovalState() == ApprovalState.APPROVED || (existing != null && existing.getDecision() == ApprovalState.APPROVED)) {
            throw ApprovalException.alreadyApproved(run.getRunId(), actionId);
        }
        if (action.getApprovalState() == ApprovalState.REJECTED && existing == null) throw ApprovalException.alreadyRejected(run.getRunId(), actionId);
        if (action.getApprovalState() == ApprovalState.REQUIRED && terminal(run)) throw ApprovalException.invalidState(run.getRunId(), actionId, "已完成、失败或取消的运行不能拒绝动作");
        if (action.getApprovalState() != ApprovalState.REQUIRED && action.getApprovalState() != ApprovalState.REJECTED) {
            throw ApprovalException.invalidState(run.getRunId(), actionId, "当前动作状态不允许拒绝");
        }
        ApprovalStore.Decision decision = store.decide(run.getRunId(), actionId, ApprovalState.REJECTED, "current-user", Instant.now());
        action.setApprovalState(ApprovalState.REJECTED);
        run.setApprovalState(ApprovalState.REJECTED);
        return new Decision(action, decision.record(), decision.created());
    }

    public ProposedAction reject(AgentRun run, String actionId) {
        return rejectDecision(run, actionId).action();
    }

    public boolean claimExecution(String runId, String actionId) { return store.claimExecution(runId, actionId); }
    public void completeExecution(String runId, String actionId, String taskId) { store.completeExecution(runId, actionId, taskId); }
    public void failExecution(String runId, String actionId, String code, String message) { store.failExecution(runId, actionId, code, message); }
    public ApprovalRecord find(String runId, String actionId) { return store.find(runId, actionId); }

    private ProposedAction action(AgentRun run, String actionId) {
        if (run == null || run.getActions() == null) throw ApprovalException.actionNotFound(run == null ? null : run.getRunId(), actionId);
        return run.getActions().stream().filter(item -> item != null && actionId != null && actionId.equals(item.getId())).findFirst()
                .orElseThrow(() -> ApprovalException.actionNotFound(run.getRunId(), actionId));
    }

    private boolean terminal(AgentRun run) {
        return run != null && (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED || run.getStatus() == RunStatus.CANCELLED);
    }

    public record Decision(ProposedAction action, ApprovalRecord record, boolean created) {
        public boolean shouldAttemptExecution() {
            return record != null && record.getDecision() == ApprovalState.APPROVED && record.getExecutionState() == ApprovalExecutionState.PENDING;
        }
    }

    /** Production path without a datasource never acknowledges an approval write. */
    private static final class FailClosedApprovalStore implements ApprovalStore {
        @Override public Decision decide(String runId, String actionId, ApprovalState state, String actorId, Instant decidedAt) { throw ApprovalException.persistenceUnavailable(runId, actionId, null); }
        @Override public ApprovalRecord find(String runId, String actionId) { throw ApprovalException.persistenceUnavailable(runId, actionId, null); }
        @Override public boolean claimExecution(String runId, String actionId) { throw ApprovalException.persistenceUnavailable(runId, actionId, null); }
        @Override public void completeExecution(String runId, String actionId, String taskId) { throw ApprovalException.persistenceUnavailable(runId, actionId, null); }
        @Override public void failExecution(String runId, String actionId, String code, String message) { throw ApprovalException.persistenceUnavailable(runId, actionId, null); }
    }
}

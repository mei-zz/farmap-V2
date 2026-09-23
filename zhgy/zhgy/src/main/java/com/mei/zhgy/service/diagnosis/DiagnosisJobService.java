package com.mei.zhgy.service.diagnosis;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.rag.DiagnosisRagService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs the expensive real Diagnosis RAG flow outside the HTTP request thread.
 * The completed Diagnosis is still written through DiagnosisPersistenceService,
 * so this changes delivery semantics without changing product provenance.
 */
@Service
@Slf4j
public class DiagnosisJobService {
    private final DiagnosisRagService diagnosisRagService;
    private final DiagnosisPersistenceService diagnosisPersistenceService;
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    @Autowired
    public DiagnosisJobService(DiagnosisRagService diagnosisRagService,
                               DiagnosisPersistenceService diagnosisPersistenceService,
                               @Value("${farmap.diagnosis.max-concurrent-jobs:2}") int maxConcurrentJobs,
                               @Value("${farmap.diagnosis.queue-capacity:8}") int queueCapacity) {
        this.diagnosisRagService = diagnosisRagService;
        this.diagnosisPersistenceService = diagnosisPersistenceService;
        this.executor = createExecutor(maxConcurrentJobs, queueCapacity);
    }

    public DiagnosisJobResponse submit(DiagnosisAnalyzeRequest request, Long userId) {
        if (request == null) throw new IllegalArgumentException("诊断请求不能为空");
        cleanupTerminalJobs();
        String jobId = "diagnosis-job-" + UUID.randomUUID();
        JobState state = new JobState(jobId, userId, Instant.now());
        jobs.put(jobId, state);
        try {
            executor.submit(() -> execute(state, request));
        } catch (RejectedExecutionException error) {
            state.fail("当前诊断任务较多，请稍后重试。", "DIAGNOSIS_QUEUE_FULL");
            log.warn("DIAGNOSIS_JOB_QUEUE_FULL jobId={} ownerUserId={}", jobId, userId);
        }
        return state.snapshot();
    }

    private ExecutorService createExecutor(int concurrency, int capacity) {
        int safeConcurrency = Math.max(1, Math.min(concurrency, 16));
        int safeCapacity = Math.max(1, Math.min(capacity, 128));
        return new ThreadPoolExecutor(safeConcurrency, safeConcurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(safeCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "farmap-diagnosis");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public DiagnosisJobResponse get(String jobId, Long userId) {
        JobState state = jobs.get(jobId);
        if (state == null || (state.ownerUserId != null && !state.ownerUserId.equals(userId))) {
            return null;
        }
        return state.snapshot();
    }

    private void execute(JobState state, DiagnosisAnalyzeRequest request) {
        state.update("RUNNING", "RAG", "正在执行真实 RAG 检索与模型生成…");
        try {
            DiagnosisAnalysisResponse analyzed = diagnosisRagService.analyze(request, state.ownerUserId);
            state.update("RUNNING", "PERSISTING", "Diagnosis 已生成，正在保存稳定身份…");
            DiagnosisAnalysisResponse persisted = diagnosisPersistenceService.persist(analyzed, request, state.ownerUserId);
            state.complete(persisted);
            log.info("DIAGNOSIS_JOB_COMPLETED jobId={} diagnosisId={} sourceRunId={} ownerUserId={}",
                    state.jobId, persisted.getDiagnosisId(), persisted.getSourceRunId(), state.ownerUserId);
        } catch (KnowledgeBackendException exception) {
            state.fail("知识后端暂不可用，请检查 Milvus 与知识库服务后重试。");
            log.error("DIAGNOSIS_JOB_KNOWLEDGE_FAILED jobId={}: {}", state.jobId, exception.getMessage());
        } catch (DiagnosisPersistenceException exception) {
            state.fail("诊断已完成，但保存稳定身份失败，请检查 MySQL 后重试。");
            log.error("DIAGNOSIS_JOB_PERSISTENCE_FAILED jobId={}: {}", state.jobId, exception.getMessage(), exception);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            state.fail("实时分析暂不可用：" + exception.getMessage());
            log.warn("DIAGNOSIS_JOB_VALIDATION_FAILED jobId={}: {}", state.jobId, exception.getMessage());
        } catch (Exception exception) {
            state.fail("实时分析暂不可用，请稍后重试。");
            log.error("DIAGNOSIS_JOB_FAILED jobId={}", state.jobId, exception);
        }
    }

    private void cleanupTerminalJobs() {
        if (jobs.size() < 200) return;
        jobs.values().stream()
                .filter(JobState::terminal)
                .sorted(Comparator.comparing(state -> state.updatedAt))
                .limit(Math.max(1, jobs.size() - 160L))
                .map(state -> state.jobId)
                .forEach(jobs::remove);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @Data
    public static class DiagnosisJobResponse {
        private String jobId;
        private String status;
        private String stage;
        private String message;
        private String diagnosisId;
        private String sourceRunId;
        private DiagnosisAnalysisResponse result;
        private String error;
        private Instant createdAt;
        private Instant updatedAt;
    }

    private static final class JobState {
        private final String jobId;
        private final Long ownerUserId;
        private final Instant createdAt;
        private volatile String status = "QUEUED";
        private volatile String stage = "QUEUED";
        private volatile String message = "任务已接收，等待后台执行…";
        private volatile String diagnosisId;
        private volatile String sourceRunId;
        private volatile DiagnosisAnalysisResponse result;
        private volatile String error;
        private volatile Instant updatedAt;

        private JobState(String jobId, Long ownerUserId, Instant createdAt) {
            this.jobId = jobId;
            this.ownerUserId = ownerUserId;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        private synchronized void update(String status, String stage, String message) {
            this.status = status;
            this.stage = stage;
            this.message = message;
            this.updatedAt = Instant.now();
        }

        private synchronized void complete(DiagnosisAnalysisResponse result) {
            this.status = "COMPLETED";
            this.stage = "COMPLETED";
            this.message = "Diagnosis 已完成，可以创建 Agent Run。";
            this.result = result;
            this.diagnosisId = result == null ? null : result.getDiagnosisId();
            this.sourceRunId = result == null ? null : result.getSourceRunId();
            this.updatedAt = Instant.now();
        }

        private synchronized void fail(String message) {
            fail(message, null);
        }

        private synchronized void fail(String message, String errorCode) {
            this.status = "FAILED";
            this.stage = "FAILED";
            this.message = message;
            this.error = errorCode == null || errorCode.isBlank() ? message : errorCode + ": " + message;
            this.updatedAt = Instant.now();
        }

        private boolean terminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        }

        private synchronized DiagnosisJobResponse snapshot() {
            DiagnosisJobResponse response = new DiagnosisJobResponse();
            response.jobId = jobId;
            response.status = status;
            response.stage = stage;
            response.message = message;
            response.diagnosisId = diagnosisId;
            response.sourceRunId = sourceRunId;
            response.result = result;
            response.error = error;
            response.createdAt = createdAt;
            response.updatedAt = updatedAt;
            return response;
        }
    }
}

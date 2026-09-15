package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.rag.DiagnosisRagService;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceException;
import com.mei.zhgy.service.diagnosis.DiagnosisJobService;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.List;

/** 结构化多模态诊断入口；旧 /ai-model 接口保持不变。 */
@RestController
@RequestMapping("/diagnosis")
@Slf4j
public class DiagnosisController {
    private final DiagnosisRagService diagnosisRagService;
    private final DiagnosisPersistenceService diagnosisPersistenceService;
    private final DiagnosisJobService diagnosisJobService;
    private final FarmFieldService farmFieldService;
    @Value("${zhgy.upload.base-url:http://localhost:24009/uploads}")
    private String uploadBaseUrl;

    @Value("${tencent-cos.public-base-url:}")
    private String cosPublicBaseUrl;

    @Value("${tencent-cos.bucket:}")
    private String cosBucket;

    @Value("${tencent-cos.region:}")
    private String cosRegion;

    public DiagnosisController(DiagnosisRagService diagnosisRagService) {
        this(diagnosisRagService, new DiagnosisPersistenceService(), null, null);
    }

    public DiagnosisController(DiagnosisRagService diagnosisRagService, DiagnosisPersistenceService diagnosisPersistenceService) {
        this(diagnosisRagService, diagnosisPersistenceService, null, null);
    }

    @Autowired
    public DiagnosisController(DiagnosisRagService diagnosisRagService, DiagnosisPersistenceService diagnosisPersistenceService, FarmFieldService farmFieldService, DiagnosisJobService diagnosisJobService) {
        this.diagnosisRagService = diagnosisRagService;
        this.diagnosisPersistenceService = diagnosisPersistenceService;
        this.farmFieldService = farmFieldService;
        this.diagnosisJobService = diagnosisJobService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Result<DiagnosisAnalysisResponse>> analyze(@RequestBody DiagnosisAnalyzeRequest request) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error("用户未登录"));
        }
        try {
            if (farmFieldService != null) farmFieldService.applyToDiagnosisRequest(request, userId);
            validateCanonicalRequest(request);
            DiagnosisAnalysisResponse analyzed = diagnosisRagService.analyze(request, userId);
            return ResponseEntity.ok(Result.success(diagnosisPersistenceService.persist(analyzed, request, userId)));
        } catch (KnowledgeBackendException exception) {
            log.error("知识后端不可用，拒绝返回伪造诊断: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error("知识后端暂不可用"));
        } catch (DiagnosisPersistenceException exception) {
            log.error("诊断结果持久化失败，拒绝返回不稳定身份: {}", exception.getMessage(), exception);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error("诊断分析已完成，但持久化不可用，未返回稳定诊断身份"));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn("多模态诊断不可用: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Result.error("实时分析暂不可用: " + exception.getMessage()));
        } catch (Exception exception) {
            log.error("多模态诊断失败", exception);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error("实时分析暂不可用"));
        }
    }

    /**
     * Asynchronous REAL entry point for the product UI. Diagnosis RAG may take
     * several minutes with a thinking model; never hold the browser request open.
     */
    @PostMapping("/jobs")
    public ResponseEntity<Result<DiagnosisJobService.DiagnosisJobResponse>> submitJob(@RequestBody DiagnosisAnalyzeRequest request) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error("用户未登录"));
        try {
            if (farmFieldService != null) farmFieldService.applyToDiagnosisRequest(request, userId);
            validateCanonicalRequest(request);
            if (diagnosisJobService == null) throw new IllegalStateException("诊断任务服务未初始化");
            return ResponseEntity.ok(Result.success(diagnosisJobService.submit(request, userId)));
        } catch (KnowledgeBackendException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error("知识后端暂不可用"));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Result.error("实时分析暂不可用: " + exception.getMessage()));
        } catch (Exception exception) {
            log.error("诊断任务提交失败", exception);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error("诊断任务暂不可用"));
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Result<DiagnosisJobService.DiagnosisJobResponse>> getJob(@PathVariable String jobId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error("用户未登录"));
        if (diagnosisJobService == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error("诊断任务服务未初始化"));
        DiagnosisJobService.DiagnosisJobResponse job = diagnosisJobService.get(jobId, userId);
        if (job == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error("诊断任务不存在或无权访问"));
        return ResponseEntity.ok(Result.success(job));
    }

    /** Only references issued by the authenticated upload boundary may reach a vision provider. */
    private void validateCanonicalRequest(DiagnosisAnalyzeRequest request) {
        if (request == null || request.getFieldId() == null || request.getFieldId().isBlank()) {
            throw new IllegalArgumentException("fieldId 不能为空");
        }
        if ("a12-demo".equals(request.getFieldId())) {
            throw new IllegalArgumentException("a12-demo 仅属于前端演示模式，不能调用 REAL 诊断接口");
        }
        List<String> images = request.getImageUrls();
        if (images == null) return;
        URI upload = URI.create(uploadBaseUrl);
        URI cos = cosReferenceBaseUrl();
        String uploadPath = upload.getPath() == null ? "/" : upload.getPath().replaceAll("/$", "");
        for (String image : images) {
            if (image == null || image.isBlank()) throw new IllegalArgumentException("图像引用不能为空");
            URI reference;
            try { reference = URI.create(image); } catch (RuntimeException error) { throw new IllegalArgumentException("图像引用格式无效"); }
            if (!("http".equalsIgnoreCase(reference.getScheme()) || "https".equalsIgnoreCase(reference.getScheme()))) {
                throw new IllegalArgumentException("图像必须来自已认证上传的 HTTP(S) 引用");
            }
            boolean localUpload = referenceUnder(upload, uploadPath, reference);
            boolean cosUpload = cos != null && referenceUnder(cos, "/", reference);
            if (!localUpload && !cosUpload) {
                throw new IllegalArgumentException("图像引用不属于当前上传资源边界");
            }
        }
    }

    private URI cosReferenceBaseUrl() {
        if (cosPublicBaseUrl != null && !cosPublicBaseUrl.isBlank()) return URI.create(cosPublicBaseUrl);
        if (cosBucket == null || cosBucket.isBlank() || cosRegion == null || cosRegion.isBlank()) return null;
        return URI.create("https://" + cosBucket + ".cos." + cosRegion + ".myqcloud.com");
    }

    private boolean referenceUnder(URI base, String basePath, URI reference) {
        return sameHost(base, reference) && reference.getPath() != null
                && ("/".equals(basePath) || reference.getPath().equals(basePath) || reference.getPath().startsWith(basePath + "/"));
    }

    private boolean sameHost(URI expected, URI actual) {
        String expectedHost = expected.getHost();
        String actualHost = actual.getHost();
        if (expectedHost == null || actualHost == null) return false;
        return expectedHost.equalsIgnoreCase(actualHost) && expected.getPort() == actual.getPort();
    }
}


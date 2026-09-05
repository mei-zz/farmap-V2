package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.ai.GroundedDiagnosisGenerator;
import com.mei.zhgy.service.ai.ModelCallException;
import com.mei.zhgy.service.ai.EvidenceReferenceValidator;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MVP Multimodal RAG 编排器。
 *
 * <p>真实模式由 GroundedDiagnosisGenerator 负责结构化生成；当模型不可用时，
 * 才回退到只使用检索 Evidence 的模板结果。两者都不返回模型内部推理。</p>
 */
@Service
@Slf4j
public class DiagnosisRagService {
    private final List<DiagnosisRetriever> retrievers;
    private final EvidenceFusionService evidenceFusionService;
    private final GroundedDiagnosisGenerator groundedDiagnosisGenerator;

    @Value("${farmap.ai.generation.mode:template}")
    private String generationMode;
    @Value("${milvus.host:127.0.0.1}") private String milvusHost = "127.0.0.1";
    @Value("${milvus.port:19530}") private int milvusPort = 19530;

    public DiagnosisRagService(List<DiagnosisRetriever> retrievers, EvidenceFusionService evidenceFusionService) {
        this(retrievers, evidenceFusionService, null);
    }

    @Autowired
    public DiagnosisRagService(List<DiagnosisRetriever> retrievers,
                               EvidenceFusionService evidenceFusionService,
                               GroundedDiagnosisGenerator groundedDiagnosisGenerator) {
        this.retrievers = retrievers;
        this.evidenceFusionService = evidenceFusionService;
        this.groundedDiagnosisGenerator = groundedDiagnosisGenerator;
    }

    public DiagnosisAnalysisResponse analyze(DiagnosisAnalyzeRequest request, Long userId) {
        validateRequest(request);
        String runId = "rag-" + UUID.randomUUID();
        long totalStart = System.currentTimeMillis();
        long retrievalStart = totalStart;

        List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
        Map<String, Object> retrieverSummary = new LinkedHashMap<>();
        Set<String> enabledModalities = request.getEnabledModalities() == null
                ? Collections.emptySet()
                : new HashSet<>(request.getEnabledModalities());
        for (DiagnosisRetriever retriever : retrievers) {
            if (!enabledModalities.isEmpty() && !enabledModalities.contains(retriever.modality())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("count", 0);
                item.put("status", "disabled_by_ablation");
                retrieverSummary.put(retriever.modality(), item);
                continue;
            }
            long start = System.currentTimeMillis();
            try {
                List<DiagnosisEvidenceVO> retrieved = retriever.retrieve(request);
                evidence.addAll(retrieved);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("count", retrieved.size());
                item.put("latencyMs", System.currentTimeMillis() - start);
                item.put("topK", request.getTopK() == null ? 5 : request.getTopK());
                if (retrieved.isEmpty() && "historical_case".equals(retriever.modality())) item.put("status", "BLOCKED");
                retrieverSummary.put(retriever.modality(), item);
            } catch (RuntimeException exception) {
                log.warn("Retriever {} 执行失败，runId={}: {}", retriever.modality(), runId, exception.getMessage());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("count", 0);
                item.put("latencyMs", System.currentTimeMillis() - start);
                item.put("status", "failed");
                retrieverSummary.put(retriever.modality(), item);
            }
        }
        int inputEvidenceCount = evidence.size();
        long fusionStart = System.currentTimeMillis();
        evidence = evidenceFusionService.fuse(evidence);
        long fusionLatency = System.currentTimeMillis() - fusionStart;
        long retrievalLatency = System.currentTimeMillis() - retrievalStart;
        ensureMinimumModalities(evidence);

        long generationStart = System.currentTimeMillis();
        GroundedDiagnosisGenerator.GenerationResult generationResult = null;
        String generationFallbackReason = null;
        DiagnosisAnalysisResponse response;
        if ("qwen".equalsIgnoreCase(generationMode)) {
            if (groundedDiagnosisGenerator == null) {
                throw new IllegalStateException("Qwen 生成器未初始化");
            }
            try {
                generationResult = groundedDiagnosisGenerator.generate(request, runId, evidence);
                response = generationResult.getResponse();
            } catch (EvidenceReferenceValidator.InvalidEvidenceReferenceException exception) {
                generationFallbackReason = "evidence_reference_validation_failed";
                response = buildGroundedResponse(request, runId, evidence);
            } catch (ModelCallException exception) {
                if (!"invalid_structured_output".equals(exception.getErrorType())) {
                    throw exception;
                }
                generationFallbackReason = "structured_output_repair_failed";
                response = buildGroundedResponse(request, runId, evidence);
            }
        } else {
            response = buildGroundedResponse(request, runId, evidence);
        }
        // Evidence is owned by the retrievers, not by the model response. Keep the
        // verified server-side set attached to both real and template results.
        response.setRunId(runId);
        response.setEvidence(evidence);
        long generationLatency = System.currentTimeMillis() - generationStart;
        long totalLatency = System.currentTimeMillis() - totalStart;

        Map<String, Object> responseSummary = new LinkedHashMap<>();
        responseSummary.put("topK", request.getTopK() == null ? 5 : request.getTopK());
        responseSummary.put("returnedEvidence", evidence.size());
        responseSummary.put("modalities", evidence.stream().map(DiagnosisEvidenceVO::getModality).distinct().collect(Collectors.toList()));
        responseSummary.put("retrievers", retrieverSummary);
        responseSummary.put("enabledModalities", enabledModalities.isEmpty() ? "all" : enabledModalities);
        responseSummary.put("modelPolicy", request.getModelPolicy() == null ? "default" : request.getModelPolicy());
        responseSummary.put("retrievalLatencyMs", retrievalLatency);
        responseSummary.put("inputEvidenceCount", inputEvidenceCount);
        responseSummary.put("outputEvidenceCount", evidence.size());
        responseSummary.put("fusionLatencyMs", fusionLatency);
        response.setRetrievalSummary(responseSummary);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "real");
        metadata.put("status", "AI_INITIAL");
        metadata.put("generationMode", generationFallbackReason == null
                ? (generationResult == null ? "grounded-template" : "qwen")
                : "template-fallback");
        metadata.put("model", generationResult == null ? "not-invoked" : generationResult.getModelCall().getSelectedModel());
        metadata.put("embeddingModel", firstEvidenceMetadata(evidence, "embeddingModel", "not-invoked"));
        metadata.put("embeddingProvider", firstEvidenceMetadata(evidence, "embeddingProvider", "not-invoked"));
        metadata.put("textEmbeddingModel", firstModalityMetadata(evidence, "knowledge", "embeddingModel", "not-invoked"));
        metadata.put("imageEmbeddingModel", firstModalityMetadata(evidence, "camera", "embeddingModel", "not-invoked"));
        metadata.put("textEmbeddingDimension", firstModalityMetadata(evidence, "knowledge", "embeddingDimension", 512));
        metadata.put("imageEmbeddingDimension", firstModalityMetadata(evidence, "camera", "embeddingDimension", 0));
        metadata.put("historicalRetriever", historicalStatus(retrieverSummary));
        metadata.put("milvusEndpoint", milvusHost + ":" + milvusPort);
        metadata.put("milvusCollection", "farmap_image_vectors_new");
        metadata.put("milvusStatus", "BLOCKED".equals(historicalStatus(retrieverSummary)) ? "VERIFIED_EMPTY" : "REAL");
        metadata.put("runTimestamp", Instant.now().toString());
        metadata.put("fieldId", request.getFieldId());
        metadata.put("farmId", request.getFarmId());
        metadata.put("userId", userId);
        metadata.put("enabledModalities", enabledModalities.isEmpty() ? "all" : enabledModalities);
        metadata.put("modelPolicy", request.getModelPolicy() == null ? "default" : request.getModelPolicy());
        metadata.put("retrievedEvidenceIds", evidence.stream().map(DiagnosisEvidenceVO::getId).collect(Collectors.toList()));
        metadata.put("selectedEvidenceIds", evidence.stream().map(DiagnosisEvidenceVO::getId).collect(Collectors.toList()));
        metadata.put("retrievalLatencyMs", retrievalLatency);
        metadata.put("fusionLatencyMs", fusionLatency);
        metadata.put("generationLatencyMs", generationLatency);
        metadata.put("totalLatencyMs", totalLatency);
        if (generationFallbackReason != null) {
            metadata.put("generationFallbackReason", generationFallbackReason);
        }
        if (generationResult != null) {
            metadata.put("primaryModel", generationResult.getPrimaryModel());
            metadata.put("evidenceSynthesisModel", generationResult.getEvidenceSynthesisModel());
            metadata.put("selectedModel", generationResult.getModelCall().getSelectedModel());
            metadata.put("escalatedModel", generationResult.getEscalatedModel());
            metadata.put("routingReason", generationResult.getEscalationReason() == null
                    ? generationResult.getModelCall().getRoutingReason() : generationResult.getEscalationReason());
            metadata.put("escalated", generationResult.isEscalated());
            metadata.put("fallbackChain", generationResult.getModelCall().getFallbackChain());
            if (generationResult.getModelCall().getUsage() != null) {
                metadata.put("inputTokens", generationResult.getModelCall().getUsage().getInputTokens());
                metadata.put("outputTokens", generationResult.getModelCall().getUsage().getOutputTokens());
                metadata.put("thinkingTokens", generationResult.getModelCall().getUsage().getThinkingTokens());
                metadata.put("totalTokens", generationResult.getModelCall().getUsage().getTotalTokens());
            }
        }
        response.setMetadata(metadata);

        log.info("RAG_RUN runId={} fieldId={} modalities={} topK={} evidenceIds={} retrievalLatencyMs={} generationLatencyMs={} totalLatencyMs={} model={} embeddingModel={} promptVersion={}",
                runId,
                request.getFieldId(),
                responseSummary.get("modalities"),
                request.getTopK(),
                metadata.get("selectedEvidenceIds"),
                retrievalLatency,
                generationLatency,
                totalLatency,
                metadata.get("model"),
                metadata.get("embeddingModel"),
                "grounded-diagnosis-v1");
        return response;
    }

    private Object firstEvidenceMetadata(List<DiagnosisEvidenceVO> evidence, String key, String fallback) {
        for (DiagnosisEvidenceVO item : evidence) {
            if (item.getMetadata() != null && item.getMetadata().get(key) != null) {
                return item.getMetadata().get(key);
            }
        }
        return fallback;
    }

    private Object firstModalityMetadata(List<DiagnosisEvidenceVO> evidence, String modality, String key, Object fallback) {
        for (DiagnosisEvidenceVO item : evidence) {
            if (modality.equals(item.getModality()) && item.getMetadata() != null && item.getMetadata().get(key) != null) return item.getMetadata().get(key);
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private String historicalStatus(Map<String, Object> summary) {
        Object value = summary.get("historical_case");
        if (!(value instanceof Map)) return "DISABLED";
        Object count = ((Map<String, Object>) value).get("count");
        return count instanceof Number && ((Number) count).intValue() > 0 ? "REAL" : "BLOCKED";
    }

    private void validateRequest(DiagnosisAnalyzeRequest request) {
        if (request == null || request.getFieldId() == null || request.getFieldId().trim().isEmpty()) {
            throw new IllegalArgumentException("fieldId 不能为空");
        }
        if (request.getImageUrls() == null || request.getImageUrls().isEmpty() || request.getImageUrls().size() > 5) {
            throw new IllegalArgumentException("实时诊断需要提供1-5张图像");
        }
        if (request.getWeather() == null || request.getWeather().getRainfall14d() == null) {
            throw new IllegalArgumentException("实时诊断需要提供天气上下文");
        }
    }

    private void ensureMinimumModalities(List<DiagnosisEvidenceVO> evidence) {
        List<String> modalities = evidence.stream().map(DiagnosisEvidenceVO::getModality).distinct().collect(Collectors.toList());
        List<String> missing = Arrays.asList("camera", "weather", "knowledge").stream()
                .filter(modality -> !modalities.contains(modality))
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("实时诊断缺少必要证据模态: " + String.join(", ", missing));
        }
    }

    private DiagnosisAnalysisResponse buildGroundedResponse(DiagnosisAnalyzeRequest request, String runId, List<DiagnosisEvidenceVO> evidence) {
        double rainfall = request.getWeather().getRainfall14d();
        boolean highRainfall = rainfall >= 40D;
        String field = request.getFieldId();
        String crop = request.getCrop() == null ? "当前作物" : request.getCrop();
        String title = highRainfall ? "持续降雨后的根区缺氧风险" : "叶片状态需要进一步复核";
        String riskLevel = highRainfall ? "high" : "medium";
        double confidence = highRainfall ? 0.82D : 0.58D;
        String summary = highRainfall
                ? field + "（" + crop + "）的图像输入、近14日降雨和农业知识证据共同支持根区排水受限并影响养分吸收的判断。该结论仍需结合现场排水检查和视觉模型结果复核。"
                : field + "（" + crop + "）已有待复核的图像输入，但当前天气证据不足以支持高风险结论，建议补充现场数据。";

        List<String> cameraIds = evidence.stream().filter(item -> "camera".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList());
        List<String> weatherIds = evidence.stream().filter(item -> "weather".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList());
        List<String> knowledgeIds = evidence.stream().filter(item -> "knowledge".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList());
        List<String> weatherKnowledge = new ArrayList<>(weatherIds);
        weatherKnowledge.addAll(knowledgeIds);

        List<DiagnosisAnalysisResponse.Claim> claims = Arrays.asList(
                DiagnosisAnalysisResponse.Claim.builder()
                        .id("claim_1")
                        .text("Camera 图像已作为当前地块的视觉证据输入，叶片特征需由视觉模型进一步判断")
                        .evidenceIds(cameraIds)
                        .build(),
                DiagnosisAnalysisResponse.Claim.builder()
                        .id("claim_2")
                        .text(highRainfall ? "持续降雨可能增加根区缺氧与养分吸收障碍风险" : "当前降雨证据不足以确认根区缺氧")
                        .evidenceIds(weatherKnowledge)
                        .build());

        List<DiagnosisAnalysisResponse.Alternative> alternatives = Arrays.asList(
                DiagnosisAnalysisResponse.Alternative.builder().name("缺镁").probability(0.11D).build(),
                DiagnosisAnalysisResponse.Alternative.builder().name("根腐病").probability(0.05D).build());

        List<DiagnosisAnalysisResponse.Recommendation> recommendations = Arrays.asList(
                DiagnosisAnalysisResponse.Recommendation.builder()
                        .id("recommendation_1")
                        .title("检查并改善排水")
                        .priority(highRainfall ? "high" : "medium")
                        .detail("优先检查主排水沟、根区积水和土埂通畅情况；完成现场确认后再制定施肥方案。")
                        .evidenceIds(weatherKnowledge)
                        .build(),
                DiagnosisAnalysisResponse.Recommendation.builder()
                        .id("recommendation_2")
                        .title("补充连续监测")
                        .priority("medium")
                        .detail("未来 7 天复查叶片图像、土壤湿度和降雨变化，若症状扩大再提交专家复核。")
                        .evidenceIds(cameraIds)
                        .build());

        return DiagnosisAnalysisResponse.builder()
                .runId(runId)
                .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder()
                        .title(title)
                        .riskLevel(riskLevel)
                        .confidence(confidence)
                        .summary(summary)
                        .alternatives(alternatives)
                        .build())
                .claims(claims)
                .evidence(evidence)
                .recommendations(recommendations)
                .build();
    }
}

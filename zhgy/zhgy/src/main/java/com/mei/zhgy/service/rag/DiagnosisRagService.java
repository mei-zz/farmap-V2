package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.ai.GroundedDiagnosisGenerator;
import com.mei.zhgy.service.ai.ModelCallException;
import com.mei.zhgy.service.ai.EvidenceReferenceValidator;
import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.reference.ReferenceImage;
import com.mei.zhgy.service.reference.ReferenceImageAttacher;
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
    private final HistoricalVectorStoreInitializer historicalVectorStore;
    private final ReferenceImageAttacher referenceImageAttacher;

    @Value("${farmap.ai.generation.mode:template}")
    private String generationMode;
    @Value("${milvus.host:127.0.0.1}") private String milvusHost = "127.0.0.1";
    @Value("${milvus.port:19530}") private int milvusPort = 19530;

    public DiagnosisRagService(List<DiagnosisRetriever> retrievers, EvidenceFusionService evidenceFusionService) {
        this(retrievers, evidenceFusionService, null, null, null);
    }

    public DiagnosisRagService(List<DiagnosisRetriever> retrievers,
                               EvidenceFusionService evidenceFusionService,
                               GroundedDiagnosisGenerator groundedDiagnosisGenerator) {
        this(retrievers, evidenceFusionService, groundedDiagnosisGenerator, null, null);
    }

    /** Compatibility overload for callers that do not yet provide the atlas attacher. */
    public DiagnosisRagService(List<DiagnosisRetriever> retrievers,
                               EvidenceFusionService evidenceFusionService,
                               GroundedDiagnosisGenerator groundedDiagnosisGenerator,
                               HistoricalVectorStoreInitializer historicalVectorStore) {
        this(retrievers, evidenceFusionService, groundedDiagnosisGenerator, historicalVectorStore, null);
    }

    @Autowired
    public DiagnosisRagService(List<DiagnosisRetriever> retrievers,
                               EvidenceFusionService evidenceFusionService,
                               GroundedDiagnosisGenerator groundedDiagnosisGenerator,
                               HistoricalVectorStoreInitializer historicalVectorStore,
                               @Autowired(required = false) ReferenceImageAttacher referenceImageAttacher) {
        this.retrievers = retrievers;
        this.evidenceFusionService = evidenceFusionService;
        this.groundedDiagnosisGenerator = groundedDiagnosisGenerator;
        this.historicalVectorStore = historicalVectorStore;
        this.referenceImageAttacher = referenceImageAttacher;
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
                String retrievalStatus = null;
                List<DiagnosisEvidenceVO> retrieved;
                if (retriever instanceof KnowledgeEvidenceRetriever knowledge) {
                    KnowledgeEvidenceRetriever.RetrievalResult result = knowledge.retrieveWithStatus(request, userId);
                    retrievalStatus = result.getStatus();
                    retrieved = result.getEvidence();
                    if ("UNAVAILABLE".equals(retrievalStatus)) {
                        throw new KnowledgeBackendException("UNAVAILABLE", "生产知识检索不可用");
                    }
                } else {
                    retrieved = retriever.retrieve(request, userId);
                }
                evidence.addAll(retrieved);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("count", retrieved.size());
                item.put("latencyMs", System.currentTimeMillis() - start);
                item.put("topK", request.getTopK() == null ? 5 : request.getTopK());
                if (retrievalStatus != null) item.put("status", retrievalStatus);
                else if (retrieved.isEmpty() && "historical_case".equals(retriever.modality())) item.put("status", historicalEmptyStatus());
                else if (retrieved.isEmpty()) item.put("status", retriever.emptyStatus(request, userId) == null ? "READY_EMPTY" : retriever.emptyStatus(request, userId));
                else item.put("status", "READY");
                retrieverSummary.put(retriever.modality(), item);
            } catch (KnowledgeBackendException exception) {
                // A production Knowledge outage is a dependency failure, not an
                // empty retrieval result.  Preserve the signal for the HTTP
                // boundary so callers receive 503/UNAVAILABLE.
                if ("UNAVAILABLE".equals(exception.getCode()) || "KNOWLEDGE_PERSISTENCE_UNAVAILABLE".equals(exception.getCode())) {
                    throw exception;
                }
                throw exception;
            } catch (RuntimeException exception) {
                log.warn("Retriever {} 执行失败，runId={}: {}", retriever.modality(), runId, exception.getMessage());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("count", 0);
                item.put("latencyMs", System.currentTimeMillis() - start);
                item.put("status", "knowledge".equals(retriever.modality()) ? "UNAVAILABLE" : "failed");
                retrieverSummary.put(retriever.modality(), item);
            }
        }
        int inputEvidenceCount = evidence.size();
        long fusionStart = System.currentTimeMillis();
        evidence = evidenceFusionService.fuse(evidence);
        long fusionLatency = System.currentTimeMillis() - fusionStart;
        long retrievalLatency = System.currentTimeMillis() - retrievalStart;
        ensureMinimumModalities(evidence);
        attachReferenceImages(request, evidence);

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
        responseSummary.put("sourceStatuses", sourceStatuses(request, retrieverSummary));
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
        String historicalState = historicalStatus(retrieverSummary);
        metadata.put("historicalRetriever", historicalState);
        metadata.put("milvusEndpoint", milvusHost + ":" + milvusPort);
        metadata.put("milvusCollection", "farmap_knowledge_chunks_qwen3_v1");
        metadata.put("milvusStatus", retrieverStatus(retrieverSummary, "knowledge", "UNAVAILABLE"));
        metadata.put("knowledgeStatus", retrieverStatus(retrieverSummary, "knowledge", "UNAVAILABLE"));
        metadata.put("rerankerModel", firstEvidenceMetadata(evidence, "rerankerModel", "gte-rerank-v2"));
        metadata.put("rerankerStatus", firstEvidenceMetadata(evidence, "rerankerStatus", "UNAVAILABLE"));
        metadata.put("runTimestamp", Instant.now().toString());
        metadata.put("fieldId", request.getFieldId());
        metadata.put("farmId", request.getFarmId());
        metadata.put("userId", userId);
        metadata.put("enabledModalities", enabledModalities.isEmpty() ? "all" : enabledModalities);
        metadata.put("modelPolicy", request.getModelPolicy() == null ? "default" : request.getModelPolicy());
        metadata.put("realContextMode", request.getTrustedFieldContext() == null ? "REAL_INPUT" : "REAL");
        if (request.getTrustedFieldContext() != null) metadata.put("fieldContext", request.getTrustedFieldContext());
        metadata.put("sourceStatuses", sourceStatuses(request, retrieverSummary));
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
        Object status = ((Map<String, Object>) value).get("status");
        if ("EMPTY".equals(status)) return "READY_EMPTY";
        if ("UNAVAILABLE".equals(status) || "failed".equals(status)) return "UNAVAILABLE";
        Object count = ((Map<String, Object>) value).get("count");
        return count instanceof Number && ((Number) count).intValue() > 0 ? "READY" : "READY_EMPTY";
    }

    private String historicalEmptyStatus() {
        if (historicalVectorStore == null) return "UNAVAILABLE";
        try {
            Map<String, Object> state = historicalVectorStore.initialize(false);
            if (!"READY".equals(state.get("status"))) return "UNAVAILABLE";
            return Long.valueOf(0L).equals(state.get("rowCount")) ? "EMPTY" : "READY";
        } catch (Exception error) {
            return "UNAVAILABLE";
        }
    }

    private void validateRequest(DiagnosisAnalyzeRequest request) {
        if (request == null || request.getFieldId() == null || request.getFieldId().trim().isEmpty()) {
            throw new IllegalArgumentException("fieldId 不能为空");
        }
        if (request.getImageUrls() != null && request.getImageUrls().size() > 5) {
            throw new IllegalArgumentException("实时诊断最多支持5张图像");
        }
        if (request.getFieldId().startsWith("field-") && request.getTrustedFieldContext() == null) {
            throw new IllegalArgumentException("真实地块请求必须由服务端组装 FieldContext");
        }
    }

    private void ensureMinimumModalities(List<DiagnosisEvidenceVO> evidence) {
        List<String> modalities = evidence.stream().map(DiagnosisEvidenceVO::getModality).distinct().collect(Collectors.toList());
        List<String> missing = Arrays.asList("knowledge").stream()
                .filter(modality -> !modalities.contains(modality))
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("实时诊断缺少必要证据模态: " + String.join(", ", missing));
        }
    }

    private void attachReferenceImages(DiagnosisAnalyzeRequest request, List<DiagnosisEvidenceVO> evidence) {
        if (referenceImageAttacher == null || request == null || evidence == null) return;
        try {
            List<ReferenceImage> references = referenceImageAttacher.forChat(
                    request.getQuery(), request.getCrop(), request.getGrowthStage());
            for (ReferenceImage reference : references) {
                if (reference == null || reference.refId() == null || reference.refId().isBlank()
                        || reference.imageUrl() == null || reference.imageUrl().isBlank()) continue;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("refId", reference.refId());
                metadata.put("refType", reference.refType());
                metadata.put("match", reference.match());
                metadata.put("retrievalType", "reference-image");
                evidence.add(DiagnosisEvidenceVO.builder()
                        .id("reference-image-" + reference.refId())
                        .modality("knowledge")
                        .title("参考图谱：" + reference.label())
                        .summary("作物参考图谱，仅用于前端对照展示，不是本次现场观测，不要据此编造用户的病征或数值。")
                        .score(0.5D)
                        .source(DiagnosisEvidenceVO.Source.builder().type("knowledge").name("FarMap reference atlas").build())
                        .preview(DiagnosisEvidenceVO.Preview.builder().imageUrl(reference.imageUrl()).textSnippet(reference.label()).build())
                        .metadata(metadata)
                        .build());
            }
        } catch (Exception error) {
            log.warn("诊断参考图谱附加失败，继续返回文字/原有证据: {}", error.getClass().getSimpleName());
        }
    }

    private DiagnosisAnalysisResponse buildGroundedResponse(DiagnosisAnalyzeRequest request, String runId, List<DiagnosisEvidenceVO> evidence) {
        double rainfall = request.getWeather() == null || request.getWeather().getRainfall14d() == null
                ? 0D : request.getWeather().getRainfall14d();
        boolean highRainfall = rainfall >= 40D;
        String field = request.getFieldId();
        String crop = request.getCrop() == null ? "当前作物" : request.getCrop();
        String title = highRainfall ? "持续降雨后的根区缺氧风险" : "叶片状态需要进一步复核";
        String riskLevel = highRainfall ? "high" : "medium";
        double confidence = highRainfall ? 0.82D : 0.58D;
        String summary = highRainfall
                ? field + "（" + crop + "）的图像输入、近14日降雨和农业知识证据共同支持根区排水受限并影响养分吸收的判断。该结论仍需结合现场排水检查和视觉模型结果复核。"
                : field + "（" + crop + "）已有农业知识证据，当前可用现场数据不足以支持高风险结论，建议补充现场数据。";

        List<String> cameraIds = evidence.stream().filter(item -> "camera".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList());
        List<String> weatherIds = evidence.stream().filter(item -> "weather".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList());
        List<String> knowledgeIds = evidence.stream().filter(item -> "knowledge".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList());
        List<String> weatherKnowledge = new ArrayList<>(weatherIds);
        weatherKnowledge.addAll(knowledgeIds);

        List<String> allIds = evidence.stream().map(DiagnosisEvidenceVO::getId).collect(Collectors.toList());
        List<DiagnosisAnalysisResponse.Claim> claims = new ArrayList<>();
        if (!cameraIds.isEmpty()) claims.add(DiagnosisAnalysisResponse.Claim.builder().id("claim_camera").text("视觉模型返回的图像观察需要结合农业知识进一步复核").evidenceIds(cameraIds).build());
        claims.add(DiagnosisAnalysisResponse.Claim.builder().id("claim_context").text(highRainfall ? "可信天气与农业知识证据提示持续降雨后的根区风险" : "当前可用农业知识支持后续现场核验，但不足以确认高风险结论").evidenceIds(weatherKnowledge.isEmpty() ? allIds : weatherKnowledge).build());

        List<DiagnosisAnalysisResponse.Alternative> alternatives = Arrays.asList(
                DiagnosisAnalysisResponse.Alternative.builder().name("缺镁").probability(0.11D).build(),
                DiagnosisAnalysisResponse.Alternative.builder().name("根腐病").probability(0.05D).build());

        List<DiagnosisAnalysisResponse.Recommendation> recommendations = Arrays.asList(
                DiagnosisAnalysisResponse.Recommendation.builder()
                        .id("recommendation_1")
                        .title("检查并改善排水")
                        .priority(highRainfall ? "high" : "medium")
                        .detail("优先检查主排水沟、根区积水和土埂通畅情况；完成现场确认后再制定施肥方案。")
                        .evidenceIds(weatherKnowledge.isEmpty() ? allIds : weatherKnowledge)
                        .build(),
                DiagnosisAnalysisResponse.Recommendation.builder()
                        .id("recommendation_2")
                        .title("补充连续监测")
                        .priority("medium")
                        .detail("未来 7 天复查叶片图像、土壤湿度和降雨变化，若症状扩大再提交专家复核。")
                        .evidenceIds(cameraIds.isEmpty() ? allIds : cameraIds)
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

    @SuppressWarnings("unchecked")
    private String retrieverStatus(Map<String, Object> summary, String modality, String fallback) {
        Object raw = summary.get(modality);
        if (raw instanceof Map<?, ?> map && map.get("status") != null) return String.valueOf(map.get("status"));
        return fallback;
    }

    private Map<String, Object> sourceStatuses(DiagnosisAnalyzeRequest request, Map<String, Object> retrieverSummary) {
        Map<String, Object> statuses = new LinkedHashMap<>();
        if (request.getTrustedFieldContext() != null) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("weather", request.getTrustedFieldContext().getWeather().get("status"));
            ctx.put("accumulatedTemperature", request.getTrustedFieldContext().getAccumulatedTemperature().get("status"));
            ctx.put("gis", request.getTrustedFieldContext().getGis().get("status"));
            ctx.put("soil", request.getTrustedFieldContext().getSoil().get("status"));
            ctx.put("satellite", request.getTrustedFieldContext().getSatellite().get("status"));
            statuses.putAll(ctx);
        }
        for (String modality : Arrays.asList("camera", "weather", "accumulated_temperature", "knowledge", "historical_case", "soil", "satellite", "spatial")) {
            statuses.putIfAbsent(modality, retrieverStatus(retrieverSummary, modality, "READY_EMPTY"));
        }
        return statuses;
    }
}

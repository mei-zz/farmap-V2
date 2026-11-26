package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import com.mei.zhgy.service.ai.IntelligenceGateway;
import com.mei.zhgy.service.ai.AiTaskType;
import com.mei.zhgy.service.ai.ModelCallException;
import com.mei.zhgy.service.ai.ModelCallRequest;
import com.mei.zhgy.service.ai.ModelCallResult;
import com.mei.zhgy.service.ai.ModelGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera Retriever：复用已有上传接口产出的 URL，只负责形成统一视觉证据。
 * 有 VL 权限时调用 Qwen-VL；无 VL 权限时保留真实图片输入、CLIP/Milvus
 * 相似检索和可追溯 Evidence，并把最终生成降级到已验证的文本模型。
 */
@Component
@Slf4j
public class VisualEvidenceRetriever implements DiagnosisRetriever {
    private final ImageEmbeddingProvider embeddingProvider;
    private final IntelligenceGateway visionGateway;
    private final ModelGateway modelGateway;
    private UploadedImageAssetIndexService imageAssetIndex;
    public VisualEvidenceRetriever() { this(null, null, null); }
    public VisualEvidenceRetriever(ImageEmbeddingProvider embeddingProvider) { this(embeddingProvider, null, null); }
    public VisualEvidenceRetriever(ImageEmbeddingProvider embeddingProvider, IntelligenceGateway visionGateway) {
        this(embeddingProvider, visionGateway, null);
    }
    @Autowired public VisualEvidenceRetriever(ImageEmbeddingProvider embeddingProvider,
                                              IntelligenceGateway visionGateway,
                                              ModelGateway modelGateway) {
        this.embeddingProvider = embeddingProvider;
        this.visionGateway = visionGateway;
        this.modelGateway = modelGateway;
    }

    @Autowired(required = false)
    public void setImageAssetIndex(UploadedImageAssetIndexService imageAssetIndex) {
        this.imageAssetIndex = imageAssetIndex;
    }
    @Override
    public String modality() {
        return "camera";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (request.getImageUrls() == null || request.getImageUrls().isEmpty()) {
            markVisionStatus(request, "READY_EMPTY");
            return Collections.emptyList();
        }

        // The commercial path uses the same ModelGateway as Copilot and
        // grounded diagnosis. The old Flask/Hunyuan adapter is retained only
        // for backwards-compatible tests and legacy endpoints.
        if (modelGateway != null) {
            try {
                ModelCallRequest modelRequest = ModelCallRequest.builder()
                        .requestId("vision-" + request.getFieldId())
                        .runId("vision-" + request.getFieldId())
                        .taskType(AiTaskType.IMAGE_UNDERSTANDING)
                        .systemPrompt("你是 FarMap 的农业视觉证据提取器。只根据图片提取可观察事实、图像质量和不确定性，不要编造图片之外的信息，不要输出隐藏思维过程。")
                        .userPrompt("仅返回 JSON：{subject, visibleSymptoms:[字符串], imageClear:布尔值, observedPhenologyStage:字符串或null, missingViews:[字符串], uncertainty:字符串或null}。不得从背景中的登记阶段推断图片所见，无法判读时阶段写null；不填写病例相似度。农场="
                                + request.getFarmId() + "，地块=" + request.getFieldId() + "，作物=" + request.getCrop() + "，生育期=" + request.getGrowthStage())
                        .imageUrls(new ArrayList<>(request.getImageUrls()))
                        .structuredOutput(true)
                        .context(new LinkedHashMap<>(Map.of(
                                "farmId", request.getFarmId() == null ? "" : request.getFarmId(),
                                "fieldId", request.getFieldId() == null ? "" : request.getFieldId(),
                                "imageCount", request.getImageUrls().size(),
                                "modelPolicy", "force_vl32",
                                "enableThinking", false,
                                "maxOutputTokens", 700)))
                        .build();
                ModelCallResult response = modelGateway.complete(modelRequest);
                if (!isVisionModel(response.getSelectedModel())) {
                    throw new ModelCallException("vision_model_unavailable",
                            "当前生成模型没有可用的视觉能力，已切换到文本证据链路");
                }
                boolean openCodeVision = "deepseek-v4.1-flash".equalsIgnoreCase(response.getSelectedModel());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("sourceMode", openCodeVision ? "REAL_OPENCODE_MODEL_GATEWAY" : "REAL_QWEN_MODEL_GATEWAY");
                metadata.put("visionStatus", "READY");
                metadata.put("visionModel", response.getSelectedModel());
                metadata.put("retrievalType", openCodeVision ? "DEEPSEEK_IMAGE_UNDERSTANDING" : "QWEN_VL_IMAGE_UNDERSTANDING");
                metadata.put("latencyMs", response.getLatencyMs());
                metadata.put("routingReason", response.getRoutingReason());
                metadata.put("fallbackChain", response.getFallbackChain());
                VisualObservation observation = VisualObservation.parse(response.getContent());
                if (observation != null) metadata.put("visualObservation", observation);
                if (response.getUsage() != null) {
                    metadata.put("inputTokens", response.getUsage().getInputTokens());
                    metadata.put("outputTokens", response.getUsage().getOutputTokens());
                    metadata.put("thinkingTokens", response.getUsage().getThinkingTokens());
                }
                markVisionStatus(request, "READY");
                List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
                evidence.add(DiagnosisEvidenceVO.builder()
                        .id("camera_vision_1")
                        .modality("camera")
                        .title(openCodeVision ? "DeepSeek 多模态视觉分析" : "Qwen 多模态视觉分析")
                        .summary(response.getContent())
                        .score(0.90D)
                        .source(DiagnosisEvidenceVO.Source.builder()
                                .type("camera")
                                .name(response.getSelectedModel() == null ? "qwen3-vl-32b-thinking" : response.getSelectedModel())
                                .fieldId(request.getFieldId())
                                .cameraId(request.getCameraId() == null ? "VISION-01" : request.getCameraId())
                                .build())
                        .preview(DiagnosisEvidenceVO.Preview.builder().imageUrl(request.getImageUrls().get(0)).build())
                        .metadata(metadata)
                        .build());
                evidence.addAll(similarImageEvidence(request));
                return evidence;
            } catch (ModelCallException error) {
                log.warn("Model visual evidence unavailable for field {}: {}", request.getFieldId(), error.getErrorType());
                markVisionStatus(request, "VISION_UNAVAILABLE");
            } catch (RuntimeException error) {
                log.warn("Model visual evidence unavailable for field {}: {}", request.getFieldId(), error.getClass().getSimpleName());
                markVisionStatus(request, "VISION_UNAVAILABLE");
            }
        }

        // Compatibility path for unit tests and legacy callers only.
        if (modelGateway == null && visionGateway != null) {
            try {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("fieldId", request.getFieldId());
                context.put("farmId", request.getFarmId());
                context.put("crop", request.getCrop());
                context.put("growthStage", request.getGrowthStage());
                if (request.getPointId() != null) context.put("pointId", request.getPointId());
                if (request.getCameraId() != null) context.put("cameraId", request.getCameraId());
                if (request.getTrustedFieldContext() != null) context.put("fieldContext", request.getTrustedFieldContext());
                Map<String, Object> response = visionGateway.analyzeVision(request.getImageUrls(), context,
                        "vision-" + request.getFieldId(), null);
                String status = String.valueOf(response.getOrDefault("status", "UNAVAILABLE"));
                if (!"SUCCEEDED".equals(status)) {
                    markVisionStatus(request, "VISION_UNAVAILABLE");
                } else {
                    String summary = observations(response);
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("sourceMode", "REAL");
                    metadata.put("visionStatus", "READY");
                    metadata.put("visionModel", response.get("model"));
                    metadata.put("retrievalType", "VISION_ANALYZE");
                    markVisionStatus(request, "READY");
                    List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
                    evidence.add(DiagnosisEvidenceVO.builder()
                            .id("camera_vision_1")
                            .modality("camera")
                            .title("视觉模型分析结果")
                            .summary(summary)
                            .score(0.90D)
                            .source(DiagnosisEvidenceVO.Source.builder()
                                    .type("camera")
                                    .name(String.valueOf(response.getOrDefault("model", "VISION_ANALYZE")))
                                    .timestamp(null)
                                    .fieldId(request.getFieldId())
                                    .cameraId(request.getCameraId() == null ? "VISION-01" : request.getCameraId())
                                    .build())
                            .preview(DiagnosisEvidenceVO.Preview.builder().imageUrl(request.getImageUrls().get(0)).build())
                            .metadata(metadata)
                            .build());
                    evidence.addAll(similarImageEvidence(request));
                    return evidence;
                }
            } catch (RuntimeException error) {
                log.warn("VISION_ANALYZE unavailable for field {}: {}", request.getFieldId(), error.getClass().getSimpleName());
                markVisionStatus(request, "VISION_UNAVAILABLE");
            }
            // The legacy vision boundary is an analysis service, not a raw
            // image-ingestion boundary. Returning the image as if it had been
            // interpreted would make the Agent display unsupported findings.
            return Collections.emptyList();
        }

        List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
        int index = 1;
        for (String imageUrl : request.getImageUrls()) {
            if (imageUrl == null || imageUrl.trim().isEmpty()) {
                continue;
            }
            String id = "camera_" + index;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", "real-input");
            if (index == 1 && embeddingProvider != null) {
                EmbeddingProvider.EmbeddingResult embedding = embeddingProvider.embedImage(imageUrl);
                metadata.put("embeddingProvider", embedding.getProvider());
                metadata.put("embeddingModel", embedding.getModel());
                metadata.put("embeddingDimension", embedding.getDimension());
                metadata.put("embeddingLatencyMs", embedding.getLatencyMs());
            }
            evidence.add(DiagnosisEvidenceVO.builder()
                    .id(id)
                    .modality("camera")
                    .title("Camera 图像输入 " + index)
                    .summary("输入图像已纳入视觉证据集；本阶段由原视觉分析接口负责图像特征判断。")
                    .score(index == 1 ? 0.92 : 0.86)
                    .source(DiagnosisEvidenceVO.Source.builder()
                            .type("camera")
                            .name("Uploaded camera image")
                            .timestamp(request.getWeather() == null ? null : request.getWeather().getObservedAt())
                            .fieldId(request.getFieldId())
                            .cameraId(request.getCameraId() == null ? "CAM-" + String.format("%02d", index) : request.getCameraId())
                            .build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().imageUrl(imageUrl).build())
                    .metadata(metadata)
                    .build());
            index++;
        }
        evidence.addAll(similarImageEvidence(request));
        return evidence;
    }

    @Override
    public String emptyStatus(DiagnosisAnalyzeRequest request, Long userId) {
        if (modelGateway == null && visionGateway == null) return null;
        Object status = request.getContext() == null ? null : request.getContext().get("__visionStatus");
        return status == null ? "VISION_UNAVAILABLE" : String.valueOf(status);
    }

    private void markVisionStatus(DiagnosisAnalyzeRequest request, String status) {
        if (request.getContext() == null) request.setContext(new LinkedHashMap<>());
        request.getContext().put("__visionStatus", status);
    }

    private List<DiagnosisEvidenceVO> similarImageEvidence(DiagnosisAnalyzeRequest request) {
        if (imageAssetIndex == null || request.getImageUrls() == null || request.getImageUrls().isEmpty()) return Collections.emptyList();
        List<UploadedImageAssetIndexService.SimilarAsset> matches = imageAssetIndex.search(
                request.getImageUrls().get(0), request.getFarmId(), request.getFieldId(), 3);
        List<DiagnosisEvidenceVO> output = new ArrayList<>();
        int index = 1;
        for (UploadedImageAssetIndexService.SimilarAsset match : matches) {
            Map<String, Object> asset = match.asset();
            String imageUrl = String.valueOf(asset.getOrDefault("image_url", ""));
            if (imageUrl.equals(request.getImageUrls().get(0))) continue;
            String assetId = String.valueOf(asset.getOrDefault("asset_id", "asset-" + index));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", "MILVUS_IMAGE_ASSET");
            metadata.put("retrievalType", "VISUAL_SIMILARITY");
            metadata.put("assetId", assetId);
            metadata.put("similarity", match.similarity());
            metadata.put("pointId", asset.get("point_id"));
            metadata.put("cameraId", asset.get("camera_id"));
            output.add(DiagnosisEvidenceVO.builder()
                    .id("visual_similar_" + index++)
                    .modality("historical_case")
                    .title("相似现场图 " + assetId)
                    .summary("Milvus 图像向量检索返回相似现场图，相似度 " + String.format("%.0f%%", match.similarity() * 100D) + "，需要结合当前视觉结果复核。")
                    .score(match.similarity())
                    .source(DiagnosisEvidenceVO.Source.builder().type("image_asset").name(String.valueOf(asset.getOrDefault("source", "现场图片"))).document(assetId).fieldId(String.valueOf(asset.getOrDefault("field_id", request.getFieldId()))).cameraId(String.valueOf(asset.getOrDefault("camera_id", request.getCameraId()))).build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().imageUrl(imageUrl).build())
                    .metadata(metadata)
                    .build());
        }
        return output;
    }

    private boolean isVisionModel(String model) {
        return model != null && (model.toLowerCase(java.util.Locale.ROOT).startsWith("qwen3-vl-")
                || "deepseek-v4.1-flash".equalsIgnoreCase(model));
    }

    @SuppressWarnings("unchecked")
    private String observations(Map<String, Object> response) {
        Object raw = response.get("observations");
        if (raw instanceof List<?> values && !values.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Map<?, ?> map && map.get("content") != null) texts.add(String.valueOf(map.get("content")));
                else if (value != null) texts.add(String.valueOf(value));
            }
            if (!texts.isEmpty()) return String.join("；", texts);
        }
        Object analysis = response.get("analysis");
        return analysis == null ? "视觉模型已返回分析结果，详情待结合其他证据复核。" : String.valueOf(analysis);
    }
}

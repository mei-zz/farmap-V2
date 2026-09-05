package com.mei.zhgy.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 使用 ModelGateway 生成结构化、可验证、只引用后端 Evidence 的诊断。 */
@Service
public class GroundedDiagnosisGenerator {
    private final ModelGateway modelGateway;
    private final EvidenceReferenceValidator evidenceReferenceValidator;
    private final ModelEscalationPolicy escalationPolicy;
    private final ObjectMapper objectMapper;

    public GroundedDiagnosisGenerator(ModelGateway modelGateway,
                                      EvidenceReferenceValidator evidenceReferenceValidator,
                                      ModelEscalationPolicy escalationPolicy,
                                      ObjectMapper objectMapper) {
        this.modelGateway = modelGateway;
        this.evidenceReferenceValidator = evidenceReferenceValidator;
        this.escalationPolicy = escalationPolicy;
        this.objectMapper = objectMapper;
    }

    public GenerationResult generate(DiagnosisAnalyzeRequest request,
                                     String runId,
                                     List<DiagnosisEvidenceVO> evidence) {
        ModelCallResult evidenceSynthesis = null;
        try {
            ModelCallRequest synthesisRequest = buildRequest(request, runId, evidence,
                    AiTaskType.EVIDENCE_SYNTHESIS,
                    "先对已检索证据做简洁摘要，列出支持关系与不确定性；不要生成最终诊断。");
            synthesisRequest.setImageUrls(new ArrayList<>());
            evidenceSynthesis = modelGateway.complete(synthesisRequest);
        } catch (ModelCallException ignored) {
            // Evidence synthesis 是加速层；失败时仍让多模态模型直接使用原始 Evidence。
        }
        ModelCallRequest primaryRequest = buildRequest(request, runId, evidence,
                AiTaskType.MULTIMODAL_DIAGNOSIS,
                "请完成一次有证据约束的多模态农业诊断。");
        if (evidenceSynthesis != null) {
            primaryRequest.setUserPrompt(primaryRequest.getUserPrompt()
                    + "\nQwen3.6-Plus Evidence Synthesis（仅作辅助，仍须以原始 Evidence 为准）：\n"
                    + evidenceSynthesis.getContent());
        }
        ModelCallResult primaryCall = modelGateway.complete(primaryRequest);
        DiagnosisAnalysisResponse primaryResponse;
        try {
            primaryResponse = parse(primaryCall.getContent());
            evidenceReferenceValidator.validateOrThrow(primaryResponse, evidence);
        } catch (ModelCallException | EvidenceReferenceValidator.InvalidEvidenceReferenceException exception) {
            return repairOnce(request, runId, evidence, primaryCall);
        }

        ModelRoutingContext postDiagnosisContext = ModelRoutingContext.builder()
                .diagnosisConfidence(primaryResponse.getDiagnosis() == null ? null : primaryResponse.getDiagnosis().getConfidence())
                .highRisk(primaryResponse.getDiagnosis() != null && "high".equalsIgnoreCase(primaryResponse.getDiagnosis().getRiskLevel()))
                .multipleCompetingHypotheses(primaryResponse.getDiagnosis() != null
                        && primaryResponse.getDiagnosis().getAlternatives() != null
                        && primaryResponse.getDiagnosis().getAlternatives().size() >= 3)
                .imageCount(request.getImageUrls() == null ? 0 : request.getImageUrls().size())
                .build();

        if (!ModelCapabilityRegistry.QWEN_VL_235B.equals(primaryCall.getSelectedModel())
                && escalationPolicy.shouldEscalate(postDiagnosisContext)) {
            try {
                ModelCallRequest expertRequest = buildRequest(request, runId, evidence,
                        AiTaskType.HARD_DIAGNOSIS,
                        "请复核上一轮诊断。仅在证据支持时升级结论，并返回最终结构化诊断。");
                expertRequest.getContext().put("previousDiagnosis", primaryResponse.getDiagnosis());
                ModelCallResult expertCall = modelGateway.complete(expertRequest);
                DiagnosisAnalysisResponse expertResponse = parse(expertCall.getContent());
                evidenceReferenceValidator.validateOrThrow(expertResponse, evidence);
                return GenerationResult.builder()
                        .response(expertResponse)
                        .modelCall(expertCall)
                        .primaryModel(primaryCall.getSelectedModel())
                        .evidenceSynthesisModel(evidenceSynthesis == null ? null : evidenceSynthesis.getSelectedModel())
                        .escalatedModel(expertCall.getSelectedModel())
                        .escalationReason(escalationPolicy.reason(postDiagnosisContext))
                        .escalated(true)
                        .build();
            } catch (ModelCallException | EvidenceReferenceValidator.InvalidEvidenceReferenceException exception) {
                // 专家升级失败不丢弃已经通过 Evidence 校验的初次结果；调用元数据会记录失败尝试。
                return GenerationResult.builder()
                        .response(primaryResponse)
                        .modelCall(primaryCall)
                        .primaryModel(primaryCall.getSelectedModel())
                        .evidenceSynthesisModel(evidenceSynthesis == null ? null : evidenceSynthesis.getSelectedModel())
                        .escalationReason(escalationPolicy.reason(postDiagnosisContext))
                        .escalated(false)
                        .build();
            }
        }
        return GenerationResult.builder()
                .response(primaryResponse)
                .modelCall(primaryCall)
                .primaryModel(primaryCall.getSelectedModel())
                .evidenceSynthesisModel(evidenceSynthesis == null ? null : evidenceSynthesis.getSelectedModel())
                .escalated(false)
                .build();
    }

    private GenerationResult repairOnce(DiagnosisAnalyzeRequest request,
                                        String runId,
                                        List<DiagnosisEvidenceVO> evidence,
                                        ModelCallResult primaryCall) {
        ModelCallRequest repairRequest = buildRequest(request, runId, evidence,
                AiTaskType.GROUNDED_GENERATION,
                "上一轮结构化结果未通过格式或 Evidence 引用校验。请只修复 JSON 格式和 evidence_ids，返回最终 JSON，不要新增任何来源。");
        repairRequest.setImageUrls(new ArrayList<>());
        repairRequest.setUserPrompt(repairRequest.getUserPrompt()
                + "\n上一轮结果仅作为待修复文本，不要输出其中的隐藏推理：\n" + primaryCall.getContent());
        ModelCallResult repairCall = modelGateway.complete(repairRequest);
        DiagnosisAnalysisResponse repaired = parse(repairCall.getContent());
        evidenceReferenceValidator.validateOrThrow(repaired, evidence);
        return GenerationResult.builder()
                .response(repaired)
                .modelCall(repairCall)
                .primaryModel(primaryCall.getSelectedModel())
                .evidenceSynthesisModel(null)
                .escalatedModel(repairCall.getSelectedModel())
                .escalationReason("structured_output_repair")
                .escalated(false)
                .build();
    }

    private ModelCallRequest buildRequest(DiagnosisAnalyzeRequest request,
                                          String runId,
                                          List<DiagnosisEvidenceVO> evidence,
                                          AiTaskType taskType,
                                          String instruction) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("fieldId", request.getFieldId());
        context.put("farmId", request.getFarmId());
        context.put("crop", request.getCrop());
        context.put("growthStage", request.getGrowthStage());
        context.put("imageCount", request.getImageUrls() == null ? 0 : request.getImageUrls().size());
        context.put("enabledModalities", request.getEnabledModalities());
        context.put("modelPolicy", request.getModelPolicy() == null ? "default" : request.getModelPolicy());
        context.put("weather", request.getWeather());
        context.put("knowledge", evidence.stream().filter(item -> "knowledge".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList()));
        context.put("historical", evidence.stream().filter(item -> "historical_case".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList()));
        context.put("soil", evidence.stream().filter(item -> "soil".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList()));
        context.put("spatial", evidence.stream().filter(item -> "spatial".equals(item.getModality())).map(DiagnosisEvidenceVO::getId).collect(Collectors.toList()));

        return ModelCallRequest.builder()
                .requestId(runId)
                .runId(runId)
                .taskType(taskType)
                .systemPrompt("你是 FarMap 的 GroundedDiagnosisGenerator。只使用输入中的 Evidence，不得创造来源或 evidence_id。只返回 JSON，不要输出 Chain-of-Thought、hidden reasoning 或思考过程。每个 Claim 和 Recommendation 必须引用已存在的 evidence_ids。")
                .userPrompt(instruction + "\nField Context:\n" + toJson(context) + "\nEvidence:\n" + toJson(promptEvidence(evidence)) + "\nJSON schema:\n" + schema())
                .imageUrls(request.getImageUrls() == null ? new ArrayList<>() : request.getImageUrls())
                .structuredOutput(true)
                .context(context)
                .build();
    }

    /** Keep image bytes in multimodal content, never duplicate them in text JSON. */
    private List<Map<String, Object>> promptEvidence(List<DiagnosisEvidenceVO> evidence) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DiagnosisEvidenceVO item : evidence) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", item.getId());
            normalized.put("modality", item.getModality());
            normalized.put("title", item.getTitle());
            normalized.put("summary", item.getSummary());
            normalized.put("score", item.getScore());
            normalized.put("source", item.getSource());
            normalized.put("metadata", item.getMetadata());
            if (item.getPreview() != null) {
                Map<String, Object> preview = new LinkedHashMap<>();
                if (item.getPreview().getImageUrl() != null && !item.getPreview().getImageUrl().trim().isEmpty()) {
                    preview.put("imageAttached", true);
                }
                preview.put("chartData", item.getPreview().getChartData());
                preview.put("textSnippet", item.getPreview().getTextSnippet());
                normalized.put("preview", preview);
            }
            result.add(normalized);
        }
        return result;
    }

    private DiagnosisAnalysisResponse parse(String content) {
        try {
            String normalized = content == null ? "" : content.trim();
            if (normalized.startsWith("```")) {
                normalized = normalized.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
            }
            try {
                return readDiagnosis(normalized);
            } catch (Exception directParseException) {
                // Providers may still wrap a valid JSON object in a short preamble or suffix.
                // Recover only the first balanced JSON object; never treat surrounding text as data.
                String jsonObject = firstJsonObject(normalized);
                if (jsonObject == null) throw directParseException;
                return readDiagnosis(jsonObject);
            }
        } catch (Exception exception) {
            throw new ModelCallException("invalid_structured_output",
                    "模型未返回合法结构化诊断", exception);
        }
    }

    private String firstJsonObject(String content) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (depth == 0) start = index;
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0) return content.substring(start, index + 1);
            }
        }
        return null;
    }

    private DiagnosisAnalysisResponse readDiagnosis(String json) throws Exception {
        // The provider may add forward-compatible fields (for example visual_findings).
        // Unknown fields are ignored at this boundary; required Claim/Recommendation
        // references are still enforced by EvidenceReferenceValidator immediately after.
        JsonNode tree = objectMapper.readTree(json);
        normalizeAlternativeNames(tree);
        return objectMapper.readerFor(DiagnosisAnalysisResponse.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(tree.traverse(objectMapper));
    }

    /** Accept the common provider shorthand: alternatives: ["hypothesis"]. */
    private void normalizeAlternativeNames(JsonNode root) {
        if (root == null || !root.isObject()) return;
        JsonNode diagnosisNode = root.get("diagnosis");
        if (diagnosisNode == null || !diagnosisNode.isObject()) return;
        JsonNode alternativesNode = diagnosisNode.get("alternatives");
        if (alternativesNode == null || !alternativesNode.isArray()) return;
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode item : alternativesNode) {
            if (item.isTextual()) {
                ObjectNode alternative = objectMapper.createObjectNode();
                alternative.put("name", item.asText());
                normalized.add(alternative);
            } else {
                normalized.add(item);
            }
        }
        ((ObjectNode) diagnosisNode).set("alternatives", normalized);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ModelCallException("prompt_serialization_error", "诊断上下文序列化失败", exception);
        }
    }

    private String schema() {
        return "{diagnosis:{title,risk_level,confidence,summary,alternatives},claims:[{id,text,evidence_ids}],recommendations:[{id,title,priority,detail,evidence_ids}]}";
    }

    @Value
    @Builder
    public static class GenerationResult {
        DiagnosisAnalysisResponse response;
        ModelCallResult modelCall;
        String primaryModel;
        String evidenceSynthesisModel;
        String escalatedModel;
        String escalationReason;
        boolean escalated;
    }
}

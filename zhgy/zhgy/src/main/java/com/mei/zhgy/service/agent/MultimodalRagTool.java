package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.rag.DiagnosisRagService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MultimodalRagTool implements AgentTool {
    private final DiagnosisRagService ragService;
    public MultimodalRagTool(DiagnosisRagService ragService) { this.ragService = ragService; }
    @Override public String name() { return "multimodal-rag"; }
    @Override public String description() { return "复用现有 DiagnosisRagService，汇总视觉、天气、知识与历史证据"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        try {
            if (request.getContext() == null || request.getContext().imageCount() == 0) return ToolResult.unavailable("RAG_IMAGE_INPUT_EMPTY", "多模态 RAG 需要至少一张图像");
            DiagnosisAnalysisResponse response = ragService.analyze(AgentDiagnosisRequestFactory.create(request), 0L);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("diagnosis", response.getDiagnosis()); output.put("claims", response.getClaims()); output.put("recommendations", response.getRecommendations());
            output.put("retrievalSummary", response.getRetrievalSummary());
            return ToolResult.builder().output(output).evidence(response.getEvidence()).metadata(response.getMetadata() == null ? new LinkedHashMap<>() : response.getMetadata()).build();
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ToolResult.unavailable("RAG_UNAVAILABLE", error.getMessage());
        } catch (Exception error) {
            return ToolResult.failed("RAG_FAILED", error.getMessage());
        }
    }
}

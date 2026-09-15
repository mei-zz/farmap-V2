package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeTool implements AgentTool {
    private final KnowledgeEvidenceRetriever retriever;
    public KnowledgeTool(KnowledgeEvidenceRetriever retriever) { this.retriever = retriever; }
    @Override public String name() { return "knowledge"; }
    @Override public String description() { return "检索本地农业知识库并返回可引用证据"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        try {
            KnowledgeEvidenceRetriever.RetrievalResult result = retriever.retrieveWithStatus(AgentDiagnosisRequestFactory.create(request), request.getOwnerUserId());
            List<DiagnosisEvidenceVO> evidence = result.getEvidence();
            if ("UNAVAILABLE".equals(result.getStatus())) return ToolResult.unavailable("KNOWLEDGE_UNAVAILABLE", "农业知识库暂不可用");
            if (evidence.isEmpty()) return ToolResult.builder().evidence(evidence).output(Map.of("count", 0, "status", "NO_KNOWLEDGE_AVAILABLE")).metadata(Map.of("sourceMode", result.getStatus(), "knowledgeStatus", "READY_EMPTY")).build();
            return ToolResult.builder().evidence(evidence).output(Map.of("count", evidence.size())).metadata(Map.of("sourceMode", result.getStatus(), "knowledgeStatus", result.getStatus())).build();
        } catch (Exception error) { return ToolResult.unavailable("KNOWLEDGE_UNAVAILABLE", "农业知识库暂不可用"); }
    }
}

package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.rag.HistoricalCaseEvidenceRetriever;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HistoricalCaseTool implements AgentTool {
    private final HistoricalCaseEvidenceRetriever retriever;
    public HistoricalCaseTool(HistoricalCaseEvidenceRetriever retriever) { this.retriever = retriever; }
    @Override public String name() { return "historical-case"; }
    @Override public String description() { return "读取专家历史案例；向量数据为空时返回明确不可用状态"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        try {
            List<DiagnosisEvidenceVO> evidence = retriever.retrieve(AgentDiagnosisRequestFactory.create(request));
            if (evidence.isEmpty()) return ToolResult.unavailable("HISTORICAL_VECTOR_DATA_EMPTY", "历史案例向量数据为空，继续执行其他工具");
            return ToolResult.builder().evidence(evidence).output(Map.of("count", evidence.size())).metadata(Map.of("sourceMode", "LOCAL")).build();
        } catch (Exception error) {
            return ToolResult.unavailable("HISTORICAL_VECTOR_DATA_EMPTY", "历史案例暂不可用，继续执行其他工具");
        }
    }
}

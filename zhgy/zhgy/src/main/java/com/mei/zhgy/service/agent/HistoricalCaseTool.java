package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.rag.HistoricalCaseEvidenceRetriever;
import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HistoricalCaseTool implements AgentTool {
    private final HistoricalCaseEvidenceRetriever retriever;
    private final HistoricalVectorStoreInitializer initializer;
    public HistoricalCaseTool(HistoricalCaseEvidenceRetriever retriever) { this(retriever, null); }
    @Autowired
    public HistoricalCaseTool(HistoricalCaseEvidenceRetriever retriever, HistoricalVectorStoreInitializer initializer) {
        this.retriever = retriever;
        this.initializer = initializer;
    }
    @Override public String name() { return "historical-case"; }
    @Override public String description() { return "读取专家历史案例；集合可用但无数据时返回 EMPTY，不阻断 Agent"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        try {
            List<DiagnosisEvidenceVO> evidence = retriever.retrieve(AgentDiagnosisRequestFactory.create(request));
            Map<String, Object> state = initializer == null ? Map.of("status", "UNAVAILABLE") : initializer.initialize(false);
            if ("READY".equals(state.get("status")) && evidence.isEmpty()) {
                long rows = ((Number) state.getOrDefault("rowCount", 0L)).longValue();
                if (rows == 0L) {
                    return ToolResult.builder()
                            .status(ToolStatus.COMPLETED)
                            .output(Map.of("count", 0, "status", "EMPTY", "reason", "NO_HISTORICAL_CASES"))
                            .metadata(Map.of("sourceMode", "LOCAL", "historicalStatus", "READY_EMPTY"))
                            .build();
                }
                return ToolResult.builder().status(ToolStatus.COMPLETED).output(Map.of("count", 0)).metadata(Map.of("sourceMode", "LOCAL")).build();
            }
            if (!"READY".equals(state.get("status")) && evidence.isEmpty()) {
                return ToolResult.unavailable("HISTORICAL_VECTOR_STORE_UNAVAILABLE", "历史案例向量库暂不可用，继续执行其他工具");
            }
            return ToolResult.builder().evidence(evidence).output(Map.of("count", evidence.size())).metadata(Map.of("sourceMode", "LOCAL")).build();
        } catch (Exception error) {
            return ToolResult.unavailable("HISTORICAL_VECTOR_STORE_UNAVAILABLE", "历史案例向量库暂不可用，继续执行其他工具");
        }
    }
}

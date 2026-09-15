package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.diagnosis.JdbcDiagnosisRepository;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** MySQL boundary test for persisted Diagnosis snapshots and task provenance. */
class ProductLoopRelationshipMysqlTest {
    @Test
    void mysqlPersistsRecommendationProvenanceAndKeepsLegacyNullReadable() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")),
                "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String actionId = "relationship-action-" + UUID.randomUUID();
        String legacyActionId = "relationship-legacy-" + UUID.randomUUID();
        String diagnosisId = null;
        try {
            JdbcDiagnosisRepository repository = new JdbcDiagnosisRepository(dataSource, mapper);
            repository.ensureTable();
            DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
            request.setFieldId("mysql-relationship-field"); request.setFarmId(7); request.setCrop("水稻");
            DiagnosisAnalysisResponse saved = new DiagnosisPersistenceService(repository).persist(response(), request, 42L);
            diagnosisId = saved.getDiagnosisId();
            assertTrue(new DiagnosisPersistenceService(new JdbcDiagnosisRepository(dataSource, mapper))
                    .findRecommendation(diagnosisId, "recommendation-mysql", 42L).isPresent());

            OperationsTaskService tasks = new OperationsTaskService(dataSource, mapper);
            AgentContext context = AgentContext.builder().diagnosisId(diagnosisId).sourceRecommendationId("recommendation-mysql")
                    .fieldId("mysql-relationship-field").build();
            AgentToolRequest requestWithProvenance = AgentToolRequest.builder().runId("relationship-run").actionId(actionId)
                    .diagnosisId(diagnosisId).recommendationId("recommendation-mysql").approvalGranted(true).context(context).build();
            Map<String, Object> first = tasks.createTask(requestWithProvenance);
            Map<String, Object> retry = tasks.createTask(requestWithProvenance);
            assertEquals(first.get("taskId"), retry.get("taskId"));
            assertEquals(diagnosisId, jdbc.queryForObject("SELECT diagnosis_id FROM agent_operations_tasks WHERE action_id=?", String.class, actionId));
            assertEquals("recommendation-mysql", jdbc.queryForObject("SELECT recommendation_id FROM agent_operations_tasks WHERE action_id=?", String.class, actionId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_operations_tasks WHERE action_id=?", Integer.class, actionId));

            Map<String, Object> legacy = tasks.createTask(AgentToolRequest.builder().runId("legacy-run").actionId(legacyActionId)
                    .approvalGranted(true).context(AgentContext.builder().diagnosisId(diagnosisId).fieldId("mysql-relationship-field").build()).build());
            assertNull(legacy.get("recommendationId"));
            assertNull(jdbc.queryForObject("SELECT recommendation_id FROM agent_operations_tasks WHERE action_id=?", String.class, legacyActionId));
        } finally {
            jdbc.update("DELETE FROM agent_operations_tasks WHERE action_id IN (?,?)", actionId, legacyActionId);
            if (diagnosisId != null) jdbc.update("DELETE FROM agent_diagnoses WHERE diagnosis_id=?", diagnosisId);
        }
    }

    private DiagnosisAnalysisResponse response() {
        return DiagnosisAnalysisResponse.builder().runId("relationship-source-" + UUID.randomUUID())
                .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("叶片黄化").riskLevel("medium").confidence(0.91).summary("需要复核").alternatives(List.of()).build())
                .claims(List.of(DiagnosisAnalysisResponse.Claim.builder().id("claim-mysql").text("存在风险").evidenceIds(List.of("evidence-mysql")).build()))
                .evidence(List.of(DiagnosisEvidenceVO.builder().id("evidence-mysql").modality("camera").title("图像").summary("黄化").build()))
                .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder().id("recommendation-mysql").title("现场复核").priority("high").detail("取样").evidenceIds(List.of("evidence-mysql")).build()))
                .retrievalSummary(Map.of("evidence_count", 1)).metadata(Map.of("mode", "real")).build();
    }
}

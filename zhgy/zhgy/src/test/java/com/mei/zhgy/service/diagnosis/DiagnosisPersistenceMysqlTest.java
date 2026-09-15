package com.mei.zhgy.service.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
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

class DiagnosisPersistenceMysqlTest {
    @Test
    void mysqlSnapshotSurvivesRepositoryReplacement() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")), "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String runId = "rag-mysql-" + UUID.randomUUID();
        String diagnosisId = null;
        try {
            JdbcDiagnosisRepository repository = new JdbcDiagnosisRepository(dataSource, new ObjectMapper().findAndRegisterModules());
            repository.ensureTable();
            DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
            request.setFieldId("mysql-field"); request.setFarmId(7); request.setCrop("水稻");
            DiagnosisAnalysisResponse saved = new DiagnosisPersistenceService(repository).persist(response(runId), request, 42L);
            diagnosisId = saved.getDiagnosisId();
            DiagnosisAnalysisResponse reloaded = new DiagnosisPersistenceService(new JdbcDiagnosisRepository(dataSource, new ObjectMapper().findAndRegisterModules()))
                    .find(diagnosisId, 42L).orElseThrow();
            assertNotEquals(runId, diagnosisId);
            assertEquals(saved.getSourceRunId(), reloaded.getSourceRunId());
            assertEquals(saved.getEvidence().get(0).getId(), reloaded.getEvidence().get(0).getId());
            assertEquals(saved.getClaims().get(0).getId(), reloaded.getClaims().get(0).getId());
            assertEquals(saved.getRecommendations().get(0).getId(), reloaded.getRecommendations().get(0).getId());
        } finally {
            if (diagnosisId != null) jdbc.update("DELETE FROM agent_diagnoses WHERE diagnosis_id=?", diagnosisId);
        }
    }

    private DiagnosisAnalysisResponse response(String runId) {
        return DiagnosisAnalysisResponse.builder().runId(runId)
                .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("叶片黄化").riskLevel("medium").confidence(0.91).summary("需要复核").alternatives(List.of()).build())
                .claims(List.of(DiagnosisAnalysisResponse.Claim.builder().id("claim-mysql").text("存在风险").evidenceIds(List.of("evidence-mysql")).build()))
                .evidence(List.of(DiagnosisEvidenceVO.builder().id("evidence-mysql").modality("camera").title("图像").summary("黄化").build()))
                .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder().id("recommendation-mysql").title("现场复核").priority("high").detail("取样").evidenceIds(List.of("evidence-mysql")).build()))
                .retrievalSummary(Map.of("evidence_count", 1)).metadata(Map.of("mode", "real")).build();
    }
}

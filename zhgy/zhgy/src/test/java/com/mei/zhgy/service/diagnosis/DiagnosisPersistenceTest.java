package com.mei.zhgy.service.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.controller.DiagnosisQueryController;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosisPersistenceTest {
    @Test
    void createsIndependentStableIdentityAndReloadsExactSnapshot() {
        InMemoryDiagnosisRepository repository = new InMemoryDiagnosisRepository();
        DiagnosisPersistenceService first = new DiagnosisPersistenceService(repository);
        DiagnosisAnalysisResponse response = response("rag-source-1");
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId("A-12"); request.setFarmId(7); request.setCrop("水稻");

        DiagnosisAnalysisResponse saved = first.persist(response, request, 42L);
        assertNotNull(saved.getDiagnosisId());
        assertNotEquals(saved.getRunId(), saved.getDiagnosisId());
        assertEquals(saved.getRunId(), saved.getSourceRunId());
        assertEquals("COMPLETED", saved.getStatus());

        DiagnosisAnalysisResponse reloaded = new DiagnosisPersistenceService(repository)
                .find(saved.getDiagnosisId(), 42L).orElseThrow();
        assertEquals(saved.getDiagnosisId(), reloaded.getDiagnosisId());
        assertEquals(saved.getSourceRunId(), reloaded.getSourceRunId());
        assertEquals(saved.getEvidence().get(0).getId(), reloaded.getEvidence().get(0).getId());
        assertEquals(saved.getClaims().get(0).getId(), reloaded.getClaims().get(0).getId());
        assertEquals(saved.getRecommendations().get(0).getId(), reloaded.getRecommendations().get(0).getId());
        assertEquals(saved.getDiagnosis().getSummary(), reloaded.getDiagnosis().getSummary());
    }

    @Test
    void unavailablePersistenceFailsClosed() {
        DiagnosisPersistenceService service = new DiagnosisPersistenceService((DataSource) null, new ObjectMapper());
        DiagnosisPersistenceException error = assertThrows(DiagnosisPersistenceException.class,
                () -> service.persist(response("rag-unavailable"), request(), 42L));
        assertEquals("DIAGNOSIS_PERSISTENCE_UNAVAILABLE", error.getCode());
    }

    @Test
    void unknownOrWrongOwnerIdentityDoesNotResolve() {
        InMemoryDiagnosisRepository repository = new InMemoryDiagnosisRepository();
        DiagnosisPersistenceService service = new DiagnosisPersistenceService(repository);
        DiagnosisAnalysisResponse saved = service.persist(response("rag-owner"), request(), 42L);
        assertTrue(service.find("diagnosis-does-not-exist", 42L).isEmpty());
        assertTrue(service.find(saved.getDiagnosisId(), 99L).isEmpty());
    }

    @Test
    void operationsUsesStableDiagnosisIdBeforeLegacyMap() {
        AgentToolRequest request = AgentToolRequest.builder().runId("agent-1").actionId("action-1").approvalGranted(true)
                .goal("生成复核任务").context(AgentContext.builder().fieldId("A-12").diagnosisId("diagnosis-1")
                        .diagnosis(Map.of("id", "rag-legacy")).build()).build();
        Map<String, Object> task = new OperationsTaskService().createTask(request);
        assertEquals("diagnosis-1", task.get("diagnosisId"));
    }

    @Test
    void queryReturnsResourceNotFoundForUnknownIdentity() {
        BaseContext.setCurrentId(42L);
        try {
            DiagnosisQueryController controller = new DiagnosisQueryController(new DiagnosisPersistenceService(new InMemoryDiagnosisRepository()));
            assertEquals(404, controller.get("diagnosis-missing").getStatusCodeValue());
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    private DiagnosisAnalyzeRequest request() {
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId("A-12"); request.setFarmId(7); request.setCrop("水稻");
        return request;
    }

    private DiagnosisAnalysisResponse response(String runId) {
        DiagnosisEvidenceVO evidence = DiagnosisEvidenceVO.builder().id("evidence-1").modality("camera").title("叶片图像")
                .summary("叶片出现黄化").score(0.91).build();
        return DiagnosisAnalysisResponse.builder().runId(runId)
                .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("叶片黄化").riskLevel("medium").confidence(0.91)
                        .summary("需要进一步复核氮素供给").alternatives(List.of()).build())
                .claims(List.of(DiagnosisAnalysisResponse.Claim.builder().id("claim-1").text("叶片黄化与氮素不足相关").evidenceIds(List.of("evidence-1")).build()))
                .evidence(List.of(evidence))
                .recommendations(List.of(DiagnosisAnalysisResponse.Recommendation.builder().id("recommendation-1").title("复核施肥").priority("high").detail("现场取样").evidenceIds(List.of("evidence-1")).build()))
                .retrievalSummary(Map.of("evidence_count", 1)).metadata(Map.of("mode", "real")).build();
    }
}

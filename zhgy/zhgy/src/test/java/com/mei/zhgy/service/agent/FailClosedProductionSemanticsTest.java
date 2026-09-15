package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceException;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.expert.ExpertReviewV2Service;
import com.mei.zhgy.service.expert.ExpertReviewWriteException;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.knowledge.KnowledgeBackendService;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.operations.OperationsWriteException;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Production constructors must reject writes and reads when no durable DB is available. */
class FailClosedProductionSemanticsTest {
    @Test
    void criticalWritesDoNotPretendToPersistInMemory() {
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(); request.setFieldId("failure-field");
        DiagnosisAnalysisResponse response = DiagnosisAnalysisResponse.builder().runId("source-failure").build();
        DiagnosisPersistenceException diagnosis = assertThrows(DiagnosisPersistenceException.class, () -> new com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService((javax.sql.DataSource) null, new ObjectMapper()).persist(response, request, 42L));
        assertEquals("DIAGNOSIS_PERSISTENCE_UNAVAILABLE", diagnosis.getCode());

        OperationsWriteException task = assertThrows(OperationsWriteException.class, () -> new OperationsTaskService(null, new ObjectMapper()).createTask(AgentToolRequest.builder().runId("run-failure").actionId("action-failure").approvalGranted(true).context(AgentContext.builder().fieldId("field").build()).build()));
        assertEquals("OPERATIONS_PERSISTENCE_UNAVAILABLE", task.getCode());
        ApprovalException approval = assertThrows(ApprovalException.class, () -> new ApprovalManager((javax.sql.DataSource) null).find("run-failure", "action-failure"));
        assertEquals("PERSISTENCE_UNAVAILABLE", approval.getCode());

        KnowledgeBackendException knowledge = assertThrows(KnowledgeBackendException.class, () -> new KnowledgeBackendService(null, new ObjectMapper(), false).createSource(Map.of("name", "failure"), 42L));
        assertEquals("KNOWLEDGE_PERSISTENCE_UNAVAILABLE", knowledge.getCode());
        ExpertReviewWriteException review = assertThrows(ExpertReviewWriteException.class, () -> new ExpertReviewV2Service(null, new ObjectMapper(), null, new OperationsTaskService(), false).create(Map.of("diagnosisId", "d", "scope", "DIAGNOSIS_ONLY", "diagnosisJudgment", "CORRECT", "expertConfidence", .8), 42L));
        assertEquals("EXPERT_REVIEW_PERSISTENCE_UNAVAILABLE", review.getCode());
    }

    @Test
    void criticalReadsDoNotTurnMissingProductionDbIntoEmptySuccess() {
        OperationsTaskService operations = new OperationsTaskService(null, new ObjectMapper());
        assertEquals("OPERATIONS_PERSISTENCE_UNAVAILABLE", assertThrows(OperationsWriteException.class, () -> operations.listTasks(42L)).getCode());
        ExpertReviewV2Service reviews = new ExpertReviewV2Service(null, new ObjectMapper(), null, operations, false);
        assertEquals("EXPERT_REVIEW_PERSISTENCE_UNAVAILABLE", assertThrows(ExpertReviewWriteException.class, () -> reviews.list(42L, null)).getCode());
        HistoricalCaseCandidateService candidates = new HistoricalCaseCandidateService(null, new ObjectMapper(), reviews, operations, false);
        assertEquals("EXPERT_REVIEW_PERSISTENCE_UNAVAILABLE", assertThrows(ExpertReviewWriteException.class, () -> candidates.list(42L, null)).getCode());
    }
}

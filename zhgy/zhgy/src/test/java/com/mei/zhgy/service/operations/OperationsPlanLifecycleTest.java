package com.mei.zhgy.service.operations;

import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentToolRequest;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.maturity.FarmMaturityService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationsPlanLifecycleTest {
    private static final Long OWNER = 7L;
    private static final String RUN_ID = "run-maturity-1";
    private static final String FIELD_ID = "field-1";

    private final FarmMaturityService maturity = mock(FarmMaturityService.class);
    private final FarmFieldService fields = mock(FarmFieldService.class);
    private final OperationsTaskService tasks = new OperationsTaskService();
    private final OperationsPlanService plans = new OperationsPlanService(maturity, fields, tasks);

    @Test
    void createsOneOwnerScopedDraftForSuccessfulRipeObservation() {
        when(maturity.run(RUN_ID, OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("MYSQL"));

        Map<String, Object> first = plans.fromAnalysis(RUN_ID, FIELD_ID, OWNER);
        Map<String, Object> replay = plans.fromAnalysis(RUN_ID, FIELD_ID, OWNER);

        assertEquals(first.get("draftId"), replay.get("draftId"));
        assertEquals("DRAFT", first.get("status"));
        assertEquals(RUN_ID, first.get("analysisRunId"));
        assertEquals(10, first.get("farmId"));
        assertTrue(String.valueOf(first.get("title")).contains("实地复核"));
        assertTrue(String.valueOf(first.get("detail")).contains("现场复核"));
        assertFalse(first.containsKey("ownerUserId"));
        assertFalse(first.containsKey("taskId"));
        assertEquals(1, plans.listDrafts(RUN_ID, OWNER).size());
        assertTrue(tasks.listTasks(OWNER).isEmpty(), "draft creation must not create an Operations task");
    }

    @Test
    void rejectsNonMaturityUnknownUnassignedAndFarmMismatch() {
        Map<String, Object> nonMaturity = report(false, "SUCCESS", "RIPE", "FARM");
        nonMaturity.put("kind", "PHENOLOGY");
        when(maturity.run("run-non-maturity", OWNER)).thenReturn(nonMaturity);
        assertCode("PLAN_ANALYSIS_NOT_MATURITY", () -> plans.fromAnalysis("run-non-maturity", FIELD_ID, OWNER));

        when(maturity.run("run-unknown", OWNER)).thenReturn(report(false, "SUCCESS", "UNKNOWN", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("MYSQL"));
        assertCode("PLAN_FIELD_NOT_ELIGIBLE", () -> plans.fromAnalysis("run-unknown", FIELD_ID, OWNER));

        when(maturity.run("run-unassigned", OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "UNASSIGNED_CAMERA"));
        assertCode("PLAN_FIELD_NOT_ELIGIBLE", () -> plans.fromAnalysis("run-unassigned", FIELD_ID, OWNER));

        when(maturity.run("run-offline", OWNER)).thenReturn(report(false, "OFFLINE", "RIPE", "FARM"));
        assertCode("PLAN_FIELD_NOT_ELIGIBLE", () -> plans.fromAnalysis("run-offline", FIELD_ID, OWNER));

        when(maturity.run("run-other-farm", OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("MYSQL", 11));
        assertCode("PLAN_FIELD_MISMATCH", () -> plans.fromAnalysis("run-other-farm", FIELD_ID, OWNER));
    }

    @Test
    void allowsSyntheticFieldOnlyForDemoReportAndLabelsTheDraft() {
        when(maturity.run("run-real", OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("SYNTHETIC_PRESENTATION"));
        assertCode("PLAN_FIELD_NOT_ELIGIBLE", () -> plans.fromAnalysis("run-real", FIELD_ID, OWNER));

        when(maturity.run(RUN_ID, OWNER)).thenReturn(report(true, "SUCCESS", "OVER_RIPE", "DEMO"));
        Map<String, Object> draft = plans.fromAnalysis(RUN_ID, FIELD_ID, OWNER);
        assertTrue(String.valueOf(draft.get("title")).startsWith("【演示数据】"));
        assertTrue(String.valueOf(draft.get("detail")).contains("仅供本地演示"));
    }

    @Test
    void saveAndConfirmPreserveApprovedFieldsAndReplayOneTask() {
        when(maturity.run(RUN_ID, OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("MYSQL"));
        Map<String, Object> draft = plans.fromAnalysis(RUN_ID, FIELD_ID, OWNER);
        String draftId = String.valueOf(draft.get("draftId"));

        Map<String, Object> saved = plans.save(draftId, OWNER,
                new OperationsPlanService.DraftEdit(0L, "复核脆李成熟度", "请到东坡实地复核果实成熟度。", "MEDIUM", "2026-11-25"));
        assertEquals(1L, saved.get("version"));
        assertEquals("DRAFT", saved.get("status"));

        Map<String, Object> confirmed = plans.confirm(draftId, OWNER,
                new OperationsPlanService.DraftEdit(1L, "复核脆李成熟度", "请到东坡实地复核果实成熟度。", "MEDIUM", "2026-11-25"));
        String taskId = String.valueOf(confirmed.get("taskId"));
        assertNotNull(taskId);
        assertEquals("CONFIRMED", confirmed.get("status"));
        assertEquals(2L, confirmed.get("version"));

        Map<String, Object> task = tasks.getTask(taskId, OWNER);
        assertEquals("复核脆李成熟度", task.get("title"));
        assertEquals("MEDIUM", task.get("priority"));
        assertEquals("2026-11-25", task.get("scheduledFor"));
        assertEquals(RUN_ID, task.get("analysisRunId"));
        assertEquals("请到东坡实地复核果实成熟度。", task.get("goal"));

        Map<String, Object> replay = plans.confirm(draftId, OWNER,
                new OperationsPlanService.DraftEdit(1L, "复核脆李成熟度", "请到东坡实地复核果实成熟度。", "MEDIUM", "2026-11-25"));
        assertEquals(taskId, replay.get("taskId"));
        assertEquals(1, tasks.listTasks(OWNER).size());
        assertEquals(1, tasks.listEvents(taskId, OWNER).stream().filter(event -> "TASK_CREATED".equals(event.get("eventType"))).count());
    }

    @Test
    void concurrentConfirmReturnsOneDurableTask() throws Exception {
        when(maturity.run(RUN_ID, OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("MYSQL"));
        String draftId = String.valueOf(plans.fromAnalysis(RUN_ID, FIELD_ID, OWNER).get("draftId"));
        OperationsPlanService.DraftEdit edit = new OperationsPlanService.DraftEdit(0L, "并发复核", "现场复核成熟度", "HIGH", null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = pool.invokeAll(List.of(
                    (Callable<Map<String, Object>>) () -> plans.confirm(draftId, OWNER, edit),
                    (Callable<Map<String, Object>>) () -> plans.confirm(draftId, OWNER, edit)));
            List<Map<String, Object>> results = new ArrayList<>();
            for (var future : futures) results.add(future.get());
            assertEquals(1, results.stream().map(result -> result.get("taskId")).distinct().count());
            assertEquals(1, tasks.listTasks(OWNER).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleSaveCannotOverwriteConfirmedDraft() {
        when(maturity.run(RUN_ID, OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("MYSQL"));
        String draftId = String.valueOf(plans.fromAnalysis(RUN_ID, FIELD_ID, OWNER).get("draftId"));
        plans.confirm(draftId, OWNER,
                new OperationsPlanService.DraftEdit(0L, "确认标题", "确认说明", "HIGH", null));
        assertCode("PLAN_DRAFT_ALREADY_CONFIRMED", () -> plans.save(draftId, OWNER,
                new OperationsPlanService.DraftEdit(1L, "迟到标题", "迟到说明", "LOW", null)));
    }

    @Test
    void anotherOwnerCannotReadTheDraft() {
        when(maturity.run(RUN_ID, OWNER)).thenReturn(report(false, "SUCCESS", "RIPE", "FARM"));
        when(fields.get(FIELD_ID, OWNER)).thenReturn(field("MYSQL"));
        String draftId = String.valueOf(plans.fromAnalysis(RUN_ID, FIELD_ID, OWNER).get("draftId"));

        assertCode("FORBIDDEN", () -> plans.getDraft(draftId, 8L));
    }

    @Test
    void genericAgentAttributesCannotOverrideTrustedTaskFields() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "越权标题");
        attributes.put("priority", "LOW");
        attributes.put("scheduledFor", "2030-01-01");
        attributes.put("analysisRunId", "forged-run");
        Map<String, Object> task = tasks.createTask(AgentToolRequest.builder().runId("run-generic").actionId("generic-action")
                .ownerUserId(OWNER).actorId("7").goal("农事复核").approvalGranted(true).attributes(attributes)
                .context(AgentContext.builder().fieldId(FIELD_ID).fieldName("一号地").build()).build());

        assertEquals("农事任务 · 一号地", task.get("title"));
        assertEquals("HIGH", task.get("priority"));
        assertNull(task.get("scheduledFor"));
        assertNull(task.get("analysisRunId"));
    }

    private Map<String, Object> report(boolean demo, String observationStatus, String stage, String scope) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("status", observationStatus);
        observation.put("stage", stage);
        observation.put("source", "CAMERA");
        Map<String, Object> parcel = new LinkedHashMap<>();
        if (!"UNASSIGNED_CAMERA".equals(scope)) parcel.put("fieldId", FIELD_ID);
        parcel.put("name", "一号地");
        parcel.put("scope", scope);
        if (demo) {
            parcel.put("scope", "DEMO_MONITORING_REGION");
            parcel.put("topology", "DEMO");
            parcel.put("bindingSource", "CURATED_PRESENTATION_TOPOLOGY");
        }
        parcel.put("observations", List.of(observation));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", RUN_ID);
        report.put("farmId", 10);
        report.put("kind", "MATURITY");
        report.put("status", "COMPLETED");
        report.put("parcels", List.of(parcel));
        if (demo) report.put("topology", "DEMO");
        return report;
    }

    private FarmField field(String sourceMode) {
        return field(sourceMode, 10);
    }

    private FarmField field(String sourceMode, int farmId) {
        return FarmField.builder().fieldId(FIELD_ID).farmId(farmId).name("一号地").sourceMode(sourceMode).status("ACTIVE").build();
    }

    private void assertCode(String code, Runnable action) {
        OperationsWriteException error = assertThrows(OperationsWriteException.class, action::run);
        assertEquals(code, error.getCode());
    }
}

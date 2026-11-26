package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.copilot.CopilotApplicationService;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotResolutionStatus;
import com.mei.zhgy.service.copilot.CopilotRouteResult;
import com.mei.zhgy.service.copilot.CopilotRouteService;
import com.mei.zhgy.service.diagnosis.DiagnosisJobService;
import com.mei.zhgy.service.rag.DiagnosisRagService.RetrievalProgress;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CopilotDiagnosisProgressTest {
    @Test
    void asyncDiagnosisPublishesProgressBeforePersistedCards() throws Exception {
        var route = mock(CopilotRouteService.class);
        var jobs = mock(DiagnosisJobService.class);
        var submitted = new DiagnosisJobService.DiagnosisJobResponse();
        submitted.setJobId("job-demo");
        submitted.setStatus("QUEUED");
        var finished = new CompletableFuture<DiagnosisJobService.DiagnosisJobResponse>();
        var registered = new CountDownLatch(1);
        var observer = new AtomicReference<Consumer<RetrievalProgress>>();
        when(route.route(any(), eq(42L), eq(true))).thenReturn(CopilotRouteResult.builder()
                .intent(CopilotIntent.DIAGNOSIS_REQUEST).resolutionStatus(CopilotResolutionStatus.RESOLVED)
                .traceId("trace-deep-1").build());
        when(jobs.submit(any(), eq(42L))).thenReturn(submitted);
        when(jobs.completion("job-demo", 42L)).thenReturn(finished);
        when(jobs.subscribeProgress(eq("job-demo"), eq(42L), any())).thenAnswer(invocation -> {
            observer.set(invocation.getArgument(2));
            registered.countDown();
            return (Runnable) () -> {};
        });
        var service = new CopilotApplicationService(route, null, null, null, null, jobs, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "uploadBaseUrl", "http://localhost:24009/uploads");
        var mvc = MockMvcBuilders.standaloneSetup(new CopilotController(service)).build();
        BaseContext.setCurrentId(42L);
        try {
            var initial = mvc.perform(post("/api/copilot/chat").contentType("application/json").content("""
                    {"message":"生成正式诊断报告，只做分析，不创建农事任务","imageUrls":["http://localhost:24009/uploads/leaf.jpg"]}
                    """)).andExpect(request().asyncStarted()).andReturn();
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            observer.get().accept(new RetrievalProgress("RETRIEVAL", "camera", "READY", 1));
            observer.get().accept(new RetrievalProgress("RETRIEVAL", "historical_case", "READY", 3));
            assertFalse(finished.isDone());
            String running = initial.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(running.contains("diagnosis.progress"));
            assertFalse(running.contains("DiagnosisCard"));

            var result = DiagnosisAnalysisResponse.builder()
                    .diagnosis(DiagnosisAnalysisResponse.Diagnosis.builder().title("已复核的脆李诊断").build())
                    .evidence(List.of()).build();
            var terminal = new DiagnosisJobService.DiagnosisJobResponse();
            terminal.setDiagnosisId("diagnosis-persisted");
            terminal.setStatus("COMPLETED");
            terminal.setResult(result);
            finished.complete(terminal);
            String all = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(all.indexOf("diagnosis.progress") < all.indexOf("DiagnosisCard"));
            assertTrue(all.contains("PestComparisonCard"));
            assertTrue(all.contains("diagnosis-persisted"));
            assertTrue(all.contains("event:done"));
            verify(jobs, never()).get(anyString(), anyLong());
        } finally {
            BaseContext.removeCurrentId();
        }
    }
}

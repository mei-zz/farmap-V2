package com.mei.zhgy.service.operations;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.controller.OperationsController;
import com.mei.zhgy.service.WorkspaceAdminAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationsControllerOperationsApiTest {
    private final OperationsTaskService tasks = mock(OperationsTaskService.class);
    private final OperationsPlanService plans = mock(OperationsPlanService.class);
    private final OperationsTaskViewService views = mock(OperationsTaskViewService.class);
    private final WorkspaceAdminAccess admin = mock(WorkspaceAdminAccess.class);
    private final OperationsController controller = new OperationsController(tasks, plans, views, admin);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();

    @AfterEach
    void clearActor() {
        BaseContext.removeCurrentId();
    }

    @Test
    void exposesTaskViewWithFarmFieldAndOwnerContext() throws Exception {
        BaseContext.setCurrentId(7L);
        when(views.view(1, null, "2026-12-01", "2026-12-31", "calendar", 7L)).thenReturn(Map.of(
                "items", List.of(Map.of("taskId", "task-1", "fieldName", "一号地块")),
                "counts", Map.of("total", 1, "pending", 1, "inProgress", 0, "completed", 0),
                "truncated", false));

        mvc.perform(get("/api/operations/task-view")
                        .param("farmId", "1")
                .param("from", "2026-12-01")
                .param("to", "2026-12-31")
                .param("view", "calendar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.items[0].fieldName").value("一号地块"))
                .andExpect(jsonPath("$.data.counts.pending").value(1));
    }

    @Test
    void schedulesOneTaskWithOptimisticVersion() throws Exception {
        BaseContext.setCurrentId(7L);
        when(tasks.scheduleTask("task-1", "2026-12-01", 3L, 7L))
                .thenReturn(Map.of("taskId", "task-1", "scheduledFor", "2026-12-01", "version", 4L));

        mvc.perform(post("/api/operations/tasks/task-1/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledFor\":\"2026-12-01\",\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduledFor").value("2026-12-01"))
                .andExpect(jsonPath("$.data.version").value(4));
    }

    @Test
    void batchRouteRequiresAdminBeforeCallingOperations() {
        BaseContext.setCurrentId(7L);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden")).when(admin).requireAdmin();
        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> controller.scheduleBatch(new OperationsController.ScheduleBatchRequest()));
        verifyNoInteractions(tasks);
    }

    @Test
    void batchRouteMapsItemsToAtomicSchedulingCommands() throws Exception {
        BaseContext.setCurrentId(7L);
        doNothing().when(admin).requireAdmin();
        when(tasks.scheduleBatch(eq(1), anyList(), eq(7L)))
                .thenReturn(Map.of("items", List.of(), "updated", 1, "unchanged", 0));

        mvc.perform(post("/api/operations/tasks/schedule-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"farmId\":1,\"items\":[{\"taskId\":\"task-1\",\"scheduledFor\":\"2026-12-01\",\"version\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OperationsTaskService.ScheduleItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(tasks).scheduleBatch(eq(1), captor.capture(), eq(7L));
        assertEquals("task-1", captor.getValue().get(0).taskId());
        assertEquals("2026-12-01", captor.getValue().get(0).scheduledFor());
        assertEquals(3L, captor.getValue().get(0).version());
    }
}

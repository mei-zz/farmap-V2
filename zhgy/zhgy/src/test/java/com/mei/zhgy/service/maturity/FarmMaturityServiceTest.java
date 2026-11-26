package com.mei.zhgy.service.maturity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.ai.*;
import com.mei.zhgy.service.camera.*;
import com.mei.zhgy.service.field.*;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FarmMaturityServiceTest {
    @Test void failedCaptureDoesNotInventMaturityAndRetainsField() {
        FarmFieldService fields = mock(FarmFieldService.class);
        FarmCameraService cameras = mock(FarmCameraService.class);
        CameraSnapshotProvider shots = mock(CameraSnapshotProvider.class);
        ModelGateway model = mock(ModelGateway.class);
        when(fields.requireFarm(1, 2L)).thenReturn(UserLoginResponseVO.FarmVO.builder().name("农场").build());
        when(fields.list(1, 2L)).thenReturn(List.of(FarmField.builder().fieldId("f").name("地块").build()));
        FarmCamera c = camera("f");
        when(cameras.list(1, 2L)).thenReturn(List.of(c));
        when(shots.capture("c", 2L)).thenThrow(new IllegalStateException());
        var report = new FarmMaturityService(fields, cameras, shots, model, new ObjectMapper()).scan(1, 2L);
        assertEquals("UNKNOWN", report.parcels().get(0).get("stage"));
        verifyNoInteractions(model);
    }
    @Test void rejectsUnparseableAndPercentageBasedClaims() {
        FarmMaturityService service = new FarmMaturityService(null, null, null, null, new ObjectMapper());
        var shot = new CameraSnapshotProvider.Snapshot("https://example.org/image.jpg", "now", "camera");
        assertEquals(FarmMaturityService.Stage.UNKNOWN, service.evaluate(camera("f"), shot, ModelCallResult.builder().success(true).content("{\"stage\":\"RIPE\",\"reason\":\"成熟率90%\"}").build()).stage());
        assertEquals(FarmMaturityService.Stage.UNKNOWN, service.evaluate(camera("f"), shot, ModelCallResult.builder().success(true).content("not json").build()).stage());
        assertEquals(FarmMaturityService.Stage.UNKNOWN, service.evaluate(camera("f"), shot, ModelCallResult.builder().success(true).content("{\"fruitVisible\":false,\"stage\":\"RIPE\",\"reason\":\"远处有果树\"}").build()).stage());
        assertEquals(FarmMaturityService.Stage.RIPE, service.evaluate(camera("f"), shot, ModelCallResult.builder().success(true).content("{\"fruitVisible\":true,\"stage\":\"RIPE\",\"reason\":\"完整果实外观符合当前作物成熟期\"}").build()).stage());
    }

    @Test void analyzesAllCamerasIncludingRecentBrowserFrame() {
        FarmFieldService fields = mock(FarmFieldService.class);
        FarmCameraService cameras = mock(FarmCameraService.class);
        CameraSnapshotProvider shots = mock(CameraSnapshotProvider.class);
        ModelGateway model = mock(ModelGateway.class);
        when(fields.requireFarm(1, 2L)).thenReturn(UserLoginResponseVO.FarmVO.builder().name("农场").build());
        when(fields.list(1, 2L)).thenReturn(List.of(FarmField.builder().fieldId("f").name("地块").build()));
        List<FarmCamera> inventory = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> camera("camera-" + String.format("%02d", index), "f")).toList();
        when(cameras.list(1, 2L)).thenReturn(inventory);
        when(shots.hasRecentBrowserFrame(inventory.get(11))).thenReturn(true);
        when(shots.capture("camera-11", 2L)).thenReturn(new CameraSnapshotProvider.Snapshot("https://example.org/frame.jpg", "2026-11-21T00:00:00Z", "BROWSER_CAMERA_FRAME"));
        when(model.complete(any())).thenReturn(ModelCallResult.builder().success(true).selectedModel("deepseek-v4.1-flash")
                .content("{\"fruitVisible\":true,\"stage\":\"TURNING\",\"reason\":\"清晰可见果实开始转色\"}").build());
        var report = new FarmMaturityService(fields, cameras, shots, model, new ObjectMapper()).scan(1, 2L);
        assertEquals("TURNING", report.parcels().get(0).get("stage"));
        verify(shots).capture("camera-11", 2L);
        verify(shots, times(12)).capture(anyString(), eq(2L));
    }

    @Test void durableRunPersistsUnavailableCapturesWithoutInventingJudgments() {
        FarmFieldService fields = mock(FarmFieldService.class);
        FarmCameraService cameras = mock(FarmCameraService.class);
        CameraSnapshotProvider shots = mock(CameraSnapshotProvider.class);
        ModelGateway model = mock(ModelGateway.class);
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(fields.requireFarm(1, 2L)).thenReturn(UserLoginResponseVO.FarmVO.builder().name("农场").build());
        when(fields.list(1, 2L)).thenReturn(List.of(FarmField.builder().fieldId("f").name("地块").build()));
        when(cameras.list(1, 2L)).thenReturn(List.of(camera("f")));
        when(shots.capture("c", 2L)).thenThrow(new IllegalStateException());
        FarmMaturityService service = new FarmMaturityService(fields, cameras, shots, model, new ObjectMapper());
        service.setJdbc(jdbc);
        var report = service.analyze(1, 2L, List.of("c"), null, null, "MATURITY", "CAMERA");
        assertEquals("NO_VALID_OBSERVATIONS", report.get("status"));
        assertNotNull(report.get("runId"));
        verify(jdbc).update(startsWith("INSERT INTO maturity_analysis_runs"), any(), any(), any(), any(), any());
        verifyNoInteractions(model);
        verifyNoInteractions(shots);
    }

    @Test void explicitlyOfflineCameraDoesNotReachVisionModel() {
        FarmFieldService fields = mock(FarmFieldService.class);
        FarmCameraService cameras = mock(FarmCameraService.class);
        CameraSnapshotProvider shots = mock(CameraSnapshotProvider.class);
        CameraBindingService bindings = mock(CameraBindingService.class);
        ModelGateway model = mock(ModelGateway.class);
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(fields.requireFarm(1, 2L)).thenReturn(UserLoginResponseVO.FarmVO.builder().name("农场").build());
        when(fields.list(1, 2L)).thenReturn(List.of(FarmField.builder().fieldId("f").name("地块").build()));
        when(cameras.list(1, 2L)).thenReturn(List.of(camera("f")));
        when(bindings.list(1, 2L)).thenReturn(List.of(java.util.Map.of("cameraId", "c", "fieldId", "f", "areaId", "a", "areaName", "监测区")));
        when(shots.capture("c", 2L)).thenThrow(new CameraSnapshotProvider.CameraOfflineException());
        FarmMaturityService service = new FarmMaturityService(fields, cameras, shots, model, new ObjectMapper());
        service.setJdbc(jdbc); service.setBindingService(bindings);
        var report = service.analyze(1, 2L, List.of("c"), null, null, "MATURITY", "CAMERA");
        var parcels = (List<java.util.Map<String,Object>>) report.get("parcels");
        var observations = (List<java.util.Map<String,Object>>) parcels.get(0).get("observations");
        assertEquals("OFFLINE", observations.get(0).get("status"));
        verifyNoInteractions(model);
    }

    @Test void presentationGroupingRequiresOptInAndUsesOnlyRealCameraEvidence() {
        FarmFieldService fields = mock(FarmFieldService.class);
        FarmCameraService cameras = mock(FarmCameraService.class);
        CameraSnapshotProvider shots = mock(CameraSnapshotProvider.class);
        CameraBindingService bindings = mock(CameraBindingService.class);
        ModelGateway model = mock(ModelGateway.class);
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(fields.requireFarm(1, 2L)).thenReturn(UserLoginResponseVO.FarmVO.builder().name("农场").build());
        when(fields.list(1, 2L)).thenReturn(List.of(FarmField.builder().farmId(1).fieldId("demo").name("演示区").sourceMode("SYNTHETIC_PRESENTATION").build()));
        FarmCamera demo = new FarmCamera("c", "s", "相机", 1, "农场", "demo", "演示区", null, null, null, "SYNTHETIC_PRESENTATION", null, 1.0, 2.0, 1.0, 2.0, null, "BOUND", "CURATED_PRESENTATION_TOPOLOGY", "ACTIVE");
        when(cameras.list(1, 2L)).thenReturn(List.of(demo));
        when(shots.capture("c", 2L)).thenThrow(new CameraSnapshotProvider.CameraOfflineException());
        FarmMaturityService service = new FarmMaturityService(fields, cameras, shots, model, new ObjectMapper());
        service.setJdbc(jdbc); service.setBindingService(bindings);
        var strict = service.analyze(1, 2L, List.of("c"), null, null, "MATURITY", "CAMERA");
        verifyNoInteractions(shots);
        assertNull(strict.get("topology"));
        assertThrows(FieldAccessException.class, () -> service.analyze(1, 2L, null, "demo", null, "MATURITY", "FIELD"));
        service.setPresentationDataEnabled(true);
        var report = service.analyze(1, 2L, null, "demo", null, "MATURITY", "FIELD");
        assertEquals("DEMO", report.get("topology"));
        var parcels = (List<java.util.Map<String,Object>>) report.get("parcels");
        assertEquals("DEMO_MONITORING_REGION", parcels.get(0).get("scope"));
        assertEquals("UNKNOWN", parcels.get(0).get("stage"));
        var obs = (List<java.util.Map<String,Object>>) parcels.get(0).get("observations");
        assertEquals("OFFLINE", obs.get(0).get("status"));
        verify(shots).capture("c", 2L);
        verifyNoInteractions(model);
        verify(bindings, never()).bind(any(), any(), any(), any(), any(), any());
    }

    private FarmCamera camera(String field) { return new FarmCamera("c", "s", "相机", 1, "农场", field, "地块", null, null, null, null, null, 1.0, 2.0, 1.0, 2.0, null, "BOUND", "MANUAL", "ACTIVE"); }
    private FarmCamera camera(String id, String field) { return new FarmCamera(id, id, "相机", 1, "农场", field, "地块", null, null, null, null, null, 1.0, 2.0, 1.0, 2.0, null, "BOUND", "MANUAL", "ACTIVE"); }
}


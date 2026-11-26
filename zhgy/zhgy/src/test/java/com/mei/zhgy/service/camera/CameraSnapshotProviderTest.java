package com.mei.zhgy.service.camera;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CameraSnapshotProviderTest {
    @Test void explicitOfflineDoesNotFallBackToAnUploadedFrame() {
        FarmCameraService cameras = mock(FarmCameraService.class);
        FarmCamera camera = new FarmCamera("camera", "serial", "name", 1, "农场", "field-1", "地块", null, null, null, null,
                null, 109.0, 31.0, 109.0, 31.0, null, "BOUND", "MANUAL", "ACTIVE");
        when(cameras.get("camera", 7L)).thenReturn(camera);
        CameraSnapshotProvider provider = new CameraSnapshotProvider(cameras);
        ReflectionTestUtils.setField(provider, "clientId", "fixture-client");
        ReflectionTestUtils.setField(provider, "clientSecret", "fixture-secret");
        ReflectionTestUtils.setField(provider, "captureUrl", "https://fixture.invalid/capture");
        provider.rememberBrowserFrame(camera, "https://fixture.invalid/image.jpg", "2026-11-21T00:00:00Z");
        var template = (org.springframework.web.client.RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(template).build();
        var json = org.springframework.http.MediaType.APPLICATION_JSON;
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api2.hik-cloud.com/oauth/token"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"access_token\":\"fixture-token\"}", json));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://fixture.invalid/capture"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"code\":\"410\",\"message\":\"设备离线\"}", json));
        assertThrows(CameraSnapshotProvider.CameraOfflineException.class, () -> provider.capture("camera", 7L));
        server.verify();
    }
    @Test void requiresActorAndCameraBeforeLookup() {
        FarmCameraService cameras = mock(FarmCameraService.class);
        CameraSnapshotProvider provider = new CameraSnapshotProvider(cameras);
        assertThrows(IllegalArgumentException.class, () -> provider.capture("camera", null));
        assertThrows(IllegalArgumentException.class, () -> provider.capture(" ", 1L));
        verifyNoInteractions(cameras);
    }
    @Test void checksOwnershipAndDoesNotFabricateImageWhenCredentialsMissing() {
        FarmCameraService cameras = mock(FarmCameraService.class);
        FarmCamera camera = new FarmCamera("camera", "serial", "name", 1, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        when(cameras.get("camera", 7L)).thenReturn(camera);
        CameraSnapshotProvider provider = new CameraSnapshotProvider(cameras);
        ReflectionTestUtils.setField(provider, "clientId", "");
        ReflectionTestUtils.setField(provider, "clientSecret", "");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> provider.capture("camera", 7L));
        assertTrue(failure.getMessage().contains("凭据"));
        assertThrows(CameraSnapshotProvider.CaptureNotConfiguredException.class, () -> provider.capture("camera", 7L));
        verify(cameras, times(2)).get("camera", 7L);
    }
    @Test void returnsOnlyRememberedFrameAfterOwnerCheckWhenRemoteUnavailable() {
        FarmCameraService cameras = mock(FarmCameraService.class);
        FarmCamera camera = new FarmCamera("camera", "serial", "name", 1, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        when(cameras.get("camera", 7L)).thenReturn(camera);
        CameraSnapshotProvider provider = new CameraSnapshotProvider(cameras);
        ReflectionTestUtils.setField(provider, "clientId", "");
        ReflectionTestUtils.setField(provider, "clientSecret", "");
        provider.rememberBrowserFrame(camera, "https://storage.test/frame.jpg", "2026-11-21T00:00:00Z");
        var snapshot = provider.capture("camera", 7L);
        assertEquals("https://storage.test/frame.jpg", snapshot.imageUrl());
        assertEquals("2026-11-21T00:00:00Z", snapshot.capturedAt());
        assertEquals("BROWSER_CAMERA_FRAME", snapshot.source());
        when(cameras.get("camera", 8L)).thenThrow(new com.mei.zhgy.service.field.FieldAccessException(403, "FARM_ACCESS_DENIED", "farm access denied"));
        assertThrows(com.mei.zhgy.service.field.FieldAccessException.class, () -> provider.capture("camera", 8L));
        verify(cameras, atLeastOnce()).get("camera", 7L);
    }
}

package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.rag.UploadedImageAssetIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AIModelUploadAvailabilityTest {
    private static final String BASE_URL = "https://assets.example.test/crops";

    @TempDir
    Path uploadDir;

    @Test
    void returnsPersistedUrlWhenClipIndexQueueThrows() throws Exception {
        UploadedImageAssetIndexService indexService = mock(UploadedImageAssetIndexService.class);
        doThrow(new IllegalStateException("CLIP indexing timed out"))
                .when(indexService).queueIndex(anyString(), any(UploadedImageAssetIndexService.Metadata.class));
        AIModelController controller = controller(indexService);
        byte[] image = "valid-png-bytes".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = new MockMultipartFile("files", "leaf.png", "image/png", image);

        Result<List<String>> response = controller.uploadCropImages(
                List.of(file), 7, "field-7", "point-7", "camera-7");

        assertEquals(1, response.getCode());
        assertEquals(1, response.getData().size());
        String url = response.getData().get(0);
        assertTrue(url.startsWith(BASE_URL + "/"));
        String fileName = url.substring((BASE_URL + "/").length());
        assertTrue(fileName.endsWith(".png"));
        Path persisted = uploadDir.resolve(fileName);
        assertTrue(Files.isRegularFile(persisted));
        assertArrayEquals(image, Files.readAllBytes(persisted));
        verify(indexService).queueIndex(anyString(), any(UploadedImageAssetIndexService.Metadata.class));
    }

    @Test
    void rejectsOversizedBatchWithoutPersistingOrQueueing() throws Exception {
        UploadedImageAssetIndexService indexService = mock(UploadedImageAssetIndexService.class);
        AIModelController controller = controller(indexService);
        List<MultipartFile> files = List.of(
                image("1.png"), image("2.png"), image("3.png"),
                image("4.png"), image("5.png"), image("6.png"));

        Result<List<String>> response = controller.uploadCropImages(files, null, null, null, null);

        assertEquals(0, response.getCode());
        assertTrue(containsNoRegularFiles(uploadDir));
        verifyNoInteractions(indexService);
    }

    private AIModelController controller(UploadedImageAssetIndexService indexService) {
        AIModelController controller = new AIModelController();
        ReflectionTestUtils.setField(controller, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(controller, "uploadBaseUrl", BASE_URL);
        ReflectionTestUtils.setField(controller, "uploadedImageAssetIndexService", indexService);
        return controller;
    }

    private static MultipartFile image(String name) {
        return new MockMultipartFile("files", name, "image/png", "image-bytes".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean containsNoRegularFiles(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.noneMatch(Files::isRegularFile);
        }
    }
}
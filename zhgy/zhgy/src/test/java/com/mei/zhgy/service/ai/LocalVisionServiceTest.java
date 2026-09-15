package com.mei.zhgy.service.ai;

import com.mei.zhgy.util.CLIPModelUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalVisionServiceTest {
    @Test
    void verifiesClipEmbeddingDimensionFiniteValuesAndDeterminism() throws Exception {
        Path imageDirectory = Paths.get("..", "..", "farmap-frontend-dev", "src", "assets", "demo")
                .toAbsolutePath().normalize();
        Path firstSource = imageDirectory.resolve("diagnosis-camera-chlorosis.png");
        Path secondSource = imageDirectory.resolve("diagnosis-camera-leaf.png");
        Assumptions.assumeTrue(Files.isRegularFile(firstSource) && Files.isRegularFile(secondSource),
                "Demo image assets are not available in this checkout");
        Assumptions.assumeTrue(Files.isRegularFile(Paths.get("target", "classes", "models", "clip-image-encoder.onnx")),
                "CLIP ONNX asset is not available in this checkout");

        Path firstImage = Files.createTempFile("farmap-clip-first-", ".png");
        Path secondImage = Files.createTempFile("farmap-clip-second-", ".png");
        Files.copy(firstSource, firstImage, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(secondSource, secondImage, StandardCopyOption.REPLACE_EXISTING);
        CLIPModelUtil clipModelUtil = new CLIPModelUtil();
        clipModelUtil.init();
        try {
            Assumptions.assumeTrue(clipModelUtil.isReady(),
                    "CLIP native runtime unavailable in this process; see standalone local-model validation");
            LocalVisionService service = new LocalVisionService(clipModelUtil);
            ReflectionTestUtils.setField(service, "enabled", true);
            EmbeddingProvider.EmbeddingResult first = service.embedImage(firstImage.toString());
            EmbeddingProvider.EmbeddingResult firstRepeat = service.embedImage(firstImage.toString());
            EmbeddingProvider.EmbeddingResult second = service.embedImage(secondImage.toString());

            assertEquals(512, first.getDimension());
            assertEquals(512, firstRepeat.getDimension());
            assertEquals(512, second.getDimension());
            assertTrue(first.getVector().stream().allMatch(value -> value != null && Float.isFinite(value)));
            assertTrue(second.getVector().stream().allMatch(value -> value != null && Float.isFinite(value)));
            assertEquals(first.getVector(), firstRepeat.getVector(), "same image should produce deterministic CLIP vectors");
        } finally {
            clipModelUtil.destroy();
            Files.deleteIfExists(firstImage);
            Files.deleteIfExists(secondImage);
        }
    }
}

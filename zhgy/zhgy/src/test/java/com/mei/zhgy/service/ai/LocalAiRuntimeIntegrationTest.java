package com.mei.zhgy.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "FARMAP_RUN_LOCAL_AI_INTEGRATION", matches = "true")
class LocalAiRuntimeIntegrationTest {
    @Test void javaCallsBothLongLivedEmbeddingEndpoints() {
        LocalAiClient client = new LocalAiClient("http://127.0.0.1:8001", 30000);
        assertEquals(512, ((Number) client.embedTexts(List.of("柑橘黄化")).get("dimension")).intValue());
        String pixel = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        assertEquals(512, ((Number) client.embedImage(pixel).get("dimension")).intValue());
    }
}

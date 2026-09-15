package com.mei.zhgy.service.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LocalBgeEmbeddingProviderTest {
    @Test void mapsBatchVectors() {
        LocalAiClient client = mock(LocalAiClient.class);
        when(client.embedTexts(anyList())).thenReturn(Map.of("model","bge","dimension",2,"latencyMs",4,"embeddings",List.of(List.of(1D,0D))));
        var result = new LocalBgeEmbeddingProvider(client).embedText("柑橘");
        assertEquals(2, result.getDimension());
        assertEquals("LOCAL", result.getProvider());
    }
}

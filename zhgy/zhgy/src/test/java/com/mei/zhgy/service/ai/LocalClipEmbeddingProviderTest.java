package com.mei.zhgy.service.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LocalClipEmbeddingProviderTest {
    @Test void mapsImageVectorAndCachesIt() {
        LocalAiClient client = mock(LocalAiClient.class);
        when(client.embedImage("data:image/png;base64,x")).thenReturn(Map.of("model","clip","dimension",2,"latencyMs",3,"embedding",List.of(1D,0D)));
        LocalClipEmbeddingProvider provider = new LocalClipEmbeddingProvider(client);
        assertEquals(2, provider.embedImage("data:image/png;base64,x").getDimension());
        provider.embedImage("data:image/png;base64,x");
        verify(client, times(1)).embedImage(anyString());
    }
}

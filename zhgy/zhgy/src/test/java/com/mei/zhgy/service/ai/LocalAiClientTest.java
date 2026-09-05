package com.mei.zhgy.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalAiClientTest {
    @Test void readsHealthAndBatchEmbeddingContracts() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        LocalAiClient client = new LocalAiClient("http://local-ai:8001", http);
        server.expect(requestTo("http://local-ai:8001/health")).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://local-ai:8001/embedding/text")).andRespond(withSuccess("{\"dimension\":512,\"embeddings\":[[1.0]]}", MediaType.APPLICATION_JSON));
        assertEquals("ok", client.health().get("status"));
        assertEquals(512, ((Number) client.embedTexts(List.of("柑橘")).get("dimension")).intValue());
        server.verify();
    }
}

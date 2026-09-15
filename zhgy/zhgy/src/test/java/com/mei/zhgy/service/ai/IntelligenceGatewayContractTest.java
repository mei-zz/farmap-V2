package com.mei.zhgy.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IntelligenceGatewayContractTest {
    @Test
    void mapsVersionedVisionAndRejectsInvalidEmbeddingDimension() {
        LocalAiClient local = mock(LocalAiClient.class);
        when(local.embedTextsV1(anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("contractVersion", "ai.v1", "status", "SUCCEEDED", "dimension", 512,
                        "model", "bge", "latencyMs", 4, "embeddings", List.of(List.of(0.1D))));
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://vision:5000/v1/vision/analyze"))
                .andRespond(withSuccess("{\"contractVersion\":\"ai.v1\",\"requestId\":\"r1\",\"status\":\"SUCCEEDED\",\"observations\":[]}", MediaType.APPLICATION_JSON));
        IntelligenceGateway gateway = new IntelligenceGateway(local, "http://vision:5000/v1/vision/analyze", http, false);
        assertEquals("SUCCEEDED", gateway.analyzeVision(List.of("data:image/png;base64,AA=="), Map.of(), "r1", "t1").get("status"));
        assertEquals(1, gateway.embedTexts(List.of("柑橘"), "r2", "t2").size());
        server.verify();

        when(local.embedTextsV1(anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("contractVersion", "ai.v1", "status", "SUCCEEDED", "dimension", 384, "embeddings", List.of(List.of(0.1D))));
        assertThrows(IntelligenceGatewayException.class, () -> gateway.embedTexts(List.of("柑橘"), "r3", "t3"));
    }

    @Test
    void unavailableResponseIsNotConvertedToEmptySuccess() {
        LocalAiClient local = mock(LocalAiClient.class);
        when(local.embedImageV1(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("contractVersion", "ai.v1", "status", "UNAVAILABLE", "errorCode", "AI_MODEL_UNAVAILABLE"));
        IntelligenceGateway gateway = new IntelligenceGateway(local, "http://vision:5000/v1/vision/analyze", new RestTemplate(), false);
        IntelligenceGatewayException error = assertThrows(IntelligenceGatewayException.class,
                () -> gateway.embedImage("data:image/png;base64,AA==", "r4", "t4"));
        assertEquals("AI_MODEL_UNAVAILABLE", error.getCode());
    }

    @Test
    void timeoutIsMappedToTypedRetryableFailure() {
        LocalAiClient local = mock(LocalAiClient.class);
        when(local.embedTextsV1(anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResourceAccessException("read timed out"));
        IntelligenceGateway gateway = new IntelligenceGateway(local, "http://vision:5000/v1/vision/analyze", new RestTemplate(), false);
        IntelligenceGatewayException error = assertThrows(IntelligenceGatewayException.class,
                () -> gateway.embedTexts(List.of("柑橘"), "r5", "t5"));
        assertEquals("AI_MODEL_TIMEOUT", error.getCode());
        org.junit.jupiter.api.Assertions.assertTrue(error.isRetryable());
    }
}

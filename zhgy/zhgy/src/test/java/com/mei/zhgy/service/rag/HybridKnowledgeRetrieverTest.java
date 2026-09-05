package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.TextEmbeddingProvider;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

class HybridKnowledgeRetrieverTest {
    @Test void usesBgeForCandidateReranking() {
        TextEmbeddingProvider provider = mock(TextEmbeddingProvider.class);
        List<Float> vector = new ArrayList<>(Collections.nCopies(512, 0F)); vector.set(0, 1F);
        EmbeddingProvider.EmbeddingResult result = new EmbeddingProvider.EmbeddingResult(vector, "LOCAL", "bge", 512, 2);
        when(provider.embedTexts(anyList())).thenAnswer(call -> Collections.nCopies(((List<?>) call.getArgument(0)).size(), result));
        when(provider.embedText(anyString())).thenReturn(result);
        KnowledgeEvidenceRetriever retriever = new KnowledgeEvidenceRetriever(provider);
        ReflectionTestUtils.setField(retriever, "resourcePath", "knowledge/orchard_management.txt");
        retriever.loadKnowledge();
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(); request.setFieldId("A-12"); request.setCrop("柑橘"); request.setGrowthStage("果实膨大期"); request.setQuery("叶片黄化");
        var evidence = retriever.retrieve(request);
        assertFalse(evidence.isEmpty());
        assertEquals("hybrid", evidence.get(0).getMetadata().get("retrievalType"));
        verify(provider).embedText(anyString());
    }
}

package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.entity.Case;
import com.mei.zhgy.service.CaseStorageService;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

class HistoricalCaseRetrieverTest {
    @Test void exposesL2DistanceAndRankingTransform() {
        CaseStorageService store = mock(CaseStorageService.class); ImageEmbeddingProvider clip = mock(ImageEmbeddingProvider.class);
        when(clip.embedImage(anyString())).thenReturn(new EmbeddingProvider.EmbeddingResult(Collections.nCopies(512, 0F), "LOCAL", "clip", 512, 1));
        when(store.searchSimilarCasesWithScore(anyList(), anyInt())).thenReturn(List.of(new CaseStorageService.SimilarCaseWithScore(Case.builder().requestId("case-1").diseaseType("缺镁").build(), 0.25, 0.8)));
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest(); request.setFieldId("A-12"); request.setImageUrls(List.of("data:image/png;base64,x")); request.setTopK(5);
        HistoricalCaseEvidenceRetriever retriever = new HistoricalCaseEvidenceRetriever(store, clip);
        ReflectionTestUtils.setField(retriever, "localVisionEnabled", true);
        var evidence = retriever.retrieve(request);
        assertEquals(0.25, evidence.get(0).getMetadata().get("vectorDistance"));
        assertEquals("1/(1+distance)", evidence.get(0).getMetadata().get("scoreTransform"));
    }
}

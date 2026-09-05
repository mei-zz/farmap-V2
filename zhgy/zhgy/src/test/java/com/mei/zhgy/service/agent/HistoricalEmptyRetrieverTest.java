package com.mei.zhgy.service.agent;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.CaseStorageService;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import com.mei.zhgy.service.rag.HistoricalCaseEvidenceRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class HistoricalEmptyRetrieverTest {
    @Test void emptyMilvusResultsProduceNoFabricatedEvidence() {
        CaseStorageService store = mock(CaseStorageService.class);
        ImageEmbeddingProvider clip = mock(ImageEmbeddingProvider.class);
        when(clip.embedImage(anyString())).thenReturn(new EmbeddingProvider.EmbeddingResult(Collections.nCopies(512, 0F), "LOCAL", "clip", 512, 1));
        when(store.searchSimilarCasesWithScore(anyList(), anyInt())).thenReturn(Collections.emptyList());
        DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
        request.setFieldId("A-12");
        request.setImageUrls(List.of("data:image/png;base64,x"));
        HistoricalCaseEvidenceRetriever retriever = new HistoricalCaseEvidenceRetriever(store, clip);
        ReflectionTestUtils.setField(retriever, "localVisionEnabled", true);

        assertTrue(retriever.retrieve(request).isEmpty());
        verify(store).searchSimilarCasesWithScore(anyList(), eq(5));
    }
}

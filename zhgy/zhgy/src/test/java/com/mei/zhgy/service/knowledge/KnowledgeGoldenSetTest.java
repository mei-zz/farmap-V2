package com.mei.zhgy.service.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression contract for crop/stage query rewriting and expected knowledge coverage. */
class KnowledgeGoldenSetTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CropResolver crops = new CropResolver();
    private final KnowledgeQueryRewriter rewriter = new KnowledgeQueryRewriter(crops, new PhenologyService(crops));

    @Test
    void goldenSetHasFiftyCasesAndEveryKnownCropIsRewrittenInChinese() throws IOException {
        List<GoldenCase> cases = load();
        assertEquals(50, cases.size());
        Set<String> ids = cases.stream().map(GoldenCase::id).collect(Collectors.toSet());
        assertEquals(50, ids.size());

        int rewrittenCrop = 0;
        int rewrittenStage = 0;
        int referenceCases = 0;
        int expandedReferences = 0;
        for (GoldenCase item : cases) {
            CropResolver.ResolvedCrop crop = crops.resolve(item.crop());
            assertTrue(crop.ready(), item.id() + " crop must be in CropCatalog");
            Map<String, Object> context = new LinkedHashMap<>();
            if (item.query().contains("这个") || item.query().contains("该病")) {
                context.put("recentConversation", "用户：上一轮正在讨论该地块的病斑\n助手：已记录");
                referenceCases++;
            }
            KnowledgeQueryRewriter.RewrittenQuery rewritten = rewriter.rewrite(item.query(), item.crop(), item.stage(), context, null);
            assertEquals(crop.code(), rewritten.cropCode(), item.id());
            assertEquals(item.expectedQueryType(), rewritten.queryType(), item.id());
            assertTrue(rewritten.query().contains("作物：" + crop.name()), item.id() + " must inject Chinese crop label");
            assertFalse(rewritten.query().contains("作物：" + crop.code()), item.id() + " must not inject English crop code");
            assertNotNull(rewritten.topK(), item.id());
            assertTrue(rewritten.topK() >= 3 && rewritten.topK() <= 5, item.id() + " dynamic topK out of policy");
            if (rewritten.cropLabel() != null) rewrittenCrop++;
            if (rewritten.stageLabel() != null) rewrittenStage++;
            if (rewritten.referenceExpanded()) expandedReferences++;
            assertTrue(item.expectedTerms() != null && !item.expectedTerms().isEmpty(), item.id());
            assertTrue(item.expectedConclusion() != null && !item.expectedConclusion().isBlank(), item.id());
        }
        assertEquals(cases.size(), rewrittenCrop);
        assertEquals(cases.size(), rewrittenStage);
        assertEquals(referenceCases, expandedReferences);
    }

    private List<GoldenCase> load() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/knowledge/knowledge-golden-set-v1.json")) {
            assertNotNull(input);
            return mapper.readValue(input, new TypeReference<>() { });
        }
    }

    private record GoldenCase(String id, String crop, String stage, String query, String expectedQueryType,
                              List<String> expectedTerms, String expectedConclusion, boolean expectedStructured) { }
}

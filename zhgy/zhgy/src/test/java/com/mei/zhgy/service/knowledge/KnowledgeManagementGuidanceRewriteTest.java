package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeManagementGuidanceRewriteTest {
    @Test
    void compoundPhenologyGuidanceGetsEnoughEvidenceSlots() {
        KnowledgeQueryRewriter rewriter = new KnowledgeQueryRewriter();

        KnowledgeQueryRewriter.RewrittenQuery rewritten = rewriter.rewrite(
                "下一个物候期是什么，有哪些农事指导", "脆李", "post_harvest", java.util.Map.of(), null);

        assertEquals("MANAGEMENT_GUIDANCE", rewritten.queryType());
        assertEquals(6, rewritten.topK());
        assertEquals("post_harvest", rewritten.stageCode());
    }
}

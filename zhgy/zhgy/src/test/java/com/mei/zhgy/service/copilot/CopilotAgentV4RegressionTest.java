package com.mei.zhgy.service.copilot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Deterministic part of the V4 agriculture regression matrix. */
class CopilotAgentV4RegressionTest {
    @Test
    void coversInputUnderstandingRoutingAndMediaSeparation() {
        CopilotIntentRouter router = new CopilotIntentRouter();
        CopilotIntentRouter.HybridContext context = new CopilotIntentRouter.HybridContext("", null, null);
        List<Case> cases = List.of(
                new Case("greeting", "你好", false, CopilotIntent.GENERAL_QA, CopilotMediaNeed.NONE),
                new Case("simple-knowledge", "什么是叶面肥？", false, CopilotIntent.KNOWLEDGE_QUERY, CopilotMediaNeed.NONE),
                new Case("phenology-typo", "果实棚大期是什么样的？", false, CopilotIntent.KNOWLEDGE_QUERY, CopilotMediaNeed.BENEFICIAL),
                new Case("phenology-features", "果实膨大期有什么特征？", false, CopilotIntent.KNOWLEDGE_QUERY, CopilotMediaNeed.BENEFICIAL),
                new Case("reference-phenology-image", "给我一张果实膨大期的照片。", false, CopilotIntent.KNOWLEDGE_QUERY, CopilotMediaNeed.EXPLICIT),
                new Case("reference-disease-image", "给我看脆李穿孔病参考图片。", false, CopilotIntent.KNOWLEDGE_QUERY, CopilotMediaNeed.EXPLICIT),
                new Case("disease-appearance", "褐腐病是什么样的？", false, CopilotIntent.KNOWLEDGE_QUERY, CopilotMediaNeed.BENEFICIAL),
                new Case("diagnosis-without-upload", "我的叶子发黄了，帮我诊断。", false, CopilotIntent.DIAGNOSIS_REQUEST, CopilotMediaNeed.NONE),
                new Case("diagnosis-with-upload", "这是病害吗？", true, CopilotIntent.DIAGNOSIS_REQUEST, CopilotMediaNeed.NONE),
                new Case("field-guidance", "结合 L-01 地块制定管理方案。", false, CopilotIntent.RECOMMENDATION_QUERY, CopilotMediaNeed.NONE),
                new Case("contextual-why", "为什么需要这样管理？", false, CopilotIntent.KNOWLEDGE_QUERY, CopilotMediaNeed.NONE),
                new Case("direct-task", "帮我创建明天的浇水任务。", false, CopilotIntent.ACTION_REQUEST, CopilotMediaNeed.NONE),
                new Case("negated-task", "不要创建任务，只分析是否需要浇水。", false, CopilotIntent.GENERAL_QA, CopilotMediaNeed.NONE),
                new Case("field-follow-up", "对，就是刚才那个地块。", false, CopilotIntent.FIELD_STATUS_QUERY, CopilotMediaNeed.NONE)
        );
        for (Case item : cases) {
            CopilotIntentRouter.HybridClassification routed = router.classifyHybrid(item.message(), context, item.hasUserImage());
            assertEquals(item.intent(), routed.finalClassification().intent(), item.id());
            assertEquals(item.mediaNeed(), CopilotMediaPolicy.decide(item.message(), routed.finalClassification().intent(), item.hasUserImage()), item.id());
        }
    }

    private record Case(String id, String message, boolean hasUserImage,
                        CopilotIntent intent, CopilotMediaNeed mediaNeed) { }
}

package com.mei.zhgy.service.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopilotMediaPolicyTest {
    @Test
    void diseaseAppearanceQuestionIsBeneficial() {
        assertEquals(CopilotMediaNeed.BENEFICIAL,
                CopilotMediaPolicy.decide("褐腐病是什么样的？", CopilotIntent.KNOWLEDGE_QUERY, false));
    }

    @Test
    void explicitReferenceImageQuestionIsExplicit() {
        assertEquals(CopilotMediaNeed.EXPLICIT,
                CopilotMediaPolicy.decide("给我看褐腐病参考图片。", CopilotIntent.KNOWLEDGE_QUERY, false));
        assertEquals(CopilotMediaNeed.EXPLICIT,
                CopilotMediaPolicy.decide("脆李萌芽期图片", CopilotIntent.KNOWLEDGE_QUERY, false));
        assertEquals(CopilotMediaNeed.EXPLICIT,
                CopilotMediaPolicy.decide("给我一张果实膨大期的照片", CopilotIntent.KNOWLEDGE_QUERY, false));
        assertEquals(CopilotMediaNeed.EXPLICIT,
                CopilotMediaPolicy.decide("给我一张果实棚大期的照片", CopilotIntent.KNOWLEDGE_QUERY, false));
    }

    @Test
    void ordinaryKnowledgeDoesNotTriggerAtlasLookup() {
        assertEquals(CopilotMediaNeed.NONE,
                CopilotMediaPolicy.decide("什么是叶面肥？", CopilotIntent.KNOWLEDGE_QUERY, false));
        assertEquals(CopilotMediaNeed.NONE,
                CopilotMediaPolicy.decide("你好", CopilotIntent.GENERAL_QA, false));
    }

    @Test
    void uploadedDiagnosisUsesTheUserPhotoAsPrimaryEvidence() {
        assertEquals(CopilotMediaNeed.NONE,
                CopilotMediaPolicy.decide("请诊断这张照片里的病害。", CopilotIntent.DIAGNOSIS_REQUEST, true));
    }
}

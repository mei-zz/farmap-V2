package com.mei.zhgy.service.copilot;

import java.util.Locale;

/**
 * Small media policy beside the existing intent router. It does not replace
 * intent classification: it only decides whether a resolved turn benefits
 * from a read-only reference-atlas lookup.
 */
public final class CopilotMediaPolicy {
    private CopilotMediaPolicy() {}

    public static CopilotMediaNeed decide(String message, CopilotIntent intent, boolean hasUserImages) {
        String value = normalize(message);
        if (value.isBlank() || isGreeting(value) || intent == CopilotIntent.ACTION_REQUEST) {
            return CopilotMediaNeed.NONE;
        }

        // A user-uploaded scene image is the primary visual evidence for a
        // diagnosis. Do not add atlas images unless the user asks for them.
        boolean asksForReference = containsAny(value, "给我看", "展示", "参考图片", "参考图");
        if (hasUserImages && intent == CopilotIntent.DIAGNOSIS_REQUEST && !asksForReference) return CopilotMediaNeed.NONE;

        boolean explicit = asksForReference || containsAny(value, "图片", "图像", "照片");
        if (explicit && supportsReferenceImages(intent)) return CopilotMediaNeed.EXPLICIT;

        boolean beneficial = containsAny(value, "是什么样", "长什么样", "外观", "外观特征", "形态", "特征", "如何区分", "怎么区分", "区别")
                && supportsReferenceImages(intent);
        return beneficial ? CopilotMediaNeed.BENEFICIAL : CopilotMediaNeed.NONE;
    }

    private static boolean supportsReferenceImages(CopilotIntent intent) {
        return intent == CopilotIntent.KNOWLEDGE_QUERY
                || intent == CopilotIntent.DIAGNOSIS_QUERY
                || intent == CopilotIntent.DIAGNOSIS_REQUEST
                || intent == CopilotIntent.GENERAL_QA;
    }

    private static boolean isGreeting(String value) {
        return value.matches("^(你好|您好|嗨|哈喽|hello|hi|谢谢|多谢|辛苦了|在吗)[！!。,.？? ]*$");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String normalize(String value) {
        return CopilotQueryNormalizer.forMatching(value);
    }
}

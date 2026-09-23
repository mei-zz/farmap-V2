package com.mei.zhgy.service.copilot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small deterministic policy layer between business intent and presentation.
 * It deliberately does not classify intents or parse model JSON.
 */
public final class CopilotResponsePolicy {
    private static final Set<String> VISUAL_SIGNALS = Set.of("现场图片", "图片", "图像", "照片", "拍照", "可见异常", "叶片", "病斑");
    private static final Set<String> GUIDED_SIGNALS = Set.of("怎么管理", "如何管理", "管理方案", "农事方案", "应该怎么办", "怎么处理", "风险", "未来一周", "下一步建议", "肥水", "注意哪些");
    private static final Set<String> KNOWLEDGE_SIGNALS = Set.of("为什么", "是什么", "有哪些", "概念", "物候", "病虫害", "叶面肥", "土壤检测", "pH", "适宜温度");

    private CopilotResponsePolicy() {}

    public static CopilotResponseMode choose(CopilotRouteResult route, String message, boolean hasImages, boolean fieldFactsReady) {
        String value = normalize(message);
        if (route == null || route.isRequiresClarification() || route.getResolutionStatus() != CopilotResolutionStatus.RESOLVED) {
            return CopilotResponseMode.CLARIFICATION;
        }
        if (route.getIntent() == CopilotIntent.ACTION_REQUEST) return CopilotResponseMode.AGENT;
        if (route.getIntent() == CopilotIntent.DIAGNOSIS_REQUEST) {
            if (containsAny(value, VISUAL_SIGNALS) && !hasImages) return CopilotResponseMode.CLARIFICATION;
            return CopilotResponseMode.AGENT;
        }
        if (containsAny(value, GUIDED_SIGNALS)
                || route.getIntent() == CopilotIntent.PRIORITIES_QUERY
                || route.getIntent() == CopilotIntent.RECOMMENDATION_QUERY
                || route.getIntent() == CopilotIntent.FARM_STATUS_QUERY
                || route.getIntent() == CopilotIntent.FIELD_STATUS_QUERY
                || route.getIntent() == CopilotIntent.DIAGNOSIS_QUERY
                || route.getIntent() == CopilotIntent.OPERATIONS_STATUS_QUERY
                || route.getIntent() == CopilotIntent.OUTCOME_QUERY) {
            return CopilotResponseMode.GUIDED;
        }
        if (route.getIntent() == CopilotIntent.KNOWLEDGE_QUERY || containsAny(value, KNOWLEDGE_SIGNALS)) {
            return CopilotResponseMode.KNOWLEDGE;
        }
        return CopilotResponseMode.SIMPLE;
    }

    public static CopilotResponseMetadata build(CopilotRouteResult route, String message, String messageId,
                                                 boolean hasImages, boolean fieldFactsReady,
                                                 String cropLabel, String growthStageLabel,
                                                 String knowledgeStatus, List<Map<String, Object>> knowledgeItems) {
        CopilotResponseMode mode = choose(route, message, hasImages, fieldFactsReady);
        CopilotResponseMetadata result = new CopilotResponseMetadata();
        result.setMessageId(messageId);
        if (route != null) {
            result.setIntent(route.getIntent());
            result.setResolutionStatus(route.getResolutionStatus());
            result.setNormalizedQuery(route.getNormalizedQuery());
            result.setMediaNeed(route.getMediaNeed() == null
                    ? CopilotMediaPolicy.decide(message, route.getIntent(), hasImages) : route.getMediaNeed());
        }
        result.setResponseType(mode);
        result.setCards(cards(mode, fieldFactsReady, cropLabel, growthStageLabel, knowledgeItems));
        result.setSources(mode == CopilotResponseMode.CLARIFICATION || mode == CopilotResponseMode.AGENT
                ? List.of() : sources(knowledgeItems));
        result.setSuggestions(suggestions(mode, message, hasImages));
        result.setActions(actions(mode, message, hasImages));
        return result;
    }

    private static List<CopilotResponseMetadata.Card> cards(CopilotResponseMode mode, boolean fieldFactsReady,
                                                             String cropLabel, String growthStageLabel,
                                                             List<Map<String, Object>> items) {
        List<CopilotResponseMetadata.Card> output = new ArrayList<>();
        if (mode == CopilotResponseMode.GUIDED && fieldFactsReady && (!blank(cropLabel) || !blank(growthStageLabel))) {
            String context = "当前上下文：" + (blank(cropLabel) ? "作物未提供" : cropLabel)
                    + "；" + (blank(growthStageLabel) ? "物候期未提供" : growthStageLabel) + "。";
            output.add(new CopilotResponseMetadata.Card("context", "FIELD_CONTEXT", "当前农情上下文", context, List.of()));
        }
        // Retrieved chunks are Evidence, not a second answer. Keep them in the
        // collapsible provenance section below the streamed answer.
        return output;
    }

    private static List<CopilotResponseMetadata.SourceReference> sources(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return List.of();
        List<CopilotResponseMetadata.SourceReference> output = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> item : items) {
            String quote = stringValue(item.get("content"));
            String title = stringValue(item.get("title"));
            String sourceId = stringValue(item.get("sourceId"));
            String documentId = stringValue(item.get("documentId"));
            String versionId = stringValue(item.get("versionId"));
            String chunkId = stringValue(item.get("chunkId"));
            String id = sourceReferenceId(item);
            // Never invent a source id for a row without stable provenance.
            if (blank(id) || (blank(quote) && blank(title))) continue;
            Double score = item.get("score") instanceof Number number ? number.doubleValue() : null;
            output.add(new CopilotResponseMetadata.SourceReference(id,
                    blank(title) ? "农业知识依据" : title,
                    compactExcerpt(quote), sourceId, documentId, versionId, chunkId, score));
            index++;
            if (index >= 5) break;
        }
        return output;
    }

    private static List<CopilotResponseMetadata.SuggestedQuestion> suggestions(CopilotResponseMode mode, String message, boolean hasImages) {
        String value = normalize(message);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (mode == CopilotResponseMode.CLARIFICATION) {
            if (!hasImages && containsAny(value, VISUAL_SIGNALS)) {
                if (value.contains("叶片")) values.add("粘贴叶片正反面照片");
                else if (value.contains("果")) values.add("上传果实和受影响部位照片");
                else values.add("上传现场图片");
                if (containsAny(value, Set.of("发黄", "变色", "病斑", "腐烂", "掉果", "异常"))) values.add("说明症状从什么时候开始");
                if (!containsAny(value, Set.of("叶片", "果实", "枝", "根", "花"))) values.add("说明受影响的部位");
            } else {
                values.add("选择需要讨论的农场或地块");
                values.add("补充作物和物候期");
            }
        } else if (mode == CopilotResponseMode.KNOWLEDGE) {
            if (value.contains("叶面肥")) {
                values.add("什么时候使用叶面肥？");
                values.add("叶面肥和根部施肥有什么区别？");
            } else {
                values.add("结合当前地块解释");
                values.add("查看知识依据");
            }
        } else if (mode == CopilotResponseMode.GUIDED) {
            if (value.contains("病") || value.contains("风险")) values.add("查看病虫害风险");
            if (value.contains("肥") || value.contains("管理")) values.add("需要补充肥水数据吗？");
            values.add("生成管理计划草案");
        } else if (mode == CopilotResponseMode.AGENT) {
            values.add("查看调查进度");
        }
        List<CopilotResponseMetadata.SuggestedQuestion> output = new ArrayList<>();
        int index = 0;
        for (String valueItem : values) {
            if (index >= 3) break;
            output.add(new CopilotResponseMetadata.SuggestedQuestion("suggestion-" + (++index), valueItem));
        }
        return output;
    }

    private static List<CopilotResponseMetadata.AgentAction> actions(CopilotResponseMode mode, String message, boolean hasImages) {
        // Action buttons remain owned by the existing composer and Agent Run
        // approval UI. Do not expose a metadata action without a real handler.
        return List.of();
    }

    private static String sourceReferenceId(Map<String, Object> item) {
        String chunkId = stringValue(item.get("chunkId"));
        if (!blank(chunkId)) return chunkId;
        String documentId = stringValue(item.get("documentId"));
        if (!blank(documentId)) return documentId;
        String versionId = stringValue(item.get("versionId"));
        if (!blank(versionId)) return versionId;
        return stringValue(item.get("sourceId"));
    }

    private static String compactExcerpt(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "…";
    }

    private static boolean containsAny(String value, Set<String> signals) {
        for (String signal : signals) if (value.contains(signal.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}

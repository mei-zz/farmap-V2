package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.agriculture.PhenologyStage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic rewrite: resolve aliases, inject Chinese crop/stage names and close short references. */
@Component
public class KnowledgeQueryRewriter {
    private static final Pattern USER_TURN = Pattern.compile("(?im)(?:用户|user)\\s*[:：]\\s*([^\\n]+)");

    private final CropResolver crops;
    private final PhenologyService phenology;

    public KnowledgeQueryRewriter() { this(new CropResolver(), null); }
    public KnowledgeQueryRewriter(CropResolver crops, PhenologyService phenology) {
        this.crops = crops == null ? new CropResolver() : crops;
        this.phenology = phenology == null ? new PhenologyService(this.crops) : phenology;
    }

    public RewrittenQuery rewrite(DiagnosisAnalyzeRequest request) {
        if (request == null) return rewrite(null, null, null, Map.of(), null);
        return rewrite(request.getQuery(), request.getCrop(), request.getGrowthStage(), request.getContext(), request.getTopK());
    }

    public RewrittenQuery rewrite(String query, String rawCrop, String rawStage,
                                  Map<String, Object> context, Integer requestedTopK) {
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        CropResolver.ResolvedCrop crop = crops.resolve(first(rawCrop, text(safeContext, "crop")));
        // The chat path may not have a selected field. In that case the
        // question itself is the public crop context (for example, "脆李
        // 现在是什么期"). Resolve it before building the Chinese rewrite.
        if (!crop.ready()) {
            CropResolver.ResolvedCrop queryCrop = crops.resolve(query);
            if (queryCrop.ready()) crop = queryCrop;
            else if ("MISSING".equals(queryCrop.status()) || hasReference(query == null ? "" : query)) {
                crop = crops.resolve(lastUserTurn(text(safeContext, "recentConversation")));
            }
        }
        String cropCode = crop.ready() ? crop.code() : null;
        String cropLabel = crop.ready() ? crop.name() : text(safeContext, "cropLabel");
        String stageCode = first(rawStage, text(safeContext, "currentPhenologyStage"));
        String stageLabel = first(text(safeContext, "currentPhenologyStageLabel"), text(safeContext, "growthStageLabel"));
        if (cropCode != null && stageCode != null) {
            PhenologyStage stage = phenology.resolveStage(cropCode, stageCode);
            if (stage != null) {
                stageCode = stage.code();
                stageLabel = stage.name();
            }
        }
        String original = query == null ? "" : query.trim();
        boolean referenceExpanded = hasReference(original);
        String anchor = referenceExpanded ? lastUserTurn(text(safeContext, "recentConversation")) : null;
        StringBuilder rewritten = new StringBuilder();
        if (referenceExpanded && anchor != null) rewritten.append("上文对象：").append(anchor).append("；");
        rewritten.append(original);
        if (cropLabel != null && !cropLabel.isBlank()) rewritten.append(" 作物：").append(cropLabel);
        if (stageLabel != null && !stageLabel.isBlank()) rewritten.append(" 当前物候期：").append(stageLabel);
        String queryType = queryType(original);
        int topK = dynamicTopK(queryType, requestedTopK);
        return new RewrittenQuery(original, rewritten.toString().trim(), cropCode, cropLabel,
                stageCode, stageLabel, queryType, topK, referenceExpanded);
    }

    private int dynamicTopK(String queryType, Integer requested) {
        int policy = switch (queryType) {
            case "PHENOLOGY_FACT" -> 3;
            case "DIAGNOSIS_OR_THRESHOLD" -> 5;
            default -> 4;
        };
        if (requested == null || requested < 1) return policy;
        return Math.max(policy, Math.min(8, requested));
    }

    private String queryType(String query) {
        String value = query == null ? "" : query;
        if (contains(value, "现在", "当前", "目前", "物候", "生育期", "下一期", "下一个", "上一期", "上一个")) return "PHENOLOGY_FACT";
        if (contains(value, "病", "虫", "病斑", "黄化", "发黄", "失绿", "阈值", "温度", "高温", "暴雨", "雨量", "降雨", "肥", "水分", "水肥", "灌溉", "浇水", "排水", "药", "防治", "为什么", "原因", "如何判断", "可能")) return "DIAGNOSIS_OR_THRESHOLD";
        return "GENERAL_KNOWLEDGE";
    }

    private boolean hasReference(String value) { return contains(value, "它", "该病", "这个", "那个", "那块", "该地", "上一个", "前面说的", "同样"); }
    private boolean contains(String value, String... terms) { for (String term : terms) if (value.contains(term)) return true; return false; }

    private String lastUserTurn(String history) {
        if (history == null || history.isBlank() || "无".equals(history.trim())) return null;
        Matcher matcher = USER_TURN.matcher(history);
        String result = null;
        while (matcher.find()) result = matcher.group(1).trim();
        if (result == null) {
            String[] lines = history.split("\\R");
            result = lines.length == 0 ? null : lines[lines.length - 1].trim();
        }
        if (result == null || result.isBlank()) return null;
        return result.length() > 240 ? result.substring(result.length() - 240) : result;
    }

    private String first(String first, String second) { return first != null && !first.isBlank() ? first.trim() : second; }
    private String text(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    public record RewrittenQuery(String originalQuery, String query, String cropCode, String cropLabel,
                                 String stageCode, String stageLabel, String queryType, int topK,
                                 boolean referenceExpanded) { }
}

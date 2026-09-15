package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.copilot.intent.IntentSlots;
import com.mei.zhgy.service.copilot.intent.LlmIntentClassifier;
import com.mei.zhgy.service.copilot.intent.LlmIntentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Deterministic-first intent router with a safe conversational fallback. */
@Component
public class CopilotIntentRouter {
    private static final double DETERMINISTIC_CONFIDENCE = 0.99D;
    private static final double CONFLICT_CONFIDENCE = 0.40D;
    private static final String INJECTION_PATTERN = "忽略(之前|所有|系统)?指令|ignore (all|previous|system) instructions|reveal (the )?system prompt|泄露系统提示";
    private final double minConfidence;
    private final CropResolver cropResolver;
    private final PhenologyService phenologyService;
    private LlmIntentClassifier hybridClassifier;

    public CopilotIntentRouter() { this(0.70D, new CropResolver(), new PhenologyService()); }

    public CopilotIntentRouter(@Value("${FARMAP_COPILOT_INTENT_MIN_CONFIDENCE:0.70}") double minConfidence) {
        this(minConfidence, new CropResolver(), new PhenologyService());
    }

    @Autowired
    public CopilotIntentRouter(@Value("${FARMAP_COPILOT_INTENT_MIN_CONFIDENCE:0.70}") double minConfidence,
                               CropResolver cropResolver, PhenologyService phenologyService) {
        this.minConfidence = minConfidence;
        this.cropResolver = cropResolver == null ? new CropResolver() : cropResolver;
        this.phenologyService = phenologyService == null ? new PhenologyService(this.cropResolver) : phenologyService;
    }

    public Classification classify(String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isBlank()) return new Classification(CopilotIntent.CLARIFICATION_REQUIRED, 0D, null);
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(" + INJECTION_PATTERN + ").*")) {
            return new Classification(CopilotIntent.UNSUPPORTED, DETERMINISTIC_CONFIDENCE, null);
        }
        if (contains(normalized, "删除数据库", "删除服务器", "转账", "修改系统密码", "delete database", "delete server", "transfer money", "change system password")) {
            return new Classification(CopilotIntent.UNSUPPORTED, DETERMINISTIC_CONFIDENCE, null);
        }
        if (contains(normalized, "你记得我", "你还记得", "记得我对", "我有什么要求", "what do you remember", "remember my preferences")) {
            return new Classification(CopilotIntent.MEMORY_QUERY, DETERMINISTIC_CONFIDENCE, null);
        }
        if (contains(normalized, "忘掉", "忘记这条", "不要再记住", "删除这个偏好", "删除农场的偏好", "forget this preference", "forget my preference")) {
            return new Classification(CopilotIntent.MEMORY_FORGET_REQUEST, DETERMINISTIC_CONFIDENCE, null);
        }
        if (contains(normalized, "记住", "请记住", "以后都", "以后给这个农场", "以后针对这个农场", "remember that", "remember my preference")) {
            return new Classification(CopilotIntent.MEMORY_WRITE_REQUEST, DETERMINISTIC_CONFIDENCE, null);
        }
        boolean navigation = contains(normalized, "打开", "带我去", "跳转", "导航到", "open ", "go to", "navigate", "navigation");
        boolean action = contains(normalized, "安排", "执行", "启动agent", "启动 agent", "帮我处理", "落实", "schedule", "execute", "start agent");
        boolean priorities = contains(normalized, "今天最需要", "优先处理", "今天关注", "值得关注", "今日重点", "priority", "priorities", "what should i handle today", "what needs attention");
        boolean historicalCases = contains(normalized, "历史病例", "相似病例", "历史案例", "类似案例", "找病例", "similar case", "historical case");
        boolean farmStatus = contains(normalized, "今天农场怎么样", "农场状态", "农场概况", "farm status", "farm overview", "how is the farm");
        boolean diagnosisQuery = contains(normalized, "最近一次诊断", "查看诊断", "解释诊断", "诊断依据", "诊断证据", "latest diagnosis", "show diagnosis", "diagnosis evidence");
        boolean operations = contains(normalized, "任务状态", "任务进度", "任务做到", "做到哪一步", "作业状态", "未完成任务", "待处理任务", "有哪些任务还没完成", "现在怎么样", "目前怎么样", "operations", "task status", "task progress", "open tasks", "pending tasks");
        boolean outcome = contains(normalized, "处理结果", "执行结果", "效果怎么样", "效果如何", "结果如何", "结果呢", "outcome", "execution result");
        boolean recommendation = contains(normalized, "应该怎么办", "怎么处理", "处理建议", "建议", "第一条建议", "recommendation", "what should we do");
        boolean diagnosis = !diagnosisQuery && contains(normalized, "有什么问题", "帮我分析", "分析一下", "分析当前", "怎么了", "为什么", "原因", "诊断", "病害", "病斑", "发黄", "diagnos", "what is wrong", "why");
        boolean asksCurrentOrAdjacentPhenology = contains(normalized, "现在", "当前", "目前", "今天", "今日",
                "下一个", "下一阶段", "下一期", "之后", "上一个", "上一阶段", "上一期", "之前");
        boolean cropPhenologyStatus = cropResolver.containsKnownAlias(normalized)
                && asksCurrentOrAdjacentPhenology
                && hasPhenologyQuestionSignal(normalized);
        boolean fieldStatus = cropPhenologyStatus || contains(normalized, "当前地块", "这个地块", "地块信息", "地块状态", "最近需要注意", "近期需要注意", "最近情况", "当前情况", "农情", "field status", "field condition");
        boolean cropOrPhenologyQuestion = cropResolver.containsKnownAlias(normalized) && hasPhenologySignal(normalized);
        boolean knowledge = !fieldStatus && !historicalCases && (cropOrPhenologyQuestion
                || contains(normalized, "是什么", "怎么办", "科普", "知识", "一般如何", "通常怎么", "knowledge", "what is", "how is"));

        int strongSignals = count(navigation, action, priorities, historicalCases, farmStatus, diagnosisQuery, operations, outcome, recommendation, diagnosis, knowledge, fieldStatus);
        if ((navigation && action) || (navigation && (diagnosis || recommendation) && contains(normalized, "并", " and ")) || strongSignals > 2) {
            return new Classification(CopilotIntent.CLARIFICATION_REQUIRED, CONFLICT_CONFIDENCE, null);
        }
        if (navigation) return new Classification(CopilotIntent.NAVIGATION_REQUEST, DETERMINISTIC_CONFIDENCE, safeRoute(normalized));
        if (priorities) return new Classification(CopilotIntent.PRIORITIES_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (historicalCases) return new Classification(CopilotIntent.HISTORICAL_CASE_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (farmStatus) return new Classification(CopilotIntent.FARM_STATUS_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (diagnosisQuery) return new Classification(CopilotIntent.DIAGNOSIS_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (operations) return new Classification(CopilotIntent.OPERATIONS_STATUS_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (outcome) return new Classification(CopilotIntent.OUTCOME_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (action) return new Classification(CopilotIntent.ACTION_REQUEST, DETERMINISTIC_CONFIDENCE, null);
        if (recommendation) return new Classification(CopilotIntent.RECOMMENDATION_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (diagnosis) return new Classification(CopilotIntent.DIAGNOSIS_REQUEST, DETERMINISTIC_CONFIDENCE, null);
        if (knowledge) return new Classification(CopilotIntent.KNOWLEDGE_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (fieldStatus) return new Classification(CopilotIntent.FIELD_STATUS_QUERY, DETERMINISTIC_CONFIDENCE, null);
        // Safe non-command messages still belong in the Copilot. Route them
        // through lightweight contextual Q&A instead of rejecting them.
        if ((contains(normalized, "请帮忙", "帮帮我", "help me") && normalized.length() <= 12)
                || normalized.matches("^(第一条|第二条|第三条|这个|那个|它|然后呢)[？?。.]?$")) {
            return new Classification(CopilotIntent.CLARIFICATION_REQUIRED, 0.50D, null);
        }
        return new Classification(CopilotIntent.GENERAL_QA, 0.85D, null);
    }

    public boolean belowThreshold(double confidence) { return confidence < minConfidence; }

    /**
     * Keeps the existing deterministic classifier as the safety boundary. The
     * model is only allowed to fill long-tail ambiguity and can never replace
     * protected command, memory, navigation or safety decisions.
     */
    public HybridClassification classifyHybrid(String rawMessage, HybridContext context) {
        Classification rule = classify(rawMessage);
        if (hybridClassifier == null || !hybridClassifier.isEnabled() || !shouldDelegate(rawMessage, rule)) {
            return new HybridClassification(rule, rule, null, null);
        }
        HybridContext safeContext = context == null ? new HybridContext("", null, "") : context;
        if (hybridClassifier.isShadowMode()) {
            hybridClassifier.classifyShadow(rawMessage, safeContext, rule);
            return new HybridClassification(rule, rule, null, null);
        }
        LlmIntentResult llm = hybridClassifier.classify(rawMessage, safeContext, rule);
        Classification finalClassification = arbitrate(rule, llm);
        return new HybridClassification(rule, finalClassification, llm, llm == null ? null : llm.slots());
    }

    /** Optional injection keeps direct/unit construction of this router compatible. */
    @Autowired(required = false)
    public void setHybridClassifier(LlmIntentClassifier hybridClassifier) {
        this.hybridClassifier = hybridClassifier;
    }

    private boolean shouldDelegate(String rawMessage, Classification rule) {
        if (rule == null || rule.intent() == null || isProtected(rule.intent())) return false;
        if (rule.intent() == CopilotIntent.GENERAL_QA || rule.intent() == CopilotIntent.CLARIFICATION_REQUIRED) return true;
        String normalized = rawMessage == null ? "" : rawMessage.trim().toLowerCase(Locale.ROOT);
        // Preserve the public rule confidence for existing callers while
        // allowing soft symptom wording to be observed by the model pool.
        if (rule.intent() == CopilotIntent.DIAGNOSIS_REQUEST && contains(normalized, "有点", "咋回事", "感觉", "似乎")) return true;
        return rule.confidence() < hybridClassifier.takeoverThreshold();
    }

    private Classification arbitrate(Classification rule, LlmIntentResult llm) {
        if (rule == null || llm == null || !llm.ok() || llm.intent() == null) return rule;
        if (llm.needsClarification() || llm.confidence() < hybridClassifier.takeoverThreshold()) return rule;
        if (llm.intent() == CopilotIntent.UNSUPPORTED) return rule;
        if (isProtected(llm.intent()) && !isProtected(rule.intent())) {
            // A model cannot promote an ambiguous utterance into an external
            // action or memory mutation. It may still supply a safe read intent.
            return rule;
        }
        return new Classification(llm.intent(), llm.confidence(), rule.safeRoute());
    }

    private boolean isProtected(CopilotIntent intent) {
        return intent == CopilotIntent.UNSUPPORTED || intent == CopilotIntent.NAVIGATION_REQUEST
                || intent == CopilotIntent.ACTION_REQUEST || intent == CopilotIntent.MEMORY_WRITE_REQUEST
                || intent == CopilotIntent.MEMORY_QUERY || intent == CopilotIntent.MEMORY_FORGET_REQUEST;
    }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private int count(boolean... values) {
        int result = 0;
        for (boolean value : values) if (value) result++;
        return result;
    }

    private boolean hasPhenologySignal(String normalized) {
        if (contains(normalized, "物候期", "生育期", "生长阶段", "phenology", "growth stage")) return true;
        return phenologyService.stageAliases().stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(normalized::contains);
    }

    private boolean hasPhenologyQuestionSignal(String normalized) {
        return hasPhenologySignal(normalized)
                || contains(normalized, "什么时期", "哪个时期", "哪一时期", "什么阶段", "哪个阶段", "哪一阶段", "什么期", "哪个物候",
                "下一个", "下一阶段", "下一期", "之后", "上一个", "上一阶段", "上一期", "之前");
    }

    private String safeRoute(String message) {
        if (contains(message, "任务", "作业", "operations", "task")) return "/operations";
        if (contains(message, "诊断", "病害", "diagnos")) return "/diagnosis";
        if (contains(message, "分析", "agent")) return "/analysis";
        if (contains(message, "知识", "knowledge")) return "/knowledge";
        if (contains(message, "地块", "field")) return "/farm/map";
        return "/overview";
    }

    public record HybridContext(String recentConversation, CopilotPageContext pageContext, String contextCrop) { }

    public record HybridClassification(Classification rule, Classification finalClassification,
                                       LlmIntentResult llm, IntentSlots slots) { }

    public record Classification(CopilotIntent intent, double confidence, String safeRoute) {}
}

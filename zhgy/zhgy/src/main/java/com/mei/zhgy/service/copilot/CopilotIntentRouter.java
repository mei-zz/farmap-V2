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
        String normalized = CopilotQueryNormalizer.forMatching(message);
        if (CopilotSecurityGuard.isPromptOverride(normalized)) {
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
        if (CopilotOperationQuery.farmDirectory(normalized)) return new Classification(CopilotIntent.FARM_STATUS_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (CopilotOperationQuery.viewOf(normalized) != null) return new Classification(CopilotIntent.OPERATIONS_STATUS_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (CopilotOperationQuery.adviceView(normalized)) return new Classification(CopilotIntent.RECOMMENDATION_QUERY, DETERMINISTIC_CONFIDENCE, null);
        if (CopilotOperationQuery.harvestPlan(normalized)) return new Classification(CopilotIntent.ACTION_REQUEST, DETERMINISTIC_CONFIDENCE, null);
        if (isFarmMaturityScan(normalized)) return new Classification(CopilotIntent.FARM_MATURITY_SCAN, DETERMINISTIC_CONFIDENCE, null);
        boolean navigation = contains(normalized, "打开", "带我去", "跳转", "导航到", "open ", "go to", "navigate", "navigation");
        boolean explicitDraft = isTaskCreationRequest(normalized);
        boolean action = explicitDraft ? !contains(normalized, "不要创建", "不创建", "拒绝创建", "无需创建", "不要新建", "不新建", "不要生成", "不要安排", "不安排",
                    "只分析", "仅分析", "只解释", "不要制定", "do not create", "analysis only")
                : contains(normalized, "安排", "执行", "启动agent", "启动 agent", "帮我处理", "落实", "schedule", "execute", "start agent") && !hasNegatedActionConstraint(normalized);
        boolean priorities = contains(normalized, "今天最需要", "优先处理", "今天关注", "值得关注", "今日重点", "priority", "priorities", "what should i handle today", "what needs attention");
        boolean historicalCases = contains(normalized, "历史病例", "相似病例", "历史案例", "类似案例", "找病例", "similar case", "historical case");
        boolean farmStatus = contains(normalized, "今天农场怎么样", "农场状态", "农场概况", "farm status", "farm overview", "how is the farm");
        boolean diagnosisQuery = contains(normalized, "最近一次诊断", "查看诊断", "解释诊断", "诊断依据", "诊断证据", "latest diagnosis", "show diagnosis", "diagnosis evidence");
        boolean operations = contains(normalized, "任务状态", "任务进度", "任务做到", "做到哪一步", "作业状态", "未完成任务", "待处理任务", "有哪些任务还没完成", "现在怎么样", "目前怎么样", "operations", "task status", "task progress", "open tasks", "pending tasks");
        boolean outcome = contains(normalized, "处理结果", "执行结果", "效果怎么样", "效果如何", "结果如何", "结果呢", "outcome", "execution result");
        boolean recommendation = contains(normalized, "应该怎么办", "怎么处理", "处理建议", "建议", "管理方案", "制定管理方案", "结合地块制定方案", "第一条建议", "recommendation", "what should we do");
        // “为什么/原因” alone is a knowledge explanation signal. Diagnosis
        // requires an observable symptom, image request, or explicit
        // diagnosis wording; otherwise a contextual explanation is sent to
        // the knowledge path instead of opening a diagnosis job.
        boolean explicitReferenceImage = contains(normalized, "给我看", "给我一张", "给我几张", "展示", "参考图片", "参考图", "图片", "图像", "照片");
        boolean userProvidedEvidence = contains(normalized, "上传", "现场", "这张", "我的照片", "我拍的", "拍的照片",
                "诊断这", "分析这", "分析图片", "分析图像", "图片分析", "识别图片", "识别图像");
        // A disease appearance question is a reference-knowledge request,
        // not a request to diagnose the current field. Without this branch,
        // "褐腐病是什么样的" also matches the broad "病害" diagnosis
        // signal below and enters the heavy diagnosis path before the
        // reference.images SSE event can be emitted.
        boolean diseaseAppearanceQuestion = contains(normalized,
                "病害", "褐腐病", "褐腐", "穿孔病", "穿孔", "袋果病", "袋果", "锈病", "痘病毒")
                && contains(normalized, "是什么样", "长什么样", "外观", "外观特征", "形态", "特征", "样子", "图片", "图像", "照片");
        boolean visualKnowledge = !userProvidedEvidence && (explicitReferenceImage
                || diseaseAppearanceQuestion
                || (contains(normalized, "萌芽期", "萌芽", "花蕾期", "露白期", "花期", "坐果期", "果实发育期", "果实膨大期", "膨果期", "膨大期", "成熟期", "物候期")
                && contains(normalized, "是什么样", "长什么样", "外观", "特征", "样子")));
        boolean diagnosis = !diagnosisQuery && !visualKnowledge && contains(normalized, "有什么问题", "帮我分析", "分析一下", "分析当前", "现场图片", "图像", "照片", "拍照", "可见异常", "叶片异常", "长势差", "长势不好", "生长异常", "怎么了", "诊断", "病害", "病斑", "发黄", "diagnos", "what is wrong");
        boolean asksCurrentOrAdjacentPhenology = contains(normalized, "现在", "当前", "目前", "今天", "今日",
                "下一个", "下一阶段", "下一期", "之后", "上一个", "上一阶段", "上一期", "之前");
        boolean cropPhenologyStatus = cropResolver.containsKnownAlias(normalized)
                && asksCurrentOrAdjacentPhenology
                && hasPhenologyQuestionSignal(normalized);
        boolean fieldStatus = cropPhenologyStatus || contains(normalized, "当前地块", "这个地块", "那个地块", "刚才那个地块", "上一轮地块", "地块信息", "地块状态", "最近需要注意", "近期需要注意", "最近情况", "当前情况", "农情", "field status", "field condition");
        boolean cropOrPhenologyQuestion = cropResolver.containsKnownAlias(normalized) && hasPhenologySignal(normalized);
        boolean knowledge = !fieldStatus && !historicalCases && (cropOrPhenologyQuestion || visualKnowledge
                || contains(normalized, "是什么", "什么是", "何谓", "为什么", "原因", "怎么办", "长什么样", "有什么作用", "有何作用", "科普", "知识", "一般如何", "通常怎么", "knowledge", "what is", "how is"));

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

    /** Harness routing reserves explicit administrative commands and lets Pi decide which reads to perform. */
    public HybridClassification classifyHarness(String message) {
        String normalized = CopilotQueryNormalizer.forMatching(message);
        String compact = CopilotQueryNormalizer.compactForMatching(message);
        CopilotIntent intent = CopilotIntent.GENERAL_QA;
        String target = null;
        if (compact.isEmpty()) intent = CopilotIntent.CLARIFICATION_REQUIRED;
        else if (CopilotSecurityGuard.isPromptOverride(normalized)
                || contains(normalized, "删除数据库", "删除服务器", "转账", "修改系统密码",
                        "delete database", "delete server", "transfer money", "change system password")) intent = CopilotIntent.UNSUPPORTED;
        else if (compact.matches("(?:请)?(?:查看|列出|显示)?(?:我有|当前有|现在有|有)?(?:哪些|几个|所有)?农场(?:列表|清单)?")) intent = CopilotIntent.FARM_STATUS_QUERY;
        else if (compact.startsWith("请记住") || compact.startsWith("记住") || normalized.startsWith("remember that ")) intent = CopilotIntent.MEMORY_WRITE_REQUEST;
        else if (compact.startsWith("忘掉") || compact.startsWith("忘记这条") || normalized.startsWith("forget this preference")) intent = CopilotIntent.MEMORY_FORGET_REQUEST;
        else if (compact.startsWith("你还记得") || compact.startsWith("你记得我") || normalized.startsWith("what do you remember")) intent = CopilotIntent.MEMORY_QUERY;
        else if (contains(compact, "正式诊断", "生成诊断报告", "出具诊断报告", "深度多模态诊断")
                && !contains(compact, "不要生成诊断报告", "不生成诊断报告", "不用正式诊断", "无需正式诊断")) {
            intent = CopilotIntent.DIAGNOSIS_REQUEST;
        }
        else if (!hasNegatedActionConstraint(normalized)
                && (isTaskCreationRequest(normalized) || CopilotOperationQuery.harvestPlan(normalized))) intent = CopilotIntent.ACTION_REQUEST;
        else if (compact.startsWith("打开") || compact.startsWith("导航到") || normalized.startsWith("go to ")) {
            target = safeRoute(normalized);
            if (target != null) intent = CopilotIntent.NAVIGATION_REQUEST;
        }
        var classification = new Classification(intent, DETERMINISTIC_CONFIDENCE, target);
        return new HybridClassification(classification, classification, null, null);
    }

    public boolean belowThreshold(double confidence) { return confidence < minConfidence; }

    /**
     * Keeps the existing deterministic classifier as the safety boundary. The
     * model is only allowed to fill long-tail ambiguity and can never replace
     * protected command, memory, navigation or safety decisions.
     */
    public HybridClassification classifyHybrid(String rawMessage, HybridContext context) {
        return classifyHybrid(rawMessage, context, false);
    }

    /**
     * Chat requests may carry user-uploaded photos. That is evidence about the
     * current scene, not a request for atlas images, so make that signal
     * available to routing without putting image URLs into the intent prompt.
     */
    public HybridClassification classifyHybrid(String rawMessage, HybridContext context, boolean hasUserImages) {
        Classification rule = classify(rawMessage);
        if (rule.intent() == CopilotIntent.OPERATIONS_STATUS_QUERY && CopilotOperationQuery.viewOf(rawMessage) != null
                || rule.intent() == CopilotIntent.FARM_STATUS_QUERY && CopilotOperationQuery.farmDirectory(rawMessage)) {
            return new HybridClassification(rule, rule, null, null);
        }
        String imageText = rawMessage == null ? "" : rawMessage.toLowerCase(java.util.Locale.ROOT);
        if ((!isProtected(rule.intent()) || rule.intent() == CopilotIntent.FARM_MATURITY_SCAN)
                && context != null && context.pageContext() != null && context.pageContext().getAnalysisRunId() != null
                && contains(imageText, "本次报告", "这份报告", "这次报告", "这份观测", "判断依据", "不确定性")
                && !contains(imageText, "重新扫描", "重新分析", "再次扫描", "现在抓拍", "重新抓拍", "历史", "对比", "排名", "排行", "制定", "安排", "创建", "执行")) {
            return new HybridClassification(rule, new Classification(CopilotIntent.GENERAL_QA, DETERMINISTIC_CONFIDENCE, null), null, null);
        }
        if (!isProtected(rule.intent()) && hasUserImages && contains(imageText, "成熟度", "是否成熟", "物候", "长势", "生长情况", "maturity", "phenology", "growth")
                && contains(imageText, "分析", "判断", "评估", "看看", "看一下", "assess", "analyze")
                && !contains(imageText, "如何", "怎么", "原理", "什么是", "不要分析", "不分析", "别分析", "do not analyze", "how to")) {
            rule = new Classification(CopilotIntent.FARM_MATURITY_SCAN, DETERMINISTIC_CONFIDENCE, null);
        }
        if (hasUserImages && rule.intent() != CopilotIntent.FARM_MATURITY_SCAN && shouldTreatAsImageDiagnosis(rawMessage, rule)) {
            rule = new Classification(CopilotIntent.DIAGNOSIS_REQUEST, DETERMINISTIC_CONFIDENCE, rule.safeRoute());
        }
        if (hybridClassifier == null || !hybridClassifier.isEnabled() || !shouldDelegate(rawMessage, rule)) {
            return new HybridClassification(rule, rule, null, null);
        }
        HybridContext safeContext = context == null ? new HybridContext("", null, "") : context;
        if (hybridClassifier.isRuleOnlyMode()) {
            return new HybridClassification(rule, rule, null, null);
        }
        if (hybridClassifier.isShadowMode()) {
            hybridClassifier.classifyShadow(rawMessage, safeContext, rule);
            return new HybridClassification(rule, rule, null, null);
        }
        LlmIntentResult llm = hybridClassifier.classify(rawMessage, safeContext, rule);
        Classification finalClassification = arbitrate(rule, llm);
        return new HybridClassification(rule, finalClassification, llm, llm == null ? null : llm.slots());
    }

    private boolean shouldTreatAsImageDiagnosis(String rawMessage, Classification rule) {
        if (rule == null || rule.intent() == null || isProtected(rule.intent())
                || rule.intent() == CopilotIntent.DIAGNOSIS_REQUEST) return false;
        String normalized = CopilotQueryNormalizer.forMatching(rawMessage);
        if (contains(normalized, "参考图", "参考图片", "给我看", "展示参考", "图库")) return false;
        return !isGreeting(normalized) && !contains(normalized, "打开", "跳转", "导航", "记住", "忘记");
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
        return intent == CopilotIntent.UNSUPPORTED || intent == CopilotIntent.NAVIGATION_REQUEST || intent == CopilotIntent.FARM_MATURITY_SCAN
                || intent == CopilotIntent.ACTION_REQUEST || intent == CopilotIntent.MEMORY_WRITE_REQUEST
                || intent == CopilotIntent.MEMORY_QUERY || intent == CopilotIntent.MEMORY_FORGET_REQUEST;
    }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private boolean isFarmMaturityScan(String text) {
        if (isAgentPlanningRequest(text)) return false;
        boolean visualTopic = contains(text, "成熟度", "熟没熟", "熟了没有", "是否成熟", "果实熟了", "可采收分布", "物候", "生长情况", "长势", "生长状态", "ripe", "ripeness", "maturity", "phenology", "growth", "适合采收", "采收排序", "采收排名");
        boolean explicitCamera = contains(text, "摄像头", "camera") && contains(text, "分析", "评估", "查看", "看看", "比较", "对比", "analyze", "assess", "compare");
        if (!visualTopic && !explicitCamera) return false;
        if (contains(text, "不调用摄像头", "不要调用", "不用摄像头", "不使用摄像头", "不抓拍", "不要抓拍", "别抓拍", "不要分析", "不分析", "别分析", "不要扫描", "不扫描", "do not analyze", "do not scan", "只问原理", "仅解释")) return false;
        boolean requested = contains(text, "帮我", "给我", "请", "看一下", "看看", "扫一下", "扫描", "展示", "查看", "评估", "分析", "判断", "比较", "对比", "适合采收", "采收排序", "采收排名", "check", "scan", "show", "assess", "analyze", "compare");
        if (contains(text, "如何", "怎么", "原理", "什么是", "能不能", "是否可以", "how to")) {
            return contains(text, "帮我抓拍", "请抓拍", "现在抓拍", "请扫描", "帮我扫描");
        }
        return requested && contains(text, "农场", "全园", "果园", "地块", "各区", "摄像头", "监控", "抓拍", "成熟度分布", "适合采收", "采收排序", "采收排名", "照片", "图片", "图中", "这张图", "farm", "camera", "orchard", "image", "photo");
    }

    private boolean isGreeting(String value) {
        return value.matches("^(你好|您好|嗨|哈喽|hello|hi|谢谢|多谢|辛苦了|在吗)[！!。,.？? ]*$");
    }

    /**
     * A write verb inside a negative constraint is not an action request. This
     * is intentionally conservative: the request can still be diagnosed or
     * answered, but it cannot enter an Agent-start path by keyword accident.
     */
    private boolean hasNegatedActionConstraint(String normalized) {
        return contains(normalized, "不执行", "不要执行", "别执行", "暂不执行", "不创建任务", "不要创建", "无需创建",
                "不安排", "不要安排", "只分析", "仅分析", "先给方案", "等我确认", "do not execute", "don't execute",
                "do not create", "without executing", "analysis only", "wait for my confirmation");
    }

    /**
     * Direct Operations requests do not need an existing Diagnosis. They still
     * enter the Agent approval workflow, where the server validates the current
     * field and the Agent produces a proposed task before any write.
     */
    private boolean isTaskCreationRequest(String normalized) {
        return contains(normalized, "创建任务", "创建农事任务", "生成任务", "新建任务", "创建浇水任务", "创建施肥任务",
                "安排农事任务", "安排浇水任务", "安排施肥任务", "create task", "schedule task")
                || normalized.matches(".*(?:创建|生成|新建|安排).{0,12}任务.*")
                || isAgentPlanningRequest(normalized);
    }

    private boolean isAgentPlanningRequest(String normalized) {
        boolean planningObject = contains(normalized, "农事计划", "农事安排", "作业计划", "管理计划", "采收计划", "一周计划", "周计划", "未来一周", "本周计划", "farm plan", "weekly plan");
        boolean directive = contains(normalized, "帮我制定", "请制定", "给我制定", "制定一份", "帮我生成", "请生成", "给我生成", "生成一份",
                "帮我规划", "请规划", "给我规划", "规划一份", "create a plan", "make a plan");
        return planningObject && directive;
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

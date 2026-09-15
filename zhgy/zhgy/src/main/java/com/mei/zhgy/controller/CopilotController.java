package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.agent.AgentAccessException;
import com.mei.zhgy.service.copilot.CopilotRouteRequest;
import com.mei.zhgy.service.copilot.CopilotRouteResult;
import com.mei.zhgy.service.copilot.CopilotRouteService;
import com.mei.zhgy.service.copilot.CopilotIntent;
import com.mei.zhgy.service.copilot.CopilotResolutionStatus;
import com.mei.zhgy.service.copilot.CopilotPageContext;
import com.mei.zhgy.service.copilot.CopilotConversationException;
import com.mei.zhgy.service.copilot.CopilotLongTermMemoryService;
import com.mei.zhgy.service.copilot.CopilotConversationView;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.ai.AiTaskType;
import com.mei.zhgy.service.ai.ModelCallRequest;
import com.mei.zhgy.service.ai.ModelGateway;
import com.mei.zhgy.service.ai.ModelCallResult;
import com.mei.zhgy.service.ai.ModelCallException;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever;
import com.mei.zhgy.service.diagnosis.DiagnosisJobService;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.agent.AgentRun;
import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyResult;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.reference.ReferenceImage;
import com.mei.zhgy.service.reference.ReferenceImageAttacher;
import com.mei.zhgy.result.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Server-side Copilot route boundary. It classifies and resolves; it does not execute writes. */
@RestController
@RequestMapping("/api/copilot")
public class CopilotController {
    private static final ZoneId COPILOT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter COPILOT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE);
    private final CopilotRouteService routes;
    private final com.mei.zhgy.service.copilot.CopilotConversationService conversations;
    private final CopilotLongTermMemoryService memories;
    private final ModelGateway modelGateway;
    private final KnowledgeEvidenceRetriever knowledgeRetriever;
    private final DiagnosisJobService diagnosisJobs;
    private final FarmFieldService farmFieldService;
    private final AgentRuntime agentRuntime;
    private final FieldFactsService fieldFactsService;
    private final CropResolver cropResolver;
    private final PhenologyService phenologyService;
    private final ReferenceImageAttacher referenceImageAttacher;
    @org.springframework.beans.factory.annotation.Value("${zhgy.upload.base-url:http://localhost:24009/uploads}")
    private String uploadBaseUrl;
    @org.springframework.beans.factory.annotation.Value("${tencent-cos.public-base-url:}")
    private String cosPublicBaseUrl;
    @org.springframework.beans.factory.annotation.Value("${tencent-cos.bucket:}")
    private String cosBucket;
    @org.springframework.beans.factory.annotation.Value("${tencent-cos.region:}")
    private String cosRegion;
    @org.springframework.beans.factory.annotation.Value("${farmap.copilot.allow-ungrounded:true}")
    private boolean allowUngrounded = true;
    @org.springframework.beans.factory.annotation.Value("${farmap.ai.generation.copilot-max-output-tokens:2048}")
    private int copilotMaxOutputTokens = 2048;
    private final Executor chatExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "farmap-copilot-chat");
        thread.setDaemon(true);
        return thread;
    });

    public CopilotController(CopilotRouteService routes) { this(routes, null, null, null, null, null, null, null, null, null, null, null); }

    public CopilotController(CopilotRouteService routes, com.mei.zhgy.service.copilot.CopilotConversationService conversations) {
        this(routes, conversations, null, null, null, null, null, null, null, null, null, null);
    }

    public CopilotController(CopilotRouteService routes, com.mei.zhgy.service.copilot.CopilotConversationService conversations, CopilotLongTermMemoryService memories) {
        this(routes, conversations, memories, null, null, null, null, null, null, null, null, null);
    }

    public CopilotController(CopilotRouteService routes,
                             com.mei.zhgy.service.copilot.CopilotConversationService conversations,
                             CopilotLongTermMemoryService memories,
                             ModelGateway modelGateway) {
        this(routes, conversations, memories, modelGateway, null, null, null, null, null, null, null, null);
    }

    public CopilotController(CopilotRouteService routes,
                             com.mei.zhgy.service.copilot.CopilotConversationService conversations,
                             CopilotLongTermMemoryService memories,
                             ModelGateway modelGateway,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) KnowledgeEvidenceRetriever knowledgeRetriever) {
        this(routes, conversations, memories, modelGateway, knowledgeRetriever, null, null, null, null, null, null, null);
    }

    /** Compatibility overload for callers that do not yet provide the atlas attacher. */
    public CopilotController(CopilotRouteService routes,
                             com.mei.zhgy.service.copilot.CopilotConversationService conversations,
                             CopilotLongTermMemoryService memories,
                             ModelGateway modelGateway,
                             KnowledgeEvidenceRetriever knowledgeRetriever,
                             DiagnosisJobService diagnosisJobs,
                             FarmFieldService farmFieldService,
                             AgentRuntime agentRuntime,
                             FieldFactsService fieldFactsService,
                             CropResolver cropResolver,
                             PhenologyService phenologyService) {
        this(routes, conversations, memories, modelGateway, knowledgeRetriever, diagnosisJobs, farmFieldService,
                agentRuntime, fieldFactsService, cropResolver, phenologyService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CopilotController(CopilotRouteService routes,
                             com.mei.zhgy.service.copilot.CopilotConversationService conversations,
                             CopilotLongTermMemoryService memories,
                             ModelGateway modelGateway,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) KnowledgeEvidenceRetriever knowledgeRetriever,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) DiagnosisJobService diagnosisJobs,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) FarmFieldService farmFieldService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) AgentRuntime agentRuntime,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) FieldFactsService fieldFactsService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) CropResolver cropResolver,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) PhenologyService phenologyService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) ReferenceImageAttacher referenceImageAttacher) {
        this.routes = routes;
        this.conversations = conversations;
        this.memories = memories;
        this.modelGateway = modelGateway;
        this.knowledgeRetriever = knowledgeRetriever;
        this.diagnosisJobs = diagnosisJobs;
        this.farmFieldService = farmFieldService;
        this.agentRuntime = agentRuntime;
        this.fieldFactsService = fieldFactsService;
        this.cropResolver = cropResolver == null ? new CropResolver() : cropResolver;
        this.phenologyService = phenologyService == null ? new PhenologyService(this.cropResolver) : phenologyService;
        this.referenceImageAttacher = referenceImageAttacher;
    }

    @PostMapping("/conversations")
    public com.mei.zhgy.service.copilot.CopilotConversationView createConversation() {
        requireConversationService();
        Long userId = requireUser();
        var created = conversations.create(userId);
        return conversations.view(created.getConversationId(), userId);
    }

    @GetMapping("/conversations/{conversationId}")
    public com.mei.zhgy.service.copilot.CopilotConversationView getConversation(@PathVariable String conversationId) {
        requireConversationService();
        return conversations.view(conversationId, requireUser());
    }

    @PostMapping("/route")
    public CopilotRouteResult route(@RequestBody CopilotRouteRequest request) {
        return routes.route(request, requireUser());
    }

    /**
     * Lightweight conversational path. It owns classification and knowledge
     * retrieval so ordinary questions do not wait for or create an Agent Run.
     * Heavy intents keep their diagnosis/command paths when fully resolved.
     * If a read capability lacks context or is unavailable, it falls back to
     * the same layered conversational answer path without executing writes.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) CopilotChatRequest request) {
        Long userId = requireUser();
        CopilotChatRequest safe = request == null ? new CopilotChatRequest() : request;
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(ignored -> emitter.complete());
        CompletableFuture.runAsync(() -> streamChat(safe, userId, emitter), chatExecutor);
        return emitter;
    }

    private void streamChat(CopilotChatRequest request, Long userId, SseEmitter emitter) {
        try {
            send(emitter, "status", Map.of("message", "正在结合你的农场情况…"));
            CopilotRouteResult routed = routes.route(request, userId);
            send(emitter, "route", routed);
            if (routed.getIntent() != CopilotIntent.GENERAL_QA && routed.getIntent() != CopilotIntent.KNOWLEDGE_QUERY) {
                if (routed.getIntent() == CopilotIntent.DIAGNOSIS_REQUEST && routed.getResolutionStatus() == CopilotResolutionStatus.RESOLVED) {
                    try {
                        startDiagnosisAndAgent(request, routed, userId, emitter);
                    } catch (FieldAccessException error) {
                        throw error;
                    } catch (RuntimeException failure) {
                        streamConversationalFallback(request, routed, userId, emitter, "DIAGNOSIS_CAPABILITY_UNAVAILABLE");
                    }
                    return;
                }
                if (routed.getIntent() == CopilotIntent.ACTION_REQUEST && routed.getResolutionStatus() == CopilotResolutionStatus.RESOLVED) {
                    try {
                        startAgentFromDiagnosis(request, routed, userId, emitter);
                    } catch (FieldAccessException error) {
                        throw error;
                    } catch (RuntimeException failure) {
                        streamConversationalFallback(request, routed, userId, emitter, "AGENT_CAPABILITY_UNAVAILABLE");
                    }
                    return;
                }
                if (routed.getResolutionStatus() == CopilotResolutionStatus.FORBIDDEN
                        || routed.getResolutionStatus() == CopilotResolutionStatus.UNSUPPORTED) {
                    String rejection = routed.getClarificationQuestion() == null ? "当前请求未通过访问或安全校验，未执行任何诊断、Agent 或写操作。" : routed.getClarificationQuestion();
                    streamDelta(emitter, rejection);
                    appendChatAnswer(request, routed, rejection, userId);
                    sendDone(emitter, "REJECTED");
                } else if (routed.getIntent() == CopilotIntent.CLARIFICATION_REQUIRED) {
                    String clarification = routed.getClarificationQuestion() == null ? "请明确要查询的对象或希望执行的动作。" : routed.getClarificationQuestion();
                    streamDelta(emitter, clarification);
                    appendChatAnswer(request, routed, clarification, userId);
                    sendDone(emitter, "COMPLETED");
                } else {
                    streamConversationalFallback(request, routed, userId, emitter, "HEAVY_CONTEXT_OR_CAPABILITY_UNAVAILABLE");
                }
                return;
            }
            if (routed.isRequiresClarification() || routed.getResolutionStatus() != CopilotResolutionStatus.RESOLVED) {
                String clarification = routed.getClarificationQuestion() == null ? "请先选择要讨论的农场或地块。" : routed.getClarificationQuestion();
                streamDelta(emitter, clarification);
                appendChatAnswer(request, routed, clarification, userId);
                sendDone(emitter, "COMPLETED");
                return;
            }

            send(emitter, "status", Map.of("message", "正在检索农业知识库…"));
            CopilotFacts facts = resolveCopilotFacts(request, routed, userId);
            if ("NO_FIELD".equals(facts.status())) send(emitter, "status", Map.of("message", "未选择地块，以下只能回答通用农业知识。", "code", "NO_FIELD"));
            if ("UNKNOWN_CROP".equals(facts.status())) send(emitter, "status", Map.of("message", "暂不支持当前作物，未按其他作物猜测。", "code", "UNKNOWN_CROP"));
            if ("UNAVAILABLE".equals(facts.status())) send(emitter, "status", Map.of("message", "当前地块事实暂不可用，未使用未经验证的地块数据。", "code", "FIELD_FACTS_UNAVAILABLE"));
            if ("PUBLIC_GENERIC".equals(facts.status())) send(emitter, "status", Map.of("message", "未使用地块实时数据，已按" + facts.cropLabel() + "通用物候历和服务器日期推算。", "code", "PUBLIC_GENERIC"));
            KnowledgeBundle knowledge = retrieveKnowledge(request, routed, facts, userId);
            send(emitter, "knowledge", Map.of("count", knowledge.items().size(), "items", knowledge.items(), "status", knowledge.status()));
            List<ReferenceImage> referenceImages = referenceImageAttacher == null
                    ? List.of() : referenceImageAttacher.forChat(request.getMessage(), facts.crop(), facts.growthStage());
            send(emitter, "reference.images", Map.of("count", referenceImages.size(), "items", referenceImages));
            Map<String, Object> source = answerSource(routed, facts, knowledge, request.getMessage());
            send(emitter, "answer.source", source);
            if ("UNAVAILABLE".equals(knowledge.status())) send(emitter, "status", Map.of("message", "当前知识库暂不可用；不会伪造地块事实，通用问题将按兜底策略处理。", "code", "KNOWLEDGE_UNAVAILABLE"));
            send(emitter, "status", Map.of("message", "正在生成建议…"));
            if (modelGateway == null) send(emitter, "status", Map.of("message", "模型暂不可用，无法生成建议。", "code", "MODEL_UNAVAILABLE"));
            String answer = streamModelAnswer(request, routed, facts, knowledge, userId, emitter);
            appendChatAnswer(request, routed, answer, userId);
            sendDone(emitter, "COMPLETED");
        } catch (Exception error) {
            try {
                send(emitter, "error", Map.of("message", "Copilot 暂时不可用，请稍后重试。"));
                sendDone(emitter, "FAILED");
            } catch (IOException ignored) {
                emitter.completeWithError(error);
                return;
            }
            emitter.complete();
        }
    }

    private void startDiagnosisAndAgent(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter) throws IOException {
        if (diagnosisJobs == null) throw new IllegalStateException("诊断任务服务未初始化");
        DiagnosisAnalyzeRequest diagnosis = new DiagnosisAnalyzeRequest();
        diagnosis.setFieldId(routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFieldId());
        diagnosis.setFarmId(parseInt(routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFarmId()));
        diagnosis.setQuery(request.getMessage());
        diagnosis.setTopK(5);
        diagnosis.setImageUrls(trustedImageUrls(request.getImageUrls()));
        diagnosis.setEnabledModalities(List.of("camera", "weather", "historical_case", "knowledge", "soil", "spatial"));
        if (fieldFactsService != null && diagnosis.getFieldId() != null) {
            FieldFacts facts = fieldFactsService.forField(diagnosis.getFieldId(), userId);
            if (facts.unknownCrop()) throw new CopilotConversationException("UNKNOWN_CROP", "当前地块的作物暂不在支持目录中，无法可靠启动诊断。", 422);
            if (facts.ready()) {
                diagnosis.setCrop(facts.cropCode());
                diagnosis.setGrowthStage(facts.currentGrowthStage());
                diagnosis.setTrustedFieldContext(facts.trustedContext());
            }
        } else if (farmFieldService != null) farmFieldService.applyToDiagnosisRequest(diagnosis, userId);
        DiagnosisJobService.DiagnosisJobResponse job = diagnosisJobs.submit(diagnosis, userId);
        send(emitter, "diagnosis.job", Map.of("jobId", job.getJobId(), "message", job.getMessage() == null ? "诊断任务已创建。" : job.getMessage()));
        DiagnosisJobService.DiagnosisJobResponse completed = waitForDiagnosis(job.getJobId(), userId, emitter);
        if (completed == null || !"COMPLETED".equalsIgnoreCase(completed.getStatus()) || completed.getDiagnosisId() == null) {
            String message = completed == null || completed.getError() == null ? "诊断任务未完成，未创建 Agent Run。" : completed.getError();
            streamDelta(emitter, message);
            sendDone(emitter, "FAILED");
            return;
        }
        String recommendationId = completed.getResult() == null || completed.getResult().getRecommendations() == null || completed.getResult().getRecommendations().isEmpty()
                ? null : completed.getResult().getRecommendations().get(0).getId();
        AgentRun run = createAgentFromDiagnosis(request.getMessage(), completed.getDiagnosisId(), recommendationId, userId);
        send(emitter, "run.created", Map.of("runId", run.getRunId(), "diagnosisId", completed.getDiagnosisId(), "status", "PLANNING"));
        sendDone(emitter, "RUN_CREATED");
    }

    private void startAgentFromDiagnosis(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter) throws IOException {
        if (agentRuntime == null || routed.getResolvedContext() == null || routed.getResolvedContext().getDiagnosisId() == null
                || routed.getResolvedContext().getRecommendationId() == null) {
            throw new IllegalStateException("当前建议缺少可验证的 Diagnosis provenance。");
        }
        AgentRun run = createAgentFromDiagnosis(request.getMessage(), routed.getResolvedContext().getDiagnosisId(), routed.getResolvedContext().getRecommendationId(), userId);
        send(emitter, "run.created", Map.of("runId", run.getRunId(), "diagnosisId", routed.getResolvedContext().getDiagnosisId(), "status", "PLANNING"));
        sendDone(emitter, "RUN_CREATED");
    }

    /**
     * Read-only degradation for supported intents whose heavy capability is
     * missing context. This path never creates a diagnosis, Agent Run, task,
     * or other write operation.
     */
    private void streamConversationalFallback(CopilotChatRequest request, CopilotRouteResult routed,
                                               Long userId, SseEmitter emitter, String reason) throws IOException {
        send(emitter, "fallback", Map.of("reason", reason, "intent", routed.getIntent().name(), "mode", "CONVERSATIONAL"));
        send(emitter, "status", Map.of("message", "当前能力缺少可验证上下文，已转为普通对话回答；不会因本次降级自动执行写操作。", "code", "CONVERSATIONAL_FALLBACK"));
        CopilotFacts facts = resolveCopilotFacts(request, routed, userId);
        KnowledgeBundle knowledge = retrieveKnowledge(request, routed, facts, userId);
        send(emitter, "knowledge", Map.of("count", knowledge.items().size(), "items", knowledge.items(), "status", knowledge.status()));
        List<ReferenceImage> referenceImages = referenceImageAttacher == null
                ? List.of() : referenceImageAttacher.forChat(request.getMessage(), facts.crop(), facts.growthStage());
        send(emitter, "reference.images", Map.of("count", referenceImages.size(), "items", referenceImages));
        send(emitter, "answer.source", answerSource(routed, facts, knowledge, request.getMessage()));
        String answer = streamModelAnswer(request, routed, facts, knowledge, userId, emitter);
        appendChatAnswer(request, routed, answer, userId);
        sendDone(emitter, "COMPLETED");
    }

    private AgentRun createAgentFromDiagnosis(String goal, String diagnosisId, String recommendationId, Long userId) {
        if (agentRuntime == null) throw new IllegalStateException("Agent Runtime 未初始化");
        return agentRuntime.createRunFromPersistedDiagnosis(goal, diagnosisId, recommendationId, userId);
    }

    private DiagnosisJobService.DiagnosisJobResponse waitForDiagnosis(String jobId, Long userId, SseEmitter emitter) throws IOException {
        for (int attempt = 0; attempt < 1800; attempt++) {
            DiagnosisJobService.DiagnosisJobResponse current = diagnosisJobs.get(jobId, userId);
            if (current == null) return null;
            if (current.getMessage() != null) send(emitter, "status", Map.of("message", current.getMessage(), "stage", current.getStage() == null ? "RUNNING" : current.getStage()));
            if ("COMPLETED".equalsIgnoreCase(current.getStatus()) || "FAILED".equalsIgnoreCase(current.getStatus())) return current;
            try { Thread.sleep(500L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    private CopilotFacts resolveCopilotFacts(CopilotChatRequest request, CopilotRouteResult routed, Long userId) {
        LocalDate today = LocalDate.now(COPILOT_ZONE);
        String currentDate = today.format(COPILOT_DATE_FORMAT);
        String fieldId = routed == null || routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFieldId();
        if (fieldFactsService != null) {
            FieldFacts facts = fieldFactsService.forField(fieldId, userId);
            CopilotFacts resolved = CopilotFacts.from(facts);
            if (resolved.ready()) return resolved;
            CopilotFacts publicFacts = publicFactsFromQuery(request, userId, today, currentDate);
            return publicFacts == null ? resolved : publicFacts;
        }
        if (fieldId == null || fieldId.isBlank()) {
            CopilotFacts publicFacts = publicFactsFromQuery(request, userId, today, currentDate);
            return publicFacts == null ? CopilotFacts.empty(currentDate, "NO_FIELD") : publicFacts;
        }
        if (farmFieldService == null) {
            CopilotFacts publicFacts = publicFactsFromQuery(request, userId, today, currentDate);
            return publicFacts == null ? CopilotFacts.empty(currentDate, "UNAVAILABLE") : publicFacts;
        }
        try {
            FieldContext trusted = farmFieldService.context(fieldId, userId);
            String rawCrop = mapText(trusted.getCrop(), "name");
            String growthStage = mapText(trusted.getField(), "growthStage");
            CropResolver.ResolvedCrop crop = cropResolver.resolve(rawCrop);
            if (!crop.ready()) {
                CopilotFacts publicFacts = publicFactsFromQuery(request, userId, today, currentDate);
                return publicFacts == null ? CopilotFacts.empty(currentDate, "UNKNOWN_CROP") : publicFacts;
            }
            PhenologyResult result = phenologyService.calculate(crop.code(), today, growthStage);
            return CopilotFacts.from(currentDate, crop, result, phenologyService.orderedStageNames(crop.code()), phenologyService.cycleType(crop.code()),
                    Map.of("serverDate", "SERVER_ASIA_SHANGHAI", "currentStage", growthStage == null ? "SERVER_RULE" : "MYSQL_FIELD"));
        } catch (FieldAccessException error) {
            // Preserve the existing owner/access boundary. Do not turn an
            // authorization failure into a cross-user fact lookup.
            throw error;
        } catch (RuntimeException ignored) {
            CopilotFacts publicFacts = publicFactsFromQuery(request, userId, today, currentDate);
            return publicFacts == null ? CopilotFacts.empty(currentDate, "UNAVAILABLE") : publicFacts;
        }
    }

    private CopilotFacts publicFactsFromQuery(String message, LocalDate today, String currentDate) {
        CropResolver.ResolvedCrop crop = cropResolver.resolve(message);
        if (!crop.ready()) return null;
        PhenologyResult result = phenologyService.calculate(crop.code(), today, null);
        return CopilotFacts.from(currentDate, crop, result, phenologyService.orderedStageNames(crop.code()), phenologyService.cycleType(crop.code()),
                Map.of("serverDate", "SERVER_ASIA_SHANGHAI", "crop", "PUBLIC_CROP_QUERY", "currentStage", "SERVER_RULE"), "PUBLIC_GENERIC");
    }

    private CopilotFacts publicFactsFromQuery(CopilotChatRequest request, Long userId, LocalDate today, String currentDate) {
        String message = request == null ? null : request.getMessage();
        CropResolver.ResolvedCrop crop = cropResolver.resolve(message);
        if (!crop.ready() && !crop.unknown()) crop = cropResolver.resolve(recentConversation(request, userId));
        if (!crop.ready()) return null;
        PhenologyResult result = phenologyService.calculate(crop.code(), today, null);
        return CopilotFacts.from(currentDate, crop, result, phenologyService.orderedStageNames(crop.code()), phenologyService.cycleType(crop.code()),
                Map.of("serverDate", "SERVER_ASIA_SHANGHAI", "crop", "PUBLIC_CROP_QUERY", "currentStage", "SERVER_RULE"), "PUBLIC_GENERIC");
    }

    private KnowledgeBundle retrieveKnowledge(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts, Long userId) {
        if (knowledgeRetriever == null) return new KnowledgeBundle("UNAVAILABLE", List.of());
        // Without a crop, ask the retriever for only untagged public chunks;
        // this keeps a generic question from mixing crop-specific evidence.
        boolean untaggedOnly = facts == null || facts.crop() == null || facts.crop().isBlank();
        String crop = facts == null ? null : facts.crop();
        String growthStage = facts == null ? null : facts.growthStage();
        DiagnosisAnalyzeRequest query = new DiagnosisAnalyzeRequest();
        CopilotPageContext page = request.getPageContext();
        String fieldId = routed == null || routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFieldId();
        if (fieldId == null || fieldId.isBlank()) fieldId = page == null ? null : page.getFieldId();
        String farmId = routed == null || routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFarmId();
        if (farmId == null || farmId.isBlank()) farmId = page == null ? null : page.getFarmId();
        query.setFieldId(fieldId == null || fieldId.isBlank() ? null : fieldId);
        query.setFarmId(parseInt(farmId));
        query.setQuery(request.getMessage());
        query.setCrop(crop);
        query.setGrowthStage(growthStage);
        query.setTopK(null);
        Map<String, Object> rewriteContext = new LinkedHashMap<>();
        rewriteContext.put("recentConversation", recentConversation(request, userId));
        if (facts != null) {
            rewriteContext.put("crop", facts.crop());
            rewriteContext.put("cropLabel", facts.cropLabel());
            rewriteContext.put("currentPhenologyStage", facts.growthStage());
            rewriteContext.put("currentPhenologyStageLabel", facts.growthStageLabel());
        }
        rewriteContext.put("untaggedOnly", untaggedOnly);
        query.setContext(rewriteContext);
        query.setEnabledModalities(List.of("knowledge"));
        var result = knowledgeRetriever.retrieveWithStatus(query, userId);
        List<Map<String, Object>> output = new ArrayList<>();
        if (result == null || result.getEvidence() == null) return new KnowledgeBundle("UNAVAILABLE", output);
        result.getEvidence().forEach(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", item.getTitle());
            value.put("content", item.getPreview() == null ? item.getSummary() : item.getPreview().getTextSnippet());
            value.put("score", item.getScore());
            value.put("source", item.getSource() == null ? null : item.getSource().getDocument());
            value.put("chunkId", item.getId());
            output.add(value);
        });
        return new KnowledgeBundle(result.getStatus(), output);
    }

    private String streamModelAnswer(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts,
                                     KnowledgeBundle knowledge, Long userId, SseEmitter emitter) {
        String deterministicAnswer = deterministicPhenologyAnswer(request.getMessage(), facts);
        if (deterministicAnswer != null) {
            streamDelta(emitter, deterministicAnswer);
            return deterministicAnswer;
        }
        boolean privateFactsAvailable = facts != null && "READY".equals(facts.status());
        if (!allowUngrounded && !privateFactsAvailable && (knowledge == null || knowledge.items().isEmpty())) {
            String blocked = "当前没有可验证的地块或公共知识依据；通用模型兜底已关闭，暂不生成农业结论。请先选择地块，或明确作物并补充问题。";
            streamDelta(emitter, blocked);
            return blocked;
        }
        if (modelGateway == null) {
            String unavailable = "当前 Copilot 模型暂不可用；知识检索结果已保留，请稍后重试。";
            streamDelta(emitter, unavailable);
            return unavailable;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        if (routed.getResolvedContext() != null) {
            context.put("farmId", routed.getResolvedContext().getFarmId());
            context.put("fieldId", routed.getResolvedContext().getFieldId());
            context.put("diagnosisId", routed.getResolvedContext().getDiagnosisId());
        }
        context.put("currentDate", facts.currentDate());
        context.put("currentDateSource", "SERVER_ASIA_SHANGHAI");
        context.put("crop", facts.crop());
        context.put("growthStage", facts.growthStage());
        context.put("currentGrowthStage", facts.growthStage());
        context.put("growthStageLabel", facts.growthStageLabel());
        context.put("dateDerivedGrowthStage", facts.dateDerivedGrowthStage());
        context.put("dateDerivedGrowthStageLabel", facts.dateDerivedGrowthStageLabel());
        context.put("registeredGrowthStage", facts.registeredGrowthStage());
        context.put("registeredGrowthStageLabel", facts.registeredGrowthStageLabel());
        context.put("previousGrowthStage", facts.previousGrowthStage());
        context.put("previousGrowthStageLabel", facts.previousGrowthStageLabel());
        context.put("nextGrowthStage", facts.nextGrowthStage());
        context.put("nextGrowthStageLabel", facts.nextGrowthStageLabel());
        context.put("daysUntilNextPhenologyStage", facts.daysUntilNext());
        context.put("phenologyCycleType", facts.cycleType());
        context.put("orderedPhenologyStages", facts.orderedStageNames());
        context.put("stageMismatch", facts.stageMismatch());
        context.put("factSources", facts.sources());
        context.put("fieldFactsStatus", facts.status());
        context.put("knowledgeStatus", knowledge.status());
        context.put("knowledge", knowledge.items());
        context.put("maxOutputTokens", Math.max(256, copilotMaxOutputTokens));
        Map<String, Object> source = answerSource(routed, facts, knowledge, request.getMessage());
        context.put("answerSource", source.get("layer"));
        context.put("answerSourceWarning", source.get("warning"));
        context.put("allowUngrounded", allowUngrounded);
        context.put("modelPolicy", allowUngrounded ? "allow_ungrounded" : "grounded_only");
        boolean privateFactQuestion = asksPrivateFacts(request.getMessage());
        String sourceInstruction = privateFactQuestion
                ? "这是涉及用户地块、数值、历史或检测的私有事实问题；缺少对应私有事实时绝不编造，只能明确指出缺口；若补充一般说明，必须标明‘以下为通用建议，未结合你的地块数据，仅供参考’。"
                : allowUngrounded
                ? "若没有私有地块事实或知识库命中，允许使用模型通用农业知识，但必须在回答开头明确写‘以下为通用建议，未结合你的地块数据，仅供参考’。"
                : "只使用当前地块事实或知识库依据；不能把模型记忆当作事实。";
        String prompt = "用户问题：" + safe(request.getMessage())
                + "\n当前上下文：" + context
                + "\n最近对话（仅用于承接指代，不得覆盖当前页面事实）：" + recentConversation(request, userId)
                + "\n农业知识依据：" + knowledge.items()
                + "\n知识库状态：" + knowledge.status()
                + "\n物候事实来源：" + facts.sources()
                + "\n答案来源策略：优先使用当前地块私有事实，其次使用按作物过滤的公共农业知识，再按开关决定是否使用模型通用知识。当前答案层为“" + source.get("layer") + "”。"
                + "\n事实优先级规则：当用户询问‘现在/当前/目前’的物候期、生育阶段或该做什么时，优先使用【当前日期】和【当前物候期】直接给出唯一结论，不要罗列全部物候期；上一个/下一个物候期必须使用给定的相邻阶段字段，不要让模型自行接词；没有地块时可以使用明确作物的通用历，但必须说明是通用历推算；日期、作物和依据都不足时，明确指出缺口。"
                + "\n物候术语规则：物候期只能使用给定的有序阶段名；修剪、施肥、疏果、打药、清园等是农事操作，不是物候期；禁止自造阶段名，也禁止把其他作物的阶段套入当前作物。"
                + "\n" + sourceInstruction
                + "\n始终不要虚构地块温度、湿度、土壤、历史用药、诊断或产量；知识库不可用时不要跨作物猜测。请直接用中文回答，不要输出隐藏思维链或 <think> 标签。";
        StringBuilder answer = new StringBuilder();
        ModelCallResult result = modelGateway.stream(ModelCallRequest.builder()
                .requestId("copilot-chat-" + java.util.UUID.randomUUID())
                .taskType(AiTaskType.COPILOT)
                .systemPrompt("你是 FarMap 农业 Copilot，只输出用户可读的最终答复。")
                .userPrompt(prompt).structuredOutput(false).context(context).build(), delta -> {
                    answer.append(delta);
                    streamDelta(emitter, delta);
                });
        String finalAnswer = finalAnswer(result.getContent());
        if (answer.isEmpty()) streamDelta(emitter, finalAnswer);
        return finalAnswer;
    }

    private String deterministicPhenologyAnswer(String message, CopilotFacts facts) {
        String normalized = safe(message);
        boolean asksCurrent = normalized.contains("现在") || normalized.contains("当前") || normalized.contains("目前");
        boolean asksPhenology = normalized.contains("物候期") || normalized.contains("生育期") || normalized.contains("生长阶段")
                || normalized.contains("什么时期") || normalized.contains("什么阶段") || normalized.contains("哪一期") || normalized.contains("哪一个阶段");
        boolean asksNext = normalized.contains("下一个") || normalized.contains("下一阶段") || normalized.contains("下一期") || normalized.contains("之后") || normalized.contains("再下一个");
        boolean asksPrevious = normalized.contains("上一个") || normalized.contains("上一阶段") || normalized.contains("上一期") || normalized.contains("之前");
        boolean asksTemporal = asksNext || asksPrevious;
        if ((!asksCurrent && !asksTemporal) || (!asksPhenology && !asksTemporal)) return null;
        if (facts == null || facts.growthStageLabel() == null || facts.cropLabel() == null) {
            String status = facts == null ? "UNAVAILABLE" : facts.status();
            String reason = "UNKNOWN_CROP".equals(status) ? "当前地块的作物暂不在支持目录中" : "当前未绑定可用地块";
            return reason + "；今天各支持作物按通用历推算为：" + publicPhenologySummary()
                    + "。如需唯一结论，请在问题中明确作物。";
        }
        String crop = facts.cropLabel() == null ? "当前作物" : facts.cropLabel();
        if (asksTemporal) {
            boolean next = asksNext && !asksPrevious;
            int distance = normalized.contains("再下一个") ? 2 : 1;
            String target = next ? stageAt(facts, distance) : stageAt(facts, -1);
            if (target == null) {
                if (next && facts.cycleEnded()) return temporalPrefix(facts) + crop + "当前为" + facts.growthStageLabel() + "；本茬已结束，没有下一物候期。";
                return temporalPrefix(facts) + crop + "当前为" + facts.growthStageLabel() + "，暂未配置可确定的" + (next ? "下一" : "上一") + "物候期。";
            }
            String relation = next ? (distance == 2 ? "再下一物候期" : "下一物候期") : "上一物候期";
            String days = next && distance == 1 && facts.daysUntilNext() != null ? "，按历法约" + facts.daysUntilNext() + "天后进入" : "";
            return temporalPrefix(facts) + crop + "当前处于" + facts.growthStageLabel() + "；" + relation + "是" + target + days + "。";
        }
        String source = facts.stageMismatch()
                ? "地块登记为" + facts.growthStageLabel() + "，但按服务器日期推算为" + facts.dateDerivedGrowthStageLabel() + "；当前先以登记值为准，请确认。"
                : facts.registeredGrowthStage() == null ? "根据服务器日期推算，" : "根据当前地块的服务端登记事实，";
        if ("PUBLIC_GENERIC".equals(facts.status())) source = "按服务器日期和" + crop + "通用物候历推算，";
        return ("PUBLIC_GENERIC".equals(facts.status()) ? "未使用地块实时数据，" : "截至" + facts.currentDate() + "，") + source + crop + "目前处于" + facts.growthStageLabel() + ("PUBLIC_GENERIC".equals(facts.status()) ? "。该结论不是你的地块实时事实。" : "。");
    }

    private String temporalPrefix(CopilotFacts facts) {
        return "PUBLIC_GENERIC".equals(facts.status())
                ? "未使用地块实时数据，按" + facts.cropLabel() + "通用物候历和服务器日期（" + facts.currentDate() + "）推算，"
                : "截至" + facts.currentDate() + "，";
    }

    private String publicPhenologySummary() {
        LocalDate date = LocalDate.now(COPILOT_ZONE);
        StringBuilder summary = new StringBuilder();
        for (var crop : cropResolver.supportedCrops()) {
            PhenologyResult result = phenologyService.calculate(crop.code(), date, null);
            if (summary.length() > 0) summary.append("；");
            summary.append(crop.name()).append("：").append(result.currentName() == null ? "暂无已配置阶段" : result.currentName());
        }
        return summary.toString();
    }

    private String stageAt(CopilotFacts facts, int offset) {
        if (facts.orderedStageNames() == null || facts.orderedStageNames().isEmpty() || facts.growthStageLabel() == null) return null;
        int current = facts.orderedStageNames().indexOf(facts.growthStageLabel());
        if (current < 0) return null;
        int target = current + offset;
        if ("deciduous_annual_cycle".equalsIgnoreCase(facts.cycleType()) || "evergreen_annual_cycle".equalsIgnoreCase(facts.cycleType())) {
            int size = facts.orderedStageNames().size();
            target = ((target % size) + size) % size;
        }
        return target < 0 || target >= facts.orderedStageNames().size() ? null : facts.orderedStageNames().get(target);
    }

    private String mapText(Map<String, Object> values, String key) {
        if (values == null) return null;
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private void putFieldFacts(Map<String, Object> context, FieldFacts facts) {
        if (facts == null) return;
        context.put("currentDate", facts.currentDate());
        context.put("currentDateSource", "SERVER_ASIA_SHANGHAI");
        context.put("crop", facts.cropCode());
        context.put("cropLabel", facts.cropLabel());
        context.put("growthStage", facts.currentGrowthStage());
        context.put("growthStageLabel", facts.currentGrowthStageLabel());
        context.put("dateDerivedGrowthStage", facts.dateDerivedGrowthStage());
        context.put("dateDerivedGrowthStageLabel", facts.dateDerivedGrowthStageLabel());
        context.put("stageMismatch", facts.stageMismatch());
        context.put("registeredGrowthStage", facts.registeredGrowthStage());
        context.put("registeredGrowthStageLabel", facts.registeredGrowthStageLabel());
        context.put("previousGrowthStage", facts.previousGrowthStage());
        context.put("previousGrowthStageLabel", facts.previousGrowthStageLabel());
        context.put("nextGrowthStage", facts.nextGrowthStage());
        context.put("nextGrowthStageLabel", facts.nextGrowthStageLabel());
        context.put("daysUntilNextPhenologyStage", facts.daysUntilNext());
        context.put("phenologyCycleType", facts.phenologyCycleType());
        context.put("orderedPhenologyStages", facts.orderedGrowthStages());
        context.put("phenologyCycleEnded", facts.cycleEnded());
        context.put("fieldFactsStatus", facts.status());
        context.put("factSources", facts.sources());
    }

    private void putCopilotFacts(Map<String, Object> context, CopilotFacts facts) {
        if (facts == null) return;
        context.put("currentDate", facts.currentDate());
        context.put("currentDateSource", "SERVER_ASIA_SHANGHAI");
        context.put("crop", facts.crop());
        context.put("cropLabel", facts.cropLabel());
        context.put("growthStage", facts.growthStage());
        context.put("growthStageLabel", facts.growthStageLabel());
        context.put("currentGrowthStage", facts.growthStage());
        context.put("currentGrowthStageLabel", facts.growthStageLabel());
        context.put("dateDerivedGrowthStage", facts.dateDerivedGrowthStage());
        context.put("dateDerivedGrowthStageLabel", facts.dateDerivedGrowthStageLabel());
        context.put("stageMismatch", facts.stageMismatch());
        context.put("registeredGrowthStage", facts.registeredGrowthStage());
        context.put("registeredGrowthStageLabel", facts.registeredGrowthStageLabel());
        context.put("previousGrowthStage", facts.previousGrowthStage());
        context.put("previousGrowthStageLabel", facts.previousGrowthStageLabel());
        context.put("nextGrowthStage", facts.nextGrowthStage());
        context.put("nextGrowthStageLabel", facts.nextGrowthStageLabel());
        context.put("daysUntilNextPhenologyStage", facts.daysUntilNext());
        context.put("phenologyCycleType", facts.cycleType());
        context.put("orderedPhenologyStages", facts.orderedStageNames());
        context.put("phenologyCycleEnded", facts.cycleEnded());
        context.put("fieldFactsStatus", facts.status());
        context.put("factSources", facts.sources());
    }

    private Map<String, Object> answerSource(CopilotRouteResult routed, CopilotFacts facts, KnowledgeBundle knowledge, String message) {
        boolean fieldBound = routed != null && routed.getResolvedContext() != null
                && routed.getResolvedContext().getFieldId() != null
                && !routed.getResolvedContext().getFieldId().isBlank();
        boolean privateFacts = fieldBound && facts != null && "READY".equals(facts.status());
        boolean publicKnowledge = knowledge != null && knowledge.items() != null && !knowledge.items().isEmpty();
        boolean publicPhenology = facts != null && "PUBLIC_GENERIC".equals(facts.status())
                && deterministicPhenologyAnswer(message, facts) != null;
        Map<String, Object> source = new LinkedHashMap<>();
        if (privateFacts) {
            source.put("layer", "L1_FIELD_PRIVATE");
            source.put("grounded", true);
            source.put("warning", null);
        } else if (publicKnowledge || publicPhenology) {
            source.put("layer", "L2_PUBLIC_AGRICULTURE");
            source.put("grounded", true);
            source.put("warning", "未结合你的地块实时数据");
        } else if (allowUngrounded) {
            source.put("layer", "L3_MODEL_GENERAL");
            source.put("grounded", false);
            source.put("warning", "以下为通用建议，未结合你的地块数据，仅供参考");
        } else {
            source.put("layer", "BLOCKED_NO_GROUNDING");
            source.put("grounded", false);
            source.put("warning", "通用模型兜底已关闭");
        }
        source.put("fieldBound", fieldBound);
        source.put("knowledgeCount", publicKnowledge ? knowledge.items().size() : 0);
        return source;
    }

    private boolean asksPrivateFacts(String message) {
        String value = safe(message);
        boolean privateAnchor = contains(value, "我的", "我这", "这块", "这片", "该地块", "当前",
                "本地块", "传感器", "现场", "本农场", "当前农场");
        boolean privateFact = contains(value, "地块", "农场", "数值", "历史", "检测", "诊断", "产量",
                "土壤", "温度", "湿度", "用药", "打过药", "记录", "风险");
        boolean explicitPrivateHistory = contains(value, "上周", "最近一次", "打过药", "检测结果", "诊断结果",
                "我的数值", "我的历史");
        // A domain noun alone (for example, “柑橘花期适宜温度”) is general
        // agriculture knowledge. Treat it as private only when the wording
        // also points at this user's field or contains an explicit history
        // query.
        return explicitPrivateHistory || privateAnchor && privateFact;
    }

    private void streamDelta(SseEmitter emitter, String content) {
        if (content == null || content.isEmpty()) return;
        int chunkSize = 24;
        for (int offset = 0; offset < content.length(); offset += chunkSize) {
            String delta = content.substring(offset, Math.min(content.length(), offset + chunkSize));
            try { send(emitter, "content.delta", Map.of("delta", delta)); }
            catch (IOException error) { throw new ChatDeliveryException(error); }
        }
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private void sendDone(SseEmitter emitter, String status) throws IOException {
        send(emitter, "done", Map.of("status", status));
        emitter.complete();
    }

    private void appendChatAnswer(CopilotChatRequest request, CopilotRouteResult routed, String answer, Long userId) {
        if (request.getConversationId() == null || conversations == null) return;
        try { conversations.appendAssistantAnswer(request.getConversationId(), userId, routed.getIntent(), routed.getTraceId(), answer); }
        catch (RuntimeException ignored) { /* optional conversation persistence must not break the answer */ }
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }

    private List<String> trustedImageUrls(List<String> references) {
        if (references == null || references.isEmpty()) return List.of();
        URI upload = safeUri(uploadBaseUrl);
        URI cos = cosReferenceBaseUrl();
        List<String> trusted = new ArrayList<>();
        for (String value : references) {
            if (value == null || value.isBlank()) continue;
            try {
                URI reference = URI.create(value);
                if ((upload != null && referenceUnder(upload, upload.getPath(), reference))
                        || (cos != null && referenceUnder(cos, "/", reference))) trusted.add(value);
            } catch (RuntimeException ignored) { /* untrusted image references are ignored */ }
        }
        return trusted;
    }

    private URI cosReferenceBaseUrl() {
        if (cosPublicBaseUrl != null && !cosPublicBaseUrl.isBlank()) return safeUri(cosPublicBaseUrl);
        if (cosBucket == null || cosBucket.isBlank() || cosRegion == null || cosRegion.isBlank()) return null;
        return safeUri("https://" + cosBucket + ".cos." + cosRegion + ".myqcloud.com");
    }

    private URI safeUri(String value) {
        try { return value == null || value.isBlank() ? null : URI.create(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private boolean referenceUnder(URI base, String basePath, URI reference) {
        if (base == null || reference == null || !("http".equalsIgnoreCase(reference.getScheme()) || "https".equalsIgnoreCase(reference.getScheme()))) return false;
        String expectedPath = basePath == null || basePath.isBlank() ? "/" : basePath.replaceAll("/$", "");
        String actualPath = reference.getPath();
        return sameHost(base, reference) && actualPath != null
                && ("/".equals(expectedPath) || actualPath.equals(expectedPath) || actualPath.startsWith(expectedPath + "/"));
    }

    private boolean sameHost(URI expected, URI actual) {
        return expected.getHost() != null && actual.getHost() != null
                && expected.getHost().equalsIgnoreCase(actual.getHost()) && expected.getPort() == actual.getPort();
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private record CopilotFacts(String currentDate, String crop, String cropLabel, String growthStage, String growthStageLabel,
                                String dateDerivedGrowthStage, String dateDerivedGrowthStageLabel, String registeredGrowthStage,
                                String registeredGrowthStageLabel, String previousGrowthStage, String previousGrowthStageLabel,
                                String nextGrowthStage, String nextGrowthStageLabel, Long daysUntilNext,
                                List<String> orderedStageNames, String cycleType, boolean stageMismatch, boolean cycleEnded,
                                String status, Map<String, String> sources) {
        private static CopilotFacts from(FieldFacts facts) {
            return new CopilotFacts(facts.currentDate(), facts.cropCode(), facts.cropLabel(), facts.currentGrowthStage(), facts.currentGrowthStageLabel(),
                    facts.dateDerivedGrowthStage(), facts.dateDerivedGrowthStageLabel(), facts.registeredGrowthStage(), facts.registeredGrowthStageLabel(),
                    facts.previousGrowthStage(), facts.previousGrowthStageLabel(), facts.nextGrowthStage(), facts.nextGrowthStageLabel(), facts.daysUntilNext(),
                    facts.orderedGrowthStages(), facts.phenologyCycleType(), facts.stageMismatch(), facts.cycleEnded(), facts.status(), facts.sources());
        }

        private static CopilotFacts from(String currentDate, CropResolver.ResolvedCrop crop, PhenologyResult result,
                                         List<String> orderedStageNames, String cycleType, Map<String, String> sources) {
            return from(currentDate, crop, result, orderedStageNames, cycleType, sources, "READY");
        }

        private static CopilotFacts from(String currentDate, CropResolver.ResolvedCrop crop, PhenologyResult result,
                                         List<String> orderedStageNames, String cycleType, Map<String, String> sources,
                                         String status) {
            return new CopilotFacts(currentDate, crop.code(), crop.name(), result.currentCode(), result.currentName(), result.dateDerivedCode(), result.dateDerivedName(),
                    result.registeredStage() == null ? null : result.registeredStage().code(), result.registeredStage() == null ? null : result.registeredStage().name(),
                    result.previousCode(), result.previousName(), result.nextCode(), result.nextName(), result.daysUntilNext(),
                    orderedStageNames == null ? List.of() : orderedStageNames, cycleType, result.mismatch(), result.cycleEnded(), status, sources);
        }

        private boolean ready() { return "READY".equals(status); }

        private static CopilotFacts empty(String currentDate, String status) {
            return new CopilotFacts(currentDate, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    List.of(), null, false, false, status, Map.of("serverDate", "SERVER_ASIA_SHANGHAI"));
        }
    }

    private record KnowledgeBundle(String status, List<Map<String, Object>> items) {}

    private static final class ChatDeliveryException extends RuntimeException {
        private ChatDeliveryException(IOException cause) { super(cause); }
    }

    /**
     * Produces a concise, evidence-grounded Copilot summary after the real
     * read-only command has completed. This endpoint intentionally returns a
     * safe answer and model metadata, never private chain-of-thought.
     */
    @PostMapping("/answer")
    public Result<Map<String, Object>> answer(@RequestBody AnswerRequest request) {
        Long userId = requireUser();
        if (modelGateway == null) {
            throw new CopilotConversationException("COPILOT_MODEL_UNAVAILABLE", "Copilot 生成模型暂不可用。", 503);
        }
        String message = request == null || request.message == null ? "" : request.message.trim();
        String intent = request == null || request.intent == null ? "UNKNOWN" : request.intent;
        Map<String, Object> context = request == null || request.context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.context);
        if (fieldFactsService != null && context.get("fieldId") != null) {
            FieldFacts facts = fieldFactsService.forField(String.valueOf(context.get("fieldId")), userId);
            putFieldFacts(context, facts);
        }
        if (!"READY".equalsIgnoreCase(String.valueOf(context.getOrDefault("fieldFactsStatus", "")))) {
            CopilotFacts publicFacts = publicFactsFromQuery(message, LocalDate.now(COPILOT_ZONE), LocalDate.now(COPILOT_ZONE).format(COPILOT_DATE_FORMAT));
            if (publicFacts != null) putCopilotFacts(context, publicFacts);
        }
        Object evidence = request == null ? null : request.evidence;
        boolean generalQuestion = "GENERAL_QA".equalsIgnoreCase(intent);
        boolean privateFactsAvailable = context.get("fieldId") != null
                && "READY".equalsIgnoreCase(String.valueOf(context.getOrDefault("fieldFactsStatus", "")));
        boolean publicFactsAvailable = "PUBLIC_GENERIC".equalsIgnoreCase(String.valueOf(context.getOrDefault("fieldFactsStatus", "")));
        boolean evidenceAvailable = hasEvidence(evidence);
        String deterministicAnswer = deterministicPhenologyAnswer(message, factsFromContext(context));
        if (deterministicAnswer != null) {
            Map<String, Object> deterministic = new LinkedHashMap<>();
            deterministic.put("answer", deterministicAnswer);
            deterministic.put("model", null);
            deterministic.put("latencyMs", 0L);
            deterministic.put("grounded", privateFactsAvailable || publicFactsAvailable || evidenceAvailable);
            deterministic.put("available", true);
            deterministic.put("answerSource", privateFactsAvailable ? "L1_FIELD_PRIVATE" : publicFactsAvailable || evidenceAvailable ? "L2_PUBLIC_AGRICULTURE" : "L3_MODEL_GENERAL");
            deterministic.put("evidenceCount", evidenceAvailable ? 1 : 0);
            appendAnswer(request, intent, deterministicAnswer, userId);
            return Result.success(deterministic);
        }
        if (!allowUngrounded && !privateFactsAvailable && !evidenceAvailable) {
            String blockedAnswer = "当前没有可验证的地块或知识依据；通用模型兜底已关闭，暂不生成农业结论。请补充地块上下文或知识依据。";
            Map<String, Object> blocked = new LinkedHashMap<>();
            blocked.put("answer", blockedAnswer);
            blocked.put("model", null);
            blocked.put("latencyMs", null);
            blocked.put("grounded", false);
            blocked.put("available", true);
            blocked.put("answerSource", "BLOCKED_NO_GROUNDING");
            blocked.put("evidenceCount", 0);
            appendAnswer(request, intent, blockedAnswer, userId);
            return Result.success(blocked);
        }
        String evidenceText = String.valueOf(evidence);
        int evidenceBudget = generalQuestion ? 6000 : 12000;
        if (evidenceText.length() > evidenceBudget) evidenceText = evidenceText.substring(0, evidenceBudget);
        String recentConversation = generalQuestion ? recentConversation(request, userId) : "";
        String sourceInstruction = privateFactsAvailable
                ? "优先使用当前地块的已验证私有事实。"
                : evidenceAvailable
                ? "优先使用提供的公共知识或工具证据，不要把它们伪装成当前地块实时数据。"
                : "以下为通用建议，未结合你的地块数据，仅供参考。不得虚构任何地块数值、历史或检测结果。";
        String userPrompt = generalQuestion
                ? "用户问题：" + message
                    + "\n当前页面上下文：" + context
                    + "\n最近对话（仅用于承接指代，不得覆盖当前页面事实）：" + recentConversation
                    + "\n上下文快照：" + evidenceText
                    + "\n请直接用中文回答。能由页面上下文回答时优先使用上下文；一般农业知识问题可以使用模型知识，但必须标明是通用知识，不能把它伪装成当前农场的实时数据。缺少关键事实时只说明缺少哪一项，不要机械要求用户重复已经在上下文中的信息。"
                    + "\n答案来源规则：" + sourceInstruction
                : "用户问题：" + message
                    + "\n意图：" + intent
                    + "\n当前上下文：" + context
                    + "\n真实工具返回的 Evidence / 数据：" + evidenceText
                    + "\n请给出 2-4 句中文结论，明确区分事实与建议；只能引用上面的数据，不要补造数值。"
                    + "\n答案来源规则：" + sourceInstruction;
        Map<String, Object> modelContext = new LinkedHashMap<>(context);
        if (generalQuestion) modelContext.put("modelPolicy", "force_fast");
        modelContext.put("maxOutputTokens", Math.max(256, copilotMaxOutputTokens));
        modelContext.put("allowUngrounded", allowUngrounded);
        modelContext.put("answerSource", privateFactsAvailable ? "L1_FIELD_PRIVATE" : evidenceAvailable ? "L2_PUBLIC_AGRICULTURE" : "L3_MODEL_GENERAL");
        ModelCallResult result;
        try {
            result = modelGateway.complete(ModelCallRequest.builder()
                    .requestId("copilot-answer-" + java.util.UUID.randomUUID())
                    .taskType(AiTaskType.COPILOT)
                    .systemPrompt(generalQuestion
                            ? "你是 FarMap 农业 Copilot。回答普通问答和当前页面上下文问题；简洁、直接，只输出最终答复，不输出隐藏思维链、分析草稿或 <think> 标签。复杂诊断应提示用户发起 AI 调查。"
                            : "你是 FarMap 农业 Copilot。你只做基于已返回证据的简洁总结，不展示或声称拥有隐藏思维链；如果证据不足，明确说需要补充数据。")
                    .userPrompt(userPrompt)
                    .structuredOutput(false)
                    .context(modelContext)
                    .build());
        } catch (ModelCallException exception) {
            // Never leave the command bar spinning or surface a generic 500.
            // The UI can render this as a provider-status card and retry after
            // the account/model entitlement is corrected.
            Map<String, Object> unavailable = new LinkedHashMap<>();
            unavailable.put("answer", "当前 Copilot 模型暂不可用，已完成上下文收集；请检查 Model Studio 模型权限后重试。" );
            unavailable.put("model", null);
            unavailable.put("latencyMs", null);
            unavailable.put("grounded", false);
            unavailable.put("available", false);
            unavailable.put("providerStatus", "UNAVAILABLE");
            unavailable.put("errorType", exception.getErrorType());
            unavailable.put("answerSource", privateFactsAvailable ? "L1_FIELD_PRIVATE" : evidenceAvailable ? "L2_PUBLIC_AGRICULTURE" : "L3_MODEL_GENERAL");
            unavailable.put("evidenceCount", evidenceAvailable ? 1 : 0);
            appendAnswer(request, intent, "当前 Copilot 模型暂不可用，已完成上下文收集；请稍后重试。", userId);
            return Result.success(unavailable);
        }
        String finalAnswer = groundedFinalAnswer(message, context, result.getContent());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", finalAnswer);
        response.put("model", result.getSelectedModel());
        response.put("latencyMs", result.getLatencyMs());
        response.put("grounded", privateFactsAvailable || evidenceAvailable);
        response.put("answerSource", privateFactsAvailable ? "L1_FIELD_PRIVATE" : evidenceAvailable ? "L2_PUBLIC_AGRICULTURE" : "L3_MODEL_GENERAL");
        response.put("modelTier", generalQuestion ? "FAST" : "STANDARD");
        response.put("evidenceCount", evidenceAvailable ? 1 : 0);
        appendAnswer(request, intent, finalAnswer, userId);
        return Result.success(response);
    }

    private String recentConversation(AnswerRequest request, Long userId) {
        if (request == null || request.conversationId == null || request.conversationId.isBlank() || conversations == null) return "无";
        try {
            return formatRecentConversation(conversations.view(request.conversationId, userId));
        } catch (RuntimeException ignored) {
            return "无";
        }
    }

    private String recentConversation(CopilotChatRequest request, Long userId) {
        if (request == null || request.getConversationId() == null || request.getConversationId().isBlank() || conversations == null) return "无";
        try {
            return formatRecentConversation(conversations.view(request.getConversationId(), userId));
        } catch (RuntimeException ignored) {
            return "无";
        }
    }

    /** Keeps the latest ten turns while reserving a small prefix for the server-owned summary. */
    private String formatRecentConversation(CopilotConversationView view) {
        if (view == null) return "无";
        List<com.mei.zhgy.service.copilot.CopilotConversationTurn> turns = view.getRecentTurns() == null
                ? List.of() : view.getRecentTurns();
        String joined = turns.stream()
                .skip(Math.max(0, turns.size() - 10L))
                .map(turn -> turn.getRole() + "：" + turn.getMessage())
                .reduce((left, right) -> left + "\n" + right).orElse("无");
        String summary = view.getSummary();
        boolean hasSummary = summary != null && !summary.isBlank() && !"{}".equals(summary.trim());
        if (!hasSummary) return boundTail(joined, 4000);
        return "会话摘要：" + bound(summary, 800) + "\n最近对话：" + boundTail(joined, 3200);
    }

    private String bound(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String boundTail(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private void appendAnswer(AnswerRequest request, String intent, String answer, Long userId) {
        if (request == null || request.conversationId == null || conversations == null) return;
        CopilotIntent parsed;
        try { parsed = CopilotIntent.valueOf(intent); } catch (RuntimeException ignored) { parsed = null; }
        try { conversations.appendAssistantAnswer(request.conversationId, userId, parsed, request.traceId, answer); }
        catch (RuntimeException ignored) { /* answering remains available even if optional history persistence is degraded */ }
    }

    private String finalAnswer(String content) {
        if (content == null || content.isBlank()) return "当前模型没有返回有效答复，请重试。";
        String cleaned = content.replaceAll("(?is)<think>.*?</think>", "")
                .replaceAll("(?is)^.*?</think>", "")
                .trim();
        return cleaned.isBlank() ? "回答已完成，但模型未返回可展示的最终结论，请重试。" : cleaned;
    }

    private boolean hasEvidence(Object evidence) {
        if (evidence == null) return false;
        if (evidence instanceof java.util.Collection<?> values) return !values.isEmpty();
        if (evidence instanceof Map<?, ?> values) return !values.isEmpty();
        String value = String.valueOf(evidence).trim();
        return !value.isBlank() && !"null".equalsIgnoreCase(value) && !"[]".equals(value) && !"{}".equals(value);
    }

    private CopilotFacts factsFromContext(Map<String, Object> context) {
        if (context == null) return CopilotFacts.empty(null, "UNAVAILABLE");
        List<String> ordered = new ArrayList<>();
        Object orderedValue = context.get("orderedPhenologyStages");
        if (orderedValue instanceof Iterable<?> values) values.forEach(value -> { if (value != null) ordered.add(String.valueOf(value)); });
        Map<String, String> sources = new LinkedHashMap<>();
        Object sourceValue = context.get("factSources");
        if (sourceValue instanceof Map<?, ?> values) values.forEach((key, value) -> { if (key != null && value != null) sources.put(String.valueOf(key), String.valueOf(value)); });
        String status = contextString(context, "fieldFactsStatus");
        if (status == null) status = contextString(context, "fieldId") == null ? "PUBLIC_GENERIC" : "READY";
        return new CopilotFacts(
                contextString(context, "currentDate"), contextString(context, "crop"), contextString(context, "cropLabel"),
                contextString(context, "currentGrowthStage", "growthStage"), contextString(context, "currentGrowthStageLabel", "growthStageLabel"),
                contextString(context, "dateDerivedGrowthStage"), contextString(context, "dateDerivedGrowthStageLabel"),
                contextString(context, "registeredGrowthStage"), contextString(context, "registeredGrowthStageLabel"),
                contextString(context, "previousGrowthStage"), contextString(context, "previousGrowthStageLabel"),
                contextString(context, "nextGrowthStage"), contextString(context, "nextGrowthStageLabel"), number(context.get("daysUntilNextPhenologyStage")),
                ordered, contextString(context, "phenologyCycleType"), bool(context.get("stageMismatch")), bool(context.get("phenologyCycleEnded")),
                status, sources);
    }

    private String contextString(Map<String, Object> context, String... keys) {
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private Long number(Object value) {
        if (value instanceof Number item) return item.longValue();
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
    }

    private boolean bool(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)); }

    private String groundedFinalAnswer(String message, Map<String, Object> context, String modelAnswer) {
        String deterministic = deterministicPhenologyAnswer(message, factsFromContext(context));
        if (deterministic != null) return deterministic;
        return finalAnswer(modelAnswer);
    }

    public static class AnswerRequest {
        public String message;
        public String intent;
        public String conversationId;
        public String traceId;
        public Map<String, Object> context = new LinkedHashMap<>();
        public Object evidence;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CopilotChatRequest extends CopilotRouteRequest {
        private List<String> imageUrls = new ArrayList<>();
    }

    @GetMapping("/memories")
    public java.util.List<com.mei.zhgy.service.copilot.CopilotMemoryView> listMemories(@RequestParam(required = false) String farmId, @RequestParam(required = false) String fieldId) {
        if (memories == null) throw new CopilotConversationException("MEMORY_UNAVAILABLE", "memory service unavailable", 503);
        return memories.views(requireUser(), farmId, fieldId);
    }

    @PostMapping("/memories/{memoryId}/revoke")
    public com.mei.zhgy.service.copilot.CopilotMemoryView revokeMemory(@PathVariable String memoryId) {
        if (memories == null) throw new CopilotConversationException("MEMORY_UNAVAILABLE", "memory service unavailable", 503);
        return memories.revokeById(memoryId, requireUser());
    }

    private Long requireUser() { Long userId = BaseContext.getCurrentId(); if (userId == null) throw AgentAccessException.unauthenticated(); return userId; }
    private void requireConversationService() { if (conversations == null) throw new CopilotConversationException("CONVERSATION_UNAVAILABLE", "conversation service unavailable", 503); }

    @ExceptionHandler(AgentAccessException.class)
    public ResponseEntity<Map<String, Object>> accessError(AgentAccessException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode());
        body.put("message", error.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.valueOf(error.getHttpStatus())).body(body);
    }

    @ExceptionHandler(CopilotConversationException.class)
    public ResponseEntity<Map<String, Object>> conversationError(CopilotConversationException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode()); body.put("message", error.getMessage()); body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(error.getHttpStatus()).body(body);
    }

    @ExceptionHandler(FieldAccessException.class)
    public ResponseEntity<Map<String, Object>> fieldError(FieldAccessException error) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("code", error.getCode()); body.put("message", error.getMessage()); body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(error.getHttpStatus()).body(body);
    }
}

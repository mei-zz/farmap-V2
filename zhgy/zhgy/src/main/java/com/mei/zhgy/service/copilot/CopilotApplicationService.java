package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.copilot.CopilotSecurityGuard.ToolScope;
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
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.agent.AgentRun;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;
import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.reference.ReferenceImageAttacher;
import com.mei.zhgy.service.reference.ReferenceImageLookup;
import com.mei.zhgy.result.Result;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.mei.zhgy.service.copilot.CopilotToolArguments.*;

/** Authenticated application orchestration shared by the HTTP entry points. */
@org.springframework.stereotype.Service
public class CopilotApplicationService {
    private static final Logger log = LoggerFactory.getLogger(CopilotApplicationService.class);
    private static final ZoneId COPILOT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter COPILOT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE);
    private final CopilotRouteService routes;
    private final com.mei.zhgy.service.copilot.CopilotConversationService conversations;
    private final CopilotLongTermMemoryService memories;
    private final ModelGateway modelGateway;
    private final KnowledgeEvidenceRetriever knowledgeRetriever;
    private final DiagnosisJobService diagnosisJobs;
    private final FarmFieldService farmFieldService;
    private final CopilotSecurityGuard securityGuard;
    private final AgentRuntime agentRuntime;
    private final FieldFactsService fieldFactsService;
    private final CopilotFieldFactsResolver fieldContext;
    private final CopilotPhenologyGuidance phenologyGuidance;
    private final CropResolver cropResolver;
    private final PhenologyService phenologyService;
    private final ReferenceImageAttacher referenceImageAttacher;
    private PiAgentRuntime piRuntime;
    private final AgriculturalAgentHarness harness = new AgriculturalAgentHarness();
    private final CopilotContinuationGrounding continuationGrounding = new CopilotContinuationGrounding();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgriculturalToolRegistry agriculturalTools;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgriculturalWeatherAdvisor agriculturalWeather;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlumAgronomyRuleEngine agronomyRules;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlumAgronomyEvidenceCatalog agronomyEvidence;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mei.zhgy.service.knowledge.AgriculturalStructuredKnowledgeService structuredAgronomy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService diagnosticRecords;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CopilotMemoryProposalService memoryProposals;
    private com.mei.zhgy.service.CurrentWeatherService currentWeatherService;
    private com.mei.zhgy.service.maturity.FarmMaturityService maturityService;
    private com.mei.zhgy.service.operations.OperationsTaskService workspaceTasks;
    private com.mei.zhgy.service.operations.OperationsTaskViewService workspaceTaskViews;
    private com.mei.zhgy.service.AgriculturalContextService monthlyGuidanceService;
    private com.mei.zhgy.service.camera.FarmCameraService maturityCameras;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMaturityCameras(com.mei.zhgy.service.camera.FarmCameraService service) { this.maturityCameras = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCurrentWeatherService(com.mei.zhgy.service.CurrentWeatherService service) { this.currentWeatherService = service; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMaturityService(com.mei.zhgy.service.maturity.FarmMaturityService service) { this.maturityService = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkspaceTasks(com.mei.zhgy.service.operations.OperationsTaskService service) { this.workspaceTasks = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkspaceTaskViews(com.mei.zhgy.service.operations.OperationsTaskViewService service) { this.workspaceTaskViews = service; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMonthlyGuidanceService(com.mei.zhgy.service.AgriculturalContextService service) { this.monthlyGuidanceService = service; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setPiRuntime(PiAgentRuntime piRuntime) {
        this.piRuntime = piRuntime;
    }
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
    private final Map<CopilotChatRequest, UiStream> requestUiStreams = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private final Map<SseEmitter, UiStream> uiStreams = new java.util.concurrent.ConcurrentHashMap<>();
    private final Executor chatExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "farmap-copilot-chat");
        thread.setDaemon(true);
        return thread;
    });

    public CopilotApplicationService(CopilotRouteService routes) { this(routes, null, null, null, null, null, null, null, null, null, null, null); }

    public CopilotApplicationService(CopilotRouteService routes, com.mei.zhgy.service.copilot.CopilotConversationService conversations) {
        this(routes, conversations, null, null, null, null, null, null, null, null, null, null);
    }

    public CopilotApplicationService(CopilotRouteService routes, com.mei.zhgy.service.copilot.CopilotConversationService conversations, CopilotLongTermMemoryService memories) {
        this(routes, conversations, memories, null, null, null, null, null, null, null, null, null);
    }

    public CopilotApplicationService(CopilotRouteService routes,
                             com.mei.zhgy.service.copilot.CopilotConversationService conversations,
                             CopilotLongTermMemoryService memories,
                             ModelGateway modelGateway) {
        this(routes, conversations, memories, modelGateway, null, null, null, null, null, null, null, null);
    }

    public CopilotApplicationService(CopilotRouteService routes,
                             com.mei.zhgy.service.copilot.CopilotConversationService conversations,
                             CopilotLongTermMemoryService memories,
                             ModelGateway modelGateway,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) KnowledgeEvidenceRetriever knowledgeRetriever) {
        this(routes, conversations, memories, modelGateway, knowledgeRetriever, null, null, null, null, null, null, null);
    }

    /** Compatibility overload for callers that do not yet provide the atlas attacher. */
    public CopilotApplicationService(CopilotRouteService routes,
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
    public CopilotApplicationService(CopilotRouteService routes,
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
        this.securityGuard = new CopilotSecurityGuard(farmFieldService);
        this.agentRuntime = agentRuntime;
        this.fieldFactsService = fieldFactsService;
        this.cropResolver = cropResolver == null ? new CropResolver() : cropResolver;
        this.phenologyService = phenologyService == null ? new PhenologyService(this.cropResolver) : phenologyService;
        this.referenceImageAttacher = referenceImageAttacher;
        this.fieldContext = new CopilotFieldFactsResolver(farmFieldService, fieldFactsService, this.cropResolver,
                this.phenologyService, this::orchardProfile, this::recentConversation);
        this.phenologyGuidance = new CopilotPhenologyGuidance(this.cropResolver, this.phenologyService);
    }

    public com.mei.zhgy.service.copilot.CopilotConversationView createConversation(Long userId) {
        requireConversationService();
        var created = conversations.create(userId);
        return conversations.view(created.getConversationId(), userId);
    }

    public com.mei.zhgy.service.copilot.CopilotConversationView getConversation(String conversationId, Long userId) {
        requireConversationService();
        return conversations.view(conversationId, userId);
    }

    public List<com.mei.zhgy.service.copilot.CopilotConversationListItem> listConversations(
            int limit, Long userId) {
        requireConversationService();
        return conversations.list(userId, limit);
    }

    public com.mei.zhgy.service.copilot.CopilotConversationView resumeConversation(String conversationId, Long userId) {
        requireConversationService();
        return conversations.resume(conversationId, userId);
    }

    public CopilotRouteResult route(CopilotRouteRequest request, Long userId) {
        return routes.route(request, userId);
    }

    public void controlAgent(String conversationId, PiControl command, Long userId, SseEmitter emitter) {
        requireConversationService();
        CopilotConversationView view = conversations.runtimeView(conversationId, userId);
        if (!usesPi()) throw new CopilotConversationException("PI_AGENT_DISABLED", "当前未启用 Pi Agent。", 503);
        if (command == null || command.operation() == null
                || !List.of("abort", "continue", "steer", "followUp").contains(command.operation())
                || command.contextVersion() == null || command.contextVersion() != view.getContextVersion()
                || (("steer".equals(command.operation()) || "followUp".equals(command.operation()))
                && (safe(command.message()).isBlank() || command.message().length() > 4000))) {
            throw new CopilotConversationException("PI_AGENT_CONTROL_INVALID", "请使用当前会话版本和有效的 Agent 控制指令。", 400);
        }
        UiStream controlUi = new UiStream(); controlUi.conversationId = conversationId; controlUi.userId = userId;
        controlUi.telemetry.mode("PI_CONTROL");
        uiStreams.put(emitter, controlUi);
        String controlId = java.util.UUID.randomUUID().toString();
        boolean queuedInput = "steer".equals(command.operation()) || "followUp".equals(command.operation());
        if (queuedInput) conversations.recordActivity(conversationId, userId, Map.of("type", "input", "requestId", controlId,
                "operation", command.operation(), "message", command.message(), "status", "requested", "at", Instant.now().toString()));
        CompletableFuture.runAsync(() -> {
            try {
                CopilotConversationTurn userTurn = view.getRecentTurns().stream()
                        .filter(entry -> "USER".equalsIgnoreCase(entry.getRole())).reduce((first, next) -> next).orElse(null);
                var key = "continue".equals(command.operation()) && userTurn != null
                        ? continuationKey(view, userId, userTurn.getTurnId()) : null;
                PiTurn turn = new PiTurn(continuationGrounding.read(key));
                PiAgentRuntime.Result result = piRuntime.control(piSession(userId, conversationId), view.getContextVersion(),
                        command.operation(), command.message(), delta -> {
                            if (!delta.isEmpty()) controlUi.telemetry.token();
                            if (allowUngrounded) streamDelta(emitter, delta);
                        },
                        event -> streamPiEvent(emitter, event),
                        observation -> {
                            observePiTool(emitter, userTurn == null ? null : userTurn.getMessage(), turn, observation);
                            continuationGrounding.remember(key, turn.grounding());
                        });
                if ("completed".equals(result.status())) continuationGrounding.clear(key);
                else if ("aborted".equals(result.status())) continuationGrounding.remember(key, turn.grounding());
                if (queuedInput) conversations.recordActivity(conversationId, userId, Map.of("type", "input", "requestId", controlId,
                        "operation", command.operation(), "status", "delivered", "at", Instant.now().toString()));
                if ("continue".equals(command.operation()) && "completed".equals(result.status()) && !safe(result.text()).isBlank()) {
                    String answer = finalAnswer(result.text());
                    if (!allowUngrounded) {
                        if (!turn.grounded()) answer = "当前没有取得可验证的地块或农业知识依据。请重新发送需要查询的问题。";
                        streamDelta(emitter, answer);
                    }
                    emitPiSource(emitter, turn);
                    if (userTurn != null) conversations.appendAssistantAnswer(conversationId, userId,
                            userTurn.getIntent(), userTurn.getTraceId(), answer, responsePersistence(null, controlUi, userTurn.getTraceId()));
                }
                sendDone(emitter, "aborted".equals(result.status()) ? "ABORTED" : "COMPLETED");
            } catch (Exception error) {
                if (queuedInput) try { conversations.recordActivity(conversationId, userId, Map.of("type", "input", "requestId", controlId,
                        "operation", command.operation(), "status", "uncertain", "at", Instant.now().toString())); } catch (RuntimeException ignored) { }
                try {
                    send(emitter, "error", Map.of("message", "当前 Agent 会话不可用，请检查已保存的输入后再决定是否重新发送。", "code", "SESSION_RECOVERY_REQUIRED"));
                    sendDone(emitter, "FAILED");
                } catch (IOException deliveryFailure) {
                    emitter.completeWithError(deliveryFailure);
                }
            }
            finally { uiStreams.remove(emitter); }
        }, chatExecutor);
    }

    public record PiControl(String operation, Long contextVersion, String message) {}

    /**
     * Lightweight conversational path. It owns classification and knowledge
     * retrieval so ordinary questions do not wait for or create an Agent Run.
     * Heavy intents keep their diagnosis/command paths when fully resolved.
     * If a read capability lacks context or is unavailable, it falls back to
     * the same layered conversational answer path without executing writes.
     */
    public void chat(CopilotChatRequest request, Long userId, SseEmitter emitter) {
        CopilotChatRequest safe = request == null ? new CopilotChatRequest() : request;
        CompletableFuture.runAsync(() -> streamChat(safe, userId, emitter), chatExecutor);
    }

    private void streamChat(CopilotChatRequest request, Long userId, SseEmitter emitter) {
        UiStream ui = new UiStream();
        uiStreams.put(emitter, ui);
        requestUiStreams.put(request, ui);
        try {
            send(emitter, "run.started", Map.of("runId", ui.runId));
            send(emitter, "status", Map.of("message", "正在结合你的农场情况…"));
            boolean hasImages = !trustedImageUrls(request.getImageUrls()).isEmpty();
            CopilotRouteResult routed = usesPi() && agriculturalTools != null
                    ? routes.routeHarness(request, userId, hasImages)
                    : usesPi() ? routes.route(request, userId, hasImages, false)
                    : routes.route(request, userId, hasImages);
            if (routed.getConversationId() != null) request.setConversationId(routed.getConversationId());
            ui.conversationId = request.getConversationId(); ui.userId = userId;
            ui.traceId = routed.getTraceId(); ui.intent = routed.getIntent();
            recordActivity(ui, Map.of("type", "run.started", "runId", ui.runId, "at", Instant.now().toString()));
            send(emitter, "route", routed);
            if (streamFarmDirectory(request, routed, userId, emitter)) return;
            if (usesPi() && agriculturalTools != null && routed.getIntent() == CopilotIntent.GENERAL_QA
                    && routed.getResolutionStatus() == CopilotResolutionStatus.RESOLVED && !isCasualGreeting(request.getMessage())) {
                streamHarnessTurn(request, routed, userId, emitter, hasImages);
                return;
            }
            if ((!usesPi() || agriculturalTools == null || routed.getIntent() != CopilotIntent.ACTION_REQUEST)
                    && streamOperationArtifact(request, routed, userId, emitter)) return;
            if (streamCurrentWeather(request, routed, userId, emitter)) return;
            if (routed.getIntent() == CopilotIntent.FARM_MATURITY_SCAN) {
                streamFarmMaturity(request, routed, userId, emitter);
                return;
            }
            emitInitialScope(emitter, request, routed, userId);
            if (routed.getIntent() == CopilotIntent.GENERAL_QA
                    && routed.getResolutionStatus() == CopilotResolutionStatus.RESOLVED
                    && isCasualGreeting(request.getMessage())) {
                // A greeting is a conversational turn, not an agriculture
                // query. Keep it out of field-fact and RAG enrichment so the
                // user does not receive an unrelated phenology report.
                sendResponseMetadata(emitter, request, routed, null, false, false,
                        null, null, null);
                String greeting = "你好，我是 FarMap 农业 Agent。你可以直接提问农事知识，或粘贴现场图片让我分析。";
                streamDelta(emitter, greeting);
                appendChatAnswer(request, routed, greeting, userId);
                sendDone(emitter, "COMPLETED");
                return;
            }
            if (routed.getIntent() != CopilotIntent.GENERAL_QA && routed.getIntent() != CopilotIntent.KNOWLEDGE_QUERY) {
                if (routed.getIntent() == CopilotIntent.DIAGNOSIS_REQUEST && routed.getResolutionStatus() == CopilotResolutionStatus.RESOLVED) {
                    if (asksVisualEvidence(request.getMessage()) && !hasImages) {
                        CopilotFacts knownFacts = fieldContext.resolve(request, routed, userId);
                        sendResponseMetadata(emitter, request, routed, knownFacts, false,
                                knownFacts != null && "READY".equals(knownFacts.status()),
                                knownFacts == null ? null : knownFacts.cropLabel(),
                                knownFacts == null ? null : knownFacts.growthStageLabel(), null);
                        String clarification = visualEvidenceClarification(request, routed, knownFacts);
                        streamDelta(emitter, clarification);
                        appendChatAnswer(request, routed, clarification, userId);
                        sendDone(emitter, "COMPLETED");
                        return;
                    }
                    try {
                        startDiagnosisAndAgent(request, routed, userId, emitter,
                                agriculturalTools == null && !isReadOnlyAnalysis(request.getMessage()));
                    } catch (FieldAccessException error) {
                        throw error;
                    } catch (RuntimeException failure) {
                        streamConversationalFallback(request, routed, userId, emitter, "DIAGNOSIS_CAPABILITY_UNAVAILABLE");
                    }
                    return;
                }
                if (routed.getIntent() == CopilotIntent.ACTION_REQUEST && routed.getResolutionStatus() == CopilotResolutionStatus.RESOLVED) {
                    try {
                        if ((isDirectTaskRequest(request.getMessage()) || routed.getRequestedAction() != null)
                                && (routed.getResolvedContext() == null || routed.getResolvedContext().getDiagnosisId() == null
                                || routed.getResolvedContext().getDiagnosisId().isBlank())) {
                            startDirectTaskAgent(request, routed, userId, emitter);
                        } else {
                            startAgentFromDiagnosis(request, routed, userId, emitter);
                        }
                    } catch (FieldAccessException | PiTurnAborted error) {
                        throw error;
                    } catch (RuntimeException failure) {
                        streamConversationalFallback(request, routed, userId, emitter, "AGENT_CAPABILITY_UNAVAILABLE");
                    }
                    return;
                }
                if (routed.getResolutionStatus() == CopilotResolutionStatus.FORBIDDEN
                        || routed.getResolutionStatus() == CopilotResolutionStatus.UNSUPPORTED) {
                    String rejection = routed.getClarificationQuestion() == null ? "当前请求未通过访问或安全校验，未执行任何诊断、Agent 或写操作。" : routed.getClarificationQuestion();
                    sendResponseMetadata(emitter, request, routed, null, false, false, null, null, null);
                    streamDelta(emitter, rejection);
                    appendChatAnswer(request, routed, rejection, userId);
                    sendDone(emitter, "REJECTED");
                } else if (routed.getIntent() == CopilotIntent.CLARIFICATION_REQUIRED
                        || (routed.getIntent() == CopilotIntent.ACTION_REQUEST || routed.getIntent() == CopilotIntent.DIAGNOSIS_REQUEST)
                        && routed.isRequiresClarification()) {
                    String clarification = routed.getClarificationQuestion() == null ? "请明确要查询的对象或希望执行的动作。" : routed.getClarificationQuestion();
                    sendResponseMetadata(emitter, request, routed, null, false, false, null, null, null);
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
                sendResponseMetadata(emitter, request, routed, null, false, false, null, null, null);
                streamDelta(emitter, clarification);
                appendChatAnswer(request, routed, clarification, userId);
                sendDone(emitter, "COMPLETED");
                return;
            }

            if (!usesPi()) send(emitter, "status", Map.of("message", "正在检索农业知识库…"));
            CopilotFacts facts = initialAnswerFacts(request, routed, userId);
            if ("NO_FIELD".equals(facts.status())) send(emitter, "status", Map.of("message", "未选择地块，以下只能回答通用农业知识。", "code", "NO_FIELD"));
            if ("UNKNOWN_CROP".equals(facts.status())) send(emitter, "status", Map.of("message", "暂不支持当前作物，未按其他作物猜测。", "code", "UNKNOWN_CROP"));
            if ("UNAVAILABLE".equals(facts.status())) send(emitter, "status", Map.of("message", "当前地块事实暂不可用，未使用未经验证的地块数据。", "code", "FIELD_FACTS_UNAVAILABLE"));
            if ("PUBLIC_GENERIC".equals(facts.status())) send(emitter, "status", Map.of("message", "未使用地块实时数据，已按" + facts.cropLabel() + "通用物候历和服务器日期推算。", "code", "PUBLIC_GENERIC"));
            CopilotKnowledgeBundle knowledge = usesPi() ? CopilotKnowledgeBundle.notRequested() : retrieveKnowledge(request, routed, facts, userId);
            if (!usesPi()) emitKnowledgeSummary(emitter, knowledge);
            ReferenceImageResponse referenceImages = emitReferenceImages(emitter, request, routed, facts);
            Map<String, Object> source = answerSource(routed, facts, knowledge, request.getMessage());
            if (!usesPi()) send(emitter, "answer.source", source);
            sendResponseMetadata(emitter, request, routed, facts, !trustedImageUrls(request.getImageUrls()).isEmpty(),
                    facts != null && "READY".equals(facts.status()), facts == null ? null : facts.cropLabel(),
                    facts == null ? null : facts.growthStageLabel(), knowledge);
            if ("UNAVAILABLE".equals(knowledge.status())) send(emitter, "status", Map.of("message", "当前知识库暂不可用；不会伪造地块事实，通用问题将按兜底策略处理。", "code", "KNOWLEDGE_UNAVAILABLE"));
            send(emitter, "status", Map.of("message", "正在生成建议…"));
            if (modelGateway == null && !usesPi()) send(emitter, "status", Map.of("message", "模型暂不可用，无法生成建议。", "code", "MODEL_UNAVAILABLE"));
            beginModelPresentation(emitter);
            String answer = streamModelAnswer(request, routed, facts, knowledge, userId, emitter);
            appendChatAnswer(request, routed, answer, userId, referenceImages);
            sendDone(emitter, "COMPLETED");
        } catch (PiTurnAborted aborted) {
            try { sendDone(emitter, "ABORTED"); }
            catch (IOException deliveryFailure) { emitter.completeWithError(deliveryFailure); }
        } catch (Exception error) {
            // The browser intentionally receives a safe message. Retain the
            // operational cause server-side without logging prompt or image data.
            log.error("COPILOT_CHAT_FAILED userId={} errorType={}", userId,
                    error instanceof ModelCallException modelError ? modelError.getErrorType() : error.getClass().getSimpleName(),
                    error);
            try {
                String code = error instanceof PiAgentRuntime.ServiceFailure failure ? failure.code() : "COPILOT_UNAVAILABLE";
                String message = "PI_TIMEOUT".equals(code) ? "Agent 响应超时，请稍后重试。" : "Copilot 暂时不可用，请稍后重试。";
                send(emitter, "error", Map.of("code", code, "message", message));
                if (ui.traceId != null) appendChatAnswer(request, CopilotRouteResult.builder().traceId(ui.traceId)
                        .intent(ui.intent).build(), message, userId);
                sendDone(emitter, "FAILED");
            } catch (IOException ignored) {
                emitter.completeWithError(error);
                return;
            }
            emitter.complete();
        } finally {
            if (!ui.detached) {
                uiStreams.remove(emitter, ui);
                requestUiStreams.remove(request, ui);
            }
        }
    }

    private void streamHarnessTurn(CopilotChatRequest request, CopilotRouteResult route, Long actor,
                                   SseEmitter emitter, boolean hasImages) throws IOException {
        var facts = fieldContext.scopeOnly(request, route, actor);
        sendResponseMetadata(emitter, request, route, facts, hasImages, false, facts.cropLabel(), null, null);
        if ("UNKNOWN_CROP".equals(facts.status()) && phenologyGuidance.asksTemporalPhenology(request.getMessage())) {
            String clarification = phenologyGuidance.temporalPhenologyAnswer(request.getMessage(), facts);
            streamDelta(emitter, clarification);
            appendChatAnswer(request, route, clarification, actor);
            sendDone(emitter, "COMPLETED");
            return;
        }
        PiTurn turn = new PiTurn();
        String answer = runPiAnswer(request, route, facts, actor, emitter, null, turn, false);
        if (!allowUngrounded) {
            if (!turn.grounded()) answer = "当前没有取得可验证的农业或地块资料。请补充地块、作物或现场证据。";
            streamDelta(emitter, answer);
        }
        emitPiSource(emitter, turn);
        appendChatAnswer(request, route, answer, actor);
        sendDone(emitter, "COMPLETED");
    }

    private void streamFarmMaturity(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter) throws IOException {
        Integer farmId = routed.getResolvedContext() == null ? null : parseInt(routed.getResolvedContext().getFarmId());
        if (routed.getResolutionStatus() != CopilotResolutionStatus.RESOLVED || farmId == null || farmId <= 0) {
            String guidance = routed.getClarificationQuestion() == null ? "请先在顶部选择要评估成熟度的农场。" : routed.getClarificationQuestion();
            streamDelta(emitter, guidance);
            appendChatAnswer(request, routed, guidance, userId);
            sendDone(emitter, routed.getResolutionStatus() == CopilotResolutionStatus.FORBIDDEN ? "REJECTED" : "COMPLETED");
            return;
        }
        if (maturityService == null) {
            String message = "当前未启用成熟度评估服务。";
            streamDelta(emitter, message);
            appendChatAnswer(request, routed, message, userId);
            sendDone(emitter, "FAILED");
            return;
        }
        String message = safe(request.getMessage());
        String lower = message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "历史", "趋势", "历次", "排名", "排行", "适合采收", "采收排序") && !containsAny(lower, "重新扫描", "重新分析", "再次扫描", "现在抓拍")) {
            Map<String, Object> latest;
            try { latest = maturityService.latest(farmId, userId); }
            catch (com.mei.zhgy.service.field.FieldAccessException ex) { maturityGuidance(request, routed, userId, emitter, "当前农场还没有可用的历史视觉分析记录。"); return; }
            if (latest == null || latest.get("runId") == null) { maturityGuidance(request, routed, userId, emitter, "当前农场还没有可用的历史视觉分析记录。"); return; }
            Object previousId = latest.get("runId");
            String previousMode = String.valueOf(latest.getOrDefault("mode", "FARM"));
            emitBlock(emitter, "FarmMaturityCard", "历史视觉分析", null, Map.of("runId", previousId, "mode", previousMode), "ready", List.of());
            send(emitter, "analysis.workspace", Map.of("runId", previousId, "farmId", farmId, "mode", previousMode, "view", containsAny(lower, "排名", "排行", "适合采收", "采收排序") ? "ranking" : "history"));
            String text = "已打开当前农场最近一次视觉分析；历史记录仅反映各次实际画面。";
            streamDelta(emitter, text); appendChatAnswer(request, routed, text, userId); sendDone(emitter, "COMPLETED"); return;
        }
        String mode = containsAny(lower, "物候", "phenology") ? "PHENOLOGY" : containsAny(lower, "长势", "生长", "growth") ? "GROWTH" : "MATURITY";
        List<String> images = trustedImageUrls(request.getImageUrls());
        boolean farmScope = containsAny(lower, "全农场", "整个农场", "全园", "全果园", "成熟度分布", "整个果园", "whole farm");
        boolean imageScope = !farmScope && !images.isEmpty() && (containsAny(lower, "图片", "照片", "图中", "这张图", "image", "photo") || !containsAny(lower, "农场", "果园", "地块", "摄像头", "监控", "farm", "field", "camera"));
        boolean cameraScope = !farmScope && !imageScope && containsAny(lower, "摄像头", "监控", "camera");
        boolean fieldScope = !farmScope && !imageScope && !cameraScope && containsAny(lower, "地块", "田块", "field");
        List<String> cameraIds = new ArrayList<>();
        if (cameraScope) {
            if (maturityCameras == null) { maturityGuidance(request, routed, userId, emitter, "摄像头目录暂不可用，请稍后重试。"); return; }
            var inventory = maturityCameras.list(farmId, userId);
            for (var camera : inventory) {
                if (java.util.regex.Pattern.compile("(?i)(?<![a-z0-9_-])" + java.util.regex.Pattern.quote(camera.cameraId()) + "(?![a-z0-9_-])").matcher(message).find()) cameraIds.add(camera.cameraId());
            }
            if (cameraIds.isEmpty()) {
                for (var camera : inventory) {
                    if (camera.name() == null || camera.name().isBlank()) continue;
                    String cameraName = camera.name().replaceFirst("[-－]球机$", "").trim();
                    if (cameraName.length() < 2 || !message.contains(cameraName)) continue;
                    if (inventory.stream().filter(c -> c.name() != null && c.name().replaceFirst("[-－]球机$", "").trim().equals(cameraName)).count() > 1) {
                        maturityGuidance(request, routed, userId, emitter, "摄像头名称不唯一，请选择摄像头或提供编号。"); return;
                    }
                    cameraIds.add(camera.cameraId());
                }
            }
            if (cameraIds.isEmpty()) {
                List<String> fieldMatches = inventory.stream().filter(c -> c.fieldName() != null && !c.fieldName().isBlank() && message.contains(c.fieldName()))
                        .map(com.mei.zhgy.service.camera.FarmCamera::cameraId).distinct().toList();
                if (fieldMatches.size() == 1) cameraIds.add(fieldMatches.get(0));
                else if (fieldMatches.size() > 1) { maturityGuidance(request, routed, userId, emitter, "该地块关联多个摄像头，请指定摄像头编号。"); return; }
            }
            var page = request.getPageContext();
            if (cameraIds.isEmpty() && containsAny(lower, "这个", "当前", "选中", "这些", "this", "selected") && page != null) {
                List<String> hints = new ArrayList<>();
                if (page.getCameraId() != null) hints.add(page.getCameraId());
                if (page.getCameraIds() != null) hints.addAll(page.getCameraIds());
                for (String hint : hints) {
                    if (inventory.stream().noneMatch(c -> c.cameraId().equals(hint))) { maturityGuidance(request, routed, userId, emitter, "所选摄像头不属于当前农场或不存在，请重新选择。"); return; }
                    if (!cameraIds.contains(hint)) cameraIds.add(hint);
                }
            }
            if (cameraIds.isEmpty()) { maturityGuidance(request, routed, userId, emitter, "请指定或选择当前农场中的摄像头。"); return; }
        }
        String fieldId = fieldScope && routed.getResolvedContext() != null ? routed.getResolvedContext().getFieldId() : null;
        if (fieldScope && (fieldId == null || fieldId.isBlank())) { maturityGuidance(request, routed, userId, emitter, "请先选择要分析的地块。"); return; }
        String kind = imageScope ? "IMAGE" : cameraScope ? "CAMERA" : fieldScope ? "FIELD" : "FARM";
        performInspection(request, routed, userId, emitter, farmId, fieldId, cameraIds, imageScope ? images : List.of(), mode, kind);
    }

    private void performInspection(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter,
                                   Integer farmId, String fieldId, List<String> cameraIds, List<String> images,
                                   String mode, String kind) throws IOException {
        if ("FARM".equals(kind)) send(emitter, "analysis.workspace.pending", Map.of("farmId", farmId, "mode", kind));
        send(emitter, "status", Map.of("message", "正在获取可用画面并进行视觉分析…"));
        String scope = switch (kind) { case "IMAGE" -> "上传图片"; case "CAMERA" -> "所选摄像头"; case "FIELD" -> "当前地块"; default -> "当前农场"; };
        Map<String, Object> progress = emitBlock(emitter, "FarmInspectionProgress", "农场巡检进度", "范围 · " + scope,
                Map.of(), "loading", List.of());
        streamPiEvent(emitter, Map.of("type", "tool_execution_start", "toolName", "farm_maturity_scan"));
        boolean[] progressDeliveryFailed = {false};
        Map<String, Object> report;
        try {
            report = maturityService.analyze(farmId, userId, cameraIds, fieldId, images, mode, kind, update -> {
                if (progress == null) return;
                progress.put("data", Map.of("total", update.total(), "processed", update.processed(), "captured", update.captured(), "valid", update.valid()));
                if (!progressDeliveryFailed[0]) try { updateArtifact(emitter, progress, "loading"); }
                catch (IOException | IllegalStateException ignored) { progressDeliveryFailed[0] = true; }
            });
        } catch (RuntimeException failure) {
            if (!progressDeliveryFailed[0]) updateArtifact(emitter, progress, "failed");
            throw failure;
        }
        updateArtifact(emitter, progress, "completed");
        streamPiEvent(emitter, Map.of("type", "tool_execution_end", "toolName", "farm_maturity_scan"));
        String summary = String.valueOf(report.getOrDefault("summary", "分析已完成，请查看结果。"));
        publishSummary(emitter, summary);
        Object runId = report.get("runId");
        if (runId != null) {
            emitBlock(emitter, "FarmMaturityCard", "FARM".equals(kind) ? "农场视觉分析" : "视觉分析", summary,
                    Map.of("runId", runId, "farmId", farmId, "mode", kind, "kind", mode),
                    "NO_VALID_OBSERVATIONS".equals(report.get("status")) ? "unavailable" : "ready", List.of());
            if ("FARM".equals(kind)) send(emitter, "analysis.workspace", Map.of("runId", runId, "farmId", farmId, "mode", kind));
            if (hasMaturityRecommendation(report)) emitMaturityRecommendation(emitter, String.valueOf(runId), fieldId);
        }
        finishPresentedTurn(request, routed, userId, emitter, summary, null, List.of());
    }

    private boolean streamFarmDirectory(CopilotChatRequest request, CopilotRouteResult route, Long userId, SseEmitter emitter) throws IOException {
        if (farmFieldService == null || !CopilotOperationQuery.farmDirectory(request.getMessage()) || !presentationAllowed(route)) return false;
        streamPiEvent(emitter, Map.of("type", "tool_execution_start", "toolName", "read_farm_directory"));
        var farms = farmFieldService.accessibleFarms(userId);
        streamPiEvent(emitter, Map.of("type", "tool_execution_end", "toolName", "read_farm_directory"));
        String summary = farms.isEmpty() ? "当前账号没有可访问的农场。" : "当前账号可访问 " + farms.size() + " 个农场。";
        publishSummary(emitter, summary);
        emitBlock(emitter, "FarmDirectoryCard", "农场列表", null, Map.of("farms", farms), farms.isEmpty() ? "unavailable" : "ready", List.of());
        finishPresentedTurn(request, route, userId, emitter, summary, null, List.of());
        return true;
    }

    private void publishSummary(SseEmitter emitter, String summary) throws IOException {
        UiStream ui = uiStreams.get(emitter);
        if (ui != null) ui.presentation.put("summary", summary);
        send(emitter, "presentation.summary", Map.of("text", summary));
        for (String event : List.of("content.delta", "message.delta")) {
            emitter.send(SseEmitter.event().name(event).data(Map.of("delta", summary)));
        }
    }

    private void finishPresentedTurn(CopilotChatRequest request, CopilotRouteResult route, Long userId, SseEmitter emitter,
                                     String summary, String analysis, List<Map<String, String>> followUps) throws IOException {
        UiStream ui = uiStreams.get(emitter);
        if (analysis != null && !analysis.isBlank()) {
            if (ui != null) ui.presentation.put("analysis", analysis);
            send(emitter, "presentation.analysis", Map.of("text", analysis));
        }
        if (!followUps.isEmpty()) {
            if (ui != null) ui.presentation.put("followUps", followUps);
            send(emitter, "presentation.followups", Map.of("items", followUps));
        }
        appendChatAnswer(request, route, analysis == null || analysis.isBlank() ? summary : summary + "\n\n" + analysis, userId);
        sendDone(emitter, "COMPLETED");
    }

    private void beginModelPresentation(SseEmitter emitter) throws IOException {
        UiStream ui = uiStreams.get(emitter);
        if (ui == null || !ui.presentation.isEmpty()) return;
        String summary = null;
        if (ui.weather != null && "READY".equals(ui.weather.status()) && !safe(ui.weather.condition()).isBlank()) {
            summary = safe(ui.weather.farmName()).isBlank() ? "当前天气为" + ui.weather.condition() + "。"
                    : ui.weather.farmName() + "目前天气为" + ui.weather.condition() + "。";
        } else {
            var relevant = ui.blocks.stream()
                    .filter(block -> Set.of("ParcelOverviewCard", "FarmAnalyticsCard", "FarmMapCard").contains(block.get("componentType")))
                    .filter(block -> "ready".equals(block.get("status")))
                    .map(block -> String.valueOf(block.get("title"))).distinct().toList();
            if (!relevant.isEmpty()) summary = "已读取" + String.join("和", relevant) + "。";
        }
        if (summary == null) return;
        publishSummary(emitter, summary);
        ui.modelAnalysis = true;
    }

    private boolean presentationAllowed(CopilotRouteResult route) {
        return route.getIntent() != CopilotIntent.UNSUPPORTED && route.getResolutionStatus() != CopilotResolutionStatus.UNSUPPORTED
                && route.getResolutionStatus() != CopilotResolutionStatus.FORBIDDEN;
    }

    private boolean streamOperationArtifact(CopilotChatRequest request, CopilotRouteResult route, Long userId, SseEmitter emitter) throws IOException {
        CopilotPageContext page = request.getPageContext();
        if (page == null || !presentationAllowed(route)) return false;
        String text = safe(request.getMessage());
        String explicitTask = CopilotOperationQuery.taskId(text);
        String routedTask = route.getResolvedContext() == null ? null : route.getResolvedContext().getTaskId();
        String taskId = explicitTask != null ? explicitTask : routedTask != null ? routedTask : page.getTaskId();
        String queryView = CopilotOperationQuery.viewOf(text);
        boolean opensTask = containsAny(text, "执行反馈", "任务反馈", "任务进度", "打开当前农事任务", "当前任务详情", "当前任务状态")
                || (explicitTask != null && containsAny(text, "查看", "打开", "进度", "详情", "记录", "排期"))
                || route.getIntent() == CopilotIntent.OPERATIONS_STATUS_QUERY || route.getIntent() == CopilotIntent.OUTCOME_QUERY;
        if (workspaceTasks != null && taskId != null && queryView == null && opensTask) {
            var task = workspaceTasks.getTask(taskId, userId);
            emitBlock(emitter, "OperationTaskCard", "执行反馈", null, Map.of("taskId", task.get("taskId")), "ready", List.of());
            maturityGuidance(request, route, userId, emitter, "已打开任务，可在卡片中查看进度或填写执行反馈。");
            return true;
        }
        if (queryView != null && farmFieldService != null) return streamTaskView(request, route, userId, emitter, queryView);
        if (farmFieldService != null && CopilotOperationQuery.adviceView(text)) {
            Integer farmId = operationFarm(route, page);
            if (farmId == null || farmId <= 0) {
                maturityGuidance(request, route, userId, emitter, "请先选择要查看农事建议的农场。");
                return true;
            }
            OperationScope scope = operationScope(farmId, route, page, text, userId);
            if (scope.problem() != null) {
                maturityGuidance(request, route, userId, emitter, scope.problem());
                return true;
            }
            var advice = new LinkedHashMap<String, Object>();
            advice.put("farmId", farmId);
            if (scope.fieldId() != null) advice.put("fieldId", scope.fieldId());
            emitBlock(emitter, "FarmAdviceCard", "农事建议", null, advice, "ready", List.of());
            maturityGuidance(request, route, userId, emitter, "已打开当前范围的观测和诊断建议。");
            return true;
        }

        boolean asksHarvestPlan = CopilotOperationQuery.harvestPlan(text);
        String analysisRunId = page.getAnalysisRunId();
        if (maturityService == null || !asksHarvestPlan) return false;
        boolean namesField = CopilotOperationQuery.fieldCode(text) != null || text.contains("field-");
        if (namesField && !containsAny(text, "报告", "上次观测", "历史观测")) analysisRunId = null;
        Integer selectedFarm = operationFarm(route, page);
        if (analysisRunId == null && selectedFarm != null && selectedFarm > 0 && farmFieldService != null
                && !containsAny(text, "历史报告", "已有报告", "上次报告", "最近报告")) {
            OperationScope scope = operationScope(selectedFarm, route, page, text, userId);
            if (scope.problem() != null) {
                maturityGuidance(request, route, userId, emitter, scope.problem());
            } else if (scope.fieldId() != null || isFarmWide(text)) {
                performInspection(request, route, userId, emitter, selectedFarm, scope.fieldId(), List.of(), List.of(), "MATURITY",
                        scope.fieldId() == null ? "FARM" : "FIELD");
            } else {
                maturityGuidance(request, route, userId, emitter, "请先选择采收地块，或明确要检查整个农场。");
            }
            return true;
        }
        Map<String, Object> report;
        if (analysisRunId != null) report = maturityService.run(analysisRunId, userId);
        else if (selectedFarm != null && selectedFarm > 0) report = maturityService.runs(selectedFarm, userId).stream()
                .filter(value -> "MATURITY".equals(value.get("kind"))).findFirst().orElse(null);
        else {
            maturityGuidance(request, route, userId, emitter, "请先选择要安排采收复核的农场。");
            return true;
        }
        if (report == null) {
            maturityGuidance(request, route, userId, emitter, "当前农场还没有成熟度观测报告。请先发起成熟度分析，再根据报告安排复核。");
            return true;
        }
        if (selectedFarm != null && !String.valueOf(selectedFarm).equals(String.valueOf(report.get("farmId")))) {
            maturityGuidance(request, route, userId, emitter, "请切换到报告所属农场后安排任务。");
            return true;
        }
        if (!hasMaturityRecommendation(report)) {
            maturityGuidance(request, route, userId, emitter, "这份报告没有足够的成熟度证据，请先补充观测或实地复核。");
            return true;
        }
        String requestedField = null;
        if (namesField && selectedFarm != null && farmFieldService != null) {
            OperationScope scope = operationScope(selectedFarm, route, page, text, userId);
            if (scope.problem() != null) {
                maturityGuidance(request, route, userId, emitter, scope.problem());
                return true;
            }
            requestedField = scope.fieldId();
            if (!reportSupportsField(report, requestedField)) {
                maturityGuidance(request, route, userId, emitter, "这份报告没有该地块的有效成熟画面。请先分析该地块的成熟度。");
                return true;
            }
        }
        emitMaturityRecommendation(emitter, String.valueOf(report.get("runId")), requestedField);
        maturityGuidance(request, route, userId, emitter, "已打开报告中的采收建议。你可以选择地块并生成实地复核任务草稿。");
        return true;
    }

    private boolean streamTaskView(CopilotChatRequest request, CopilotRouteResult route, Long userId, SseEmitter emitter, String view) throws IOException {
        var page = request.getPageContext();
        Integer farmId = operationFarm(route, page);
        if (farmId == null || farmId <= 0) {
            maturityGuidance(request, route, userId, emitter, "请先选择要查询的农场。");
            return true;
        }
        OperationScope scope = operationScope(farmId, route, page, request.getMessage(), userId);
        if (scope.problem() != null) {
            maturityGuidance(request, route, userId, emitter, scope.problem());
            return true;
        }
        CopilotOperationQuery query;
        try { query = CopilotOperationQuery.resolve(view, request.getMessage(), referenceDay(page)); }
        catch (IllegalArgumentException invalid) {
            maturityGuidance(request, route, userId, emitter, invalid.getMessage());
            return true;
        }
        String from = query.from() == null ? null : query.from().toString();
        String to = query.to() == null ? null : query.to().toString();
        String filter = "tasks".equals(view) ? CopilotOperationQuery.statusFilter(request.getMessage()) : null;
        if (workspaceTaskViews != null) {
            streamPiEvent(emitter, Map.of("type", "tool_execution_start", "toolName", "query_operations_tasks"));
            try {
                if (filter == null) workspaceTaskViews.view(farmId, scope.fieldId(), from, to, view, userId);
                else workspaceTaskViews.view(farmId, scope.fieldId(), from, to, view, filter, userId);
            }
            catch (RuntimeException failure) {
                streamPiEvent(emitter, Map.of("type", "tool_execution_end", "toolName", "query_operations_tasks", "isError", true));
                throw failure;
            }
            streamPiEvent(emitter, Map.of("type", "tool_execution_end", "toolName", "query_operations_tasks"));
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("farmId", farmId); data.put("taskView", view);
        if (scope.fieldId() != null) data.put("fieldId", scope.fieldId());
        if (from != null) data.put("from", from);
        if (to != null) data.put("to", to);
        if (filter != null) data.put("taskFilter", filter);
        String title = switch (view) { case "calendar" -> "任务日历"; case "records" -> "执行记录"; default -> "农事任务"; };
        String summary = switch (view) {
            case "calendar" -> "已整理当前范围的排期，可以在日历中切换周或月。";
            case "records" -> "已整理当前范围的执行记录，可打开任务查看结果。";
            default -> "已整理当前范围的农事任务，可查看任务详情或调整排期。";
        };
        publishSummary(emitter, summary);
        emitBlock(emitter, "OperationsQueryCard", title, null, data, "ready", List.of());
        finishPresentedTurn(request, route, userId, emitter, summary, null, List.of());
        return true;
    }

    private record OperationScope(String fieldId, String problem) {}

    private Integer operationFarm(CopilotRouteResult route, CopilotPageContext page) {
        String canonical = route.getResolvedContext() == null ? null : route.getResolvedContext().getFarmId();
        return parseInt(canonical == null && page != null ? page.getFarmId() : canonical);
    }

    private OperationScope operationScope(Integer farmId, CopilotRouteResult route, CopilotPageContext page, String raw, Long userId) {
        String text = safe(raw);
        List<FarmField> fields = farmFieldService.list(farmId, userId);
        String code = CopilotOperationQuery.fieldCode(text);
        var named = fields.stream().filter(field -> CopilotOperationQuery.mentionsField(text, field.getFieldId(), field.getName())).toList();
        if (named.size() == 1) return new OperationScope(named.get(0).getFieldId(), null);
        if (named.size() > 1) return new OperationScope(null, "匹配到多个地块，请指定其中一个地块。");
        if (code != null || text.contains("field-")) return new OperationScope(null, "当前农场未找到所指地块，请核对名称或编号。");
        if (isFarmWide(text)) return new OperationScope(null, null);
        String fieldId = page.getFieldId();
        if (fieldId == null && route.getResolvedContext() != null) fieldId = route.getResolvedContext().getFieldId();
        final String selected = fieldId;
        if (selected != null && fields.stream().noneMatch(f -> selected.equals(f.getFieldId()))) {
            return new OperationScope(null, "所选地块不属于当前农场，请重新选择。");
        }
        if (selected == null && containsAny(text, "这个地块", "当前地块", "该地块", "某个地块")) return new OperationScope(null, "请先选择要查询的地块。");
        return new OperationScope(selected, null);
    }

    private boolean isFarmWide(String text) {
        return CopilotOperationQuery.farmWide(text);
    }

    private LocalDate referenceDay(CopilotPageContext page) {
        if (page != null && page.getReferenceDate() != null) {
            try { return LocalDate.parse(page.getReferenceDate()); }
            catch (java.time.format.DateTimeParseException ignored) { }
        }
        return LocalDate.now(COPILOT_ZONE);
    }

    private Integer requestedGuidanceMonth(CopilotChatRequest request) {
        String text = safe(request.getMessage());
        if (!containsAny(text, "农事", "栽培管理", "果园管理") || !text.contains("月")) return null;
        var namedMonth = java.util.regex.Pattern.compile("(?<!\\d)(1[0-2]|[1-9])\\s*月").matcher(text);
        if (namedMonth.find()) return Integer.valueOf(namedMonth.group(1));
        if (!containsAny(text, "月度", "本月", "这个月", "上月", "下月")) return null;
        return referenceDay(request.getPageContext()).plusMonths(text.contains("上月") ? -1 : text.contains("下月") ? 1 : 0).getMonthValue();
    }

    private List<Map<String, Object>> readMonthlyGuidance(CopilotChatRequest request, CopilotRouteResult route, Long actor) {
        Integer month = requestedGuidanceMonth(request);
        var page = request.getPageContext();
        if (month == null || page == null || monthlyGuidanceService == null) return List.of();
        Integer farmId = operationFarm(route, page);
        if (farmId == null || farmId <= 0) return List.of();
        return readMonthlyGuidance(farmId, actor, month);
    }

    private List<Map<String, Object>> readMonthlyGuidance(Integer farmId, Long actor, int month) {
        try {
            var guidance = monthlyGuidanceService.guidance(farmId, actor, month - 1);
            if (guidance == null || !"READY".equals(guidance.status()) || guidance.entries() == null) return List.of();
            return guidance.entries().stream().filter(entry -> entry.text() != null && !entry.text().isBlank()).limit(12)
                    .map(entry -> Map.<String, Object>of("month", month, "category", safe(entry.guidanceType()),
                            "text", bound(entry.text(), 400), "source", "农场月度指导"))
                    .toList();
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private boolean hasMaturityRecommendation(Map<String, Object> report) {
        return "MATURITY".equals(report.get("kind")) && "COMPLETED".equals(report.get("status"))
                && report.get("recommendations") instanceof List<?> recommendations && !recommendations.isEmpty();
    }

    private boolean reportSupportsField(Map<String, Object> report, String fieldId) {
        if (fieldId == null || !(report.get("parcels") instanceof List<?> parcels)) return false;
        return parcels.stream().anyMatch(item -> item instanceof Map<?, ?> parcel && fieldId.equals(parcel.get("fieldId"))
                && parcel.get("observations") instanceof List<?> observations && observations.stream().anyMatch(value -> value instanceof Map<?, ?> observation
                    && "SUCCESS".equals(observation.get("status"))
                    && Set.of("RIPE", "OVER_RIPE").contains(String.valueOf(observation.get("stage")))));
    }

    private void emitMaturityRecommendation(SseEmitter emitter, String analysisRunId, String fieldId) throws IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("analysisRunId", analysisRunId);
        if (fieldId != null) data.put("fieldId", fieldId);
        emitBlock(emitter, "FarmRecommendationCard", "采收建议与任务草稿", null, data, "ready", List.of());
    }

    private void maturityGuidance(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter, String text) throws IOException {
        streamDelta(emitter, text);
        appendChatAnswer(request, routed, text, userId);
        sendDone(emitter, "COMPLETED");
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private void startDiagnosisAndAgent(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter, boolean createAgentRun) throws IOException {
        UiStream telemetryUi = uiStreams.get(emitter);
        if (telemetryUi != null) telemetryUi.telemetry.mode("ASYNC_DIAGNOSIS");
        if (diagnosisJobs == null) throw new IllegalStateException("诊断任务服务未初始化");
        CopilotFacts knownFacts = null;
        if (fieldFactsService != null && routed.getResolvedContext() != null && routed.getResolvedContext().getFieldId() != null) {
            try { knownFacts = CopilotFacts.from(fieldFactsService.forField(routed.getResolvedContext().getFieldId(), userId)); }
            catch (FieldAccessException error) { throw error; }
            catch (RuntimeException ignored) { /* the existing diagnosis path will report the capability failure */ }
        }
        sendResponseMetadata(emitter, request, routed, knownFacts, !trustedImageUrls(request.getImageUrls()).isEmpty(),
                knownFacts != null && "READY".equals(knownFacts.status()), knownFacts == null ? null : knownFacts.cropLabel(),
                knownFacts == null ? null : knownFacts.growthStageLabel(), null);
        DiagnosisAnalyzeRequest diagnosis = new DiagnosisAnalyzeRequest();
        diagnosis.setFieldId(routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFieldId());
        diagnosis.setFarmId(parseInt(routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFarmId()));
        diagnosis.setQuery(request.getMessage());
        diagnosis.setTopK(5);
        diagnosis.setImageUrls(trustedImageUrls(request.getImageUrls()));
        diagnosis.setEnabledModalities(List.of("camera", "weather", "historical_case", "knowledge", "soil", "spatial",
                "accumulated_temperature", "satellite"));
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
        var completion = diagnosisJobs.completion(job.getJobId(), userId);
        UiStream ui = uiStreams.get(emitter);
        var connected = new java.util.concurrent.atomic.AtomicBoolean(true);
        var unsubscribe = diagnosisJobs.subscribeProgress(job.getJobId(), userId, update -> {
            if (!connected.get()) return;
            try {
                emitDiagnosisProgress(emitter, job.getJobId(), update);
            } catch (IOException | IllegalStateException disconnected) {
                connected.set(false);
            }
        });
        if (ui != null) ui.detached = true;
        emitter.onCompletion(() -> connected.set(false));
        completion.whenCompleteAsync((completed, error) -> {
            try {
                closeDiagnosisTurn(request, routed, userId, emitter, error == null ? completed : null, createAgentRun);
            } catch (IOException | RuntimeException failure) {
                if (connected.get()) emitter.completeWithError(failure);
            } finally {
                unsubscribe.run();
                if (ui != null) uiStreams.remove(emitter, ui);
                requestUiStreams.remove(request);
            }
        }, chatExecutor);
    }

    private void emitDiagnosisProgress(SseEmitter emitter, String jobId,
                                       com.mei.zhgy.service.rag.DiagnosisRagService.RetrievalProgress update) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("jobId", jobId);
        event.put("stage", update.phase());
        event.put("modality", update.modality());
        event.put("status", update.status());
        event.put("count", update.evidenceCount());
        send(emitter, "diagnosis.progress", event);
        String label = switch (update.modality() == null ? "" : update.modality()) {
            case "camera" -> "视觉证据";
            case "historical_case" -> "历史病例";
            case "weather" -> "气象证据";
            case "knowledge" -> "农业知识";
            case "agronomy_guardrail" -> "农事安全条件";
            case "soil" -> "土壤证据";
            case "spatial" -> "空间证据";
            case "satellite" -> "遥感证据";
            case "accumulated_temperature" -> "积温证据";
            default -> null;
        };
        String text = label != null ? label + " · " + ("READY".equals(update.status()) ? update.evidenceCount() + " 条" : "本次未取得可用结果")
                : "FUSION".equals(update.phase()) ? "已整理多模态证据" : "正在核验诊断与证据的对应关系";
        send(emitter, "status", Map.of("message", text));
    }

    private void closeDiagnosisTurn(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter,
                                    DiagnosisJobService.DiagnosisJobResponse completed, boolean createAgentRun) throws IOException {
        if (completed == null || !"COMPLETED".equalsIgnoreCase(completed.getStatus()) || completed.getDiagnosisId() == null) {
            String message = completed == null || completed.getError() == null ? "诊断任务未完成，未创建 Agent Run。" : completed.getError();
            streamDelta(emitter, message);
            sendDone(emitter, "FAILED");
            return;
        }
        String recommendationId = completed.getResult() == null || completed.getResult().getRecommendations() == null || completed.getResult().getRecommendations().isEmpty()
                ? null : completed.getResult().getRecommendations().get(0).getId();
        var citations = CopilotDiagnosisPresentation.citations(completed.getResult());
        for (var reference : citations.entries()) send(emitter, "knowledge.evidence", reference);
        var details = completed.getResult() == null ? null : completed.getResult().getMetadata();
        UiStream telemetryUi = uiStreams.get(emitter);
        if (telemetryUi != null) {
            telemetryUi.telemetry.source("MULTIMODAL_DIAGNOSIS");
            if (details != null) telemetryUi.telemetry.model(details.get("selectedModel"), details);
        }
        if (!createAgentRun) {
            String summary = CopilotDiagnosisPresentation.summary(completed.getResult());
            send(emitter, "diagnosis.completed", Map.of("diagnosisId", completed.getDiagnosisId(), "summary", summary));
            streamDelta(emitter, summary);
            emitDiagnosisPresentation(emitter, completed, trustedImageUrls(request.getImageUrls()), citations.sources());
            appendChatAnswer(request, routed, summary, userId);
            sendDone(emitter, "COMPLETED");
            return;
        }
        emitDiagnosisPresentation(emitter, completed, trustedImageUrls(request.getImageUrls()), citations.sources());
        AgentRun run = createAgentFromDiagnosis(request.getMessage(), completed.getDiagnosisId(), recommendationId, userId);
        emitWorkflowBlock(request, routed, userId, emitter, run.getRunId(), completed.getDiagnosisId());
        appendChatAnswer(request, routed, "已打开农事工作流，可核对草稿和查看进度。", userId);
        send(emitter, "run.created", Map.of("runId", run.getRunId(), "diagnosisId", completed.getDiagnosisId(),
                "status", usesPi() ? run.getStatus().name() : "PLANNING"));
        projectWorkflow(request, routed, userId, emitter, run.getRunId());
        sendDone(emitter, "RUN_CREATED");
    }

    private void emitDiagnosisPresentation(SseEmitter emitter, DiagnosisJobService.DiagnosisJobResponse completed,
                                           List<String> images, List<Map<String, Object>> sources) throws IOException {
        for (var card : CopilotDiagnosisPresentation.reportCards(completed.getDiagnosisId(), completed.getResult())) {
            emitCard(emitter, card);
        }
        var comparison = CopilotDiagnosisPresentation.pestComparison(completed.getDiagnosisId(), completed.getResult(), images, sources);
        if (comparison != null) emitCard(emitter, comparison);
    }

    private void startAgentFromDiagnosis(CopilotChatRequest request, CopilotRouteResult routed, Long userId, SseEmitter emitter) throws IOException {
        if (agentRuntime == null || routed.getResolvedContext() == null || routed.getResolvedContext().getDiagnosisId() == null
                || routed.getResolvedContext().getRecommendationId() == null) {
            throw new IllegalStateException("当前建议缺少可验证的 Diagnosis provenance。");
        }
        sendResponseMetadata(emitter, request, routed, null, !trustedImageUrls(request.getImageUrls()).isEmpty(), false,
                null, null, null);
        AgentRun run = createAgentFromDiagnosis(request.getMessage(), routed.getResolvedContext().getDiagnosisId(), routed.getResolvedContext().getRecommendationId(), userId);
        emitWorkflowBlock(request, routed, userId, emitter, run.getRunId(), routed.getResolvedContext().getDiagnosisId());
        appendChatAnswer(request, routed, "已打开农事工作流，可核对草稿和查看进度。", userId);
        send(emitter, "run.created", Map.of("runId", run.getRunId(), "diagnosisId", routed.getResolvedContext().getDiagnosisId(),
                "status", usesPi() ? run.getStatus().name() : "PLANNING"));
        projectWorkflow(request, routed, userId, emitter, run.getRunId());
        sendDone(emitter, "RUN_CREATED");
    }

    /**
     * Direct task requests have no Diagnosis provenance yet. They may still
     * enter the normal Agent Runtime: the run reads the verified field,
     * creates a proposed Operations action, and stops at human approval. No
     * Operations row is written by this method.
     */
    private void startDirectTaskAgent(CopilotChatRequest request, CopilotRouteResult routed,
                                      Long userId, SseEmitter emitter) throws IOException {
        var taskScope = routed.getResolvedContext();
        boolean farmBatchScope = usesPi() && agriculturalTools != null && taskScope != null
                && parseInt(taskScope.getFarmId()) != null;
        if (agentRuntime == null || taskScope == null || (!farmBatchScope && safe(taskScope.getFieldId()).isBlank())) {
            throw new IllegalStateException("创建农事任务需要已验证的地块上下文。");
        }
        CopilotFacts knownFacts = fieldContext.resolve(request, routed, userId);
        sendResponseMetadata(emitter, request, routed, knownFacts, !trustedImageUrls(request.getImageUrls()).isEmpty(),
                knownFacts != null && "READY".equals(knownFacts.status()),
                knownFacts == null ? null : knownFacts.cropLabel(),
                knownFacts == null ? null : knownFacts.growthStageLabel(), null);
        streamDelta(emitter, "已识别为农事任务请求，正在准备任务草案；未经过人工审批前不会写入任务。\n");

        AgentContext context = new AgentContext();
        context.setFarmId(routed.getResolvedContext().getFarmId());
        context.setFieldId(routed.getResolvedContext().getFieldId());
        context.setImageUrls(trustedImageUrls(request.getImageUrls()));
        if (request.getPageContext() != null) context.setPage(request.getPageContext().getRoute());
        String goal = routed.getRequestedAction() == null || routed.getRequestedAction().isBlank()
                ? request.getMessage() : routed.getRequestedAction();
        boolean draftingWithPi = usesPi();
        List<AgentRun> runs;
        String explanation = "";
        if (draftingWithPi) {
            PiTurn turn = new PiTurn();
            try {
                explanation = runPiAnswer(request, routed, knownFacts, userId, emitter, null, turn, true);
            } catch (PiAgentRuntime.ServiceFailure failure) {
                String message = "PI_TIMEOUT".equals(failure.code()) ? "草稿生成超时，请稍后重试。"
                        : "草稿生成服务暂时不可用，请稍后重试。";
                send(emitter, "error", Map.of("code", failure.code(), "message", message));
                appendChatAnswer(request, routed, message, userId);
                sendDone(emitter, "FAILED");
                return;
            }
            emitPiSource(emitter, turn);
            if ((turn.draft == null && turn.batch == null) || turn.conflictingDrafts) {
                String answer = "未得到可确认的农事任务草稿。请明确要执行的操作和日期后重试。";
                if (!explanation.isBlank()) answer = explanation + "\n\n" + answer;
                streamDelta(emitter, answer);
                appendChatAnswer(request, routed, answer, userId);
                sendDone(emitter, "COMPLETED");
                return;
            }
            final String reviewNote = explanation;
            if (!reviewNote.isBlank()) streamDelta(emitter, reviewNote);
            if (turn.batch != null) {
                var drafts = turn.batch.tasks().stream().map(item -> new AgentRuntime.ScopedTaskDraft(
                        item.scope(), approvalDraft(item.scope(), item.draft(), turn, reviewNote), item.evidence())).toList();
                runs = agentRuntime.prepareTaskDraftBatch(goal, userId, drafts);
            } else {
                runs = List.of(agentRuntime.prepareTaskDraft(goal, context, userId, approvalDraft(context, turn.draft, turn, reviewNote)));
            }
        } else {
            runs = List.of(agentRuntime.createRun(goal, context, "real", userId));
        }
        for (AgentRun run : runs) {
            emitWorkflowBlock(request, routed, userId, emitter, run.getRunId(), null);
            send(emitter, "run.created", Map.of("runId", run.getRunId(), "status",
                    draftingWithPi ? run.getStatus().name() : "PLANNING", "workflow", "OPERATIONS_TASK_DRAFT"));
            if (!draftingWithPi) agentRuntime.start(run.getRunId());
            projectWorkflow(request, routed, userId, emitter, run.getRunId());
        }
        appendChatAnswer(request, routed, explanation.isBlank() ? "已打开农事工作流，可核对草稿和查看进度。" : explanation, userId);
        sendDone(emitter, "RUN_CREATED");
    }

    private AgentRuntime.TaskDraft approvalDraft(AgentContext scope, AgentRuntime.TaskDraft draft, PiTurn turn, String explanation) {
        var evidence = new LinkedHashMap<String, com.mei.zhgy.vo.DiagnosisEvidenceVO>();
        for (var item : draft.supportingEvidence()) evidence.put(item.getId(), item);
        for (var item : turn.evidence.taskEvidence(parseInt(scope.getFarmId()), scope.getFieldId())) evidence.putIfAbsent(item.getId(), item);
        if (!explanation.isBlank()) {
            String id = "draft-explanation-" + java.util.UUID.randomUUID();
            evidence.put(id, com.mei.zhgy.vo.DiagnosisEvidenceVO.builder().id(id).modality("proposal_explanation")
                    .title("Agent 草稿说明").summary(bound(explanation, 6000))
                    .source(com.mei.zhgy.vo.DiagnosisEvidenceVO.Source.builder().type("model_explanation")
                            .name("FarMap Agent").fieldId(scope.getFieldId()).build())
                    .metadata(Map.of("independentEvidence", false)).build());
        }
        return new AgentRuntime.TaskDraft(scope.getFieldId(), draft.title(), draft.detail(), draft.scheduledFor(), List.copyOf(evidence.values()));
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
        CopilotFacts facts = initialAnswerFacts(request, routed, userId);
        CopilotKnowledgeBundle knowledge = usesPi() ? CopilotKnowledgeBundle.notRequested() : retrieveKnowledge(request, routed, facts, userId);
        if (!usesPi()) emitKnowledgeSummary(emitter, knowledge);
        ReferenceImageResponse referenceImages = emitReferenceImages(emitter, request, routed, facts);
        if (!usesPi()) send(emitter, "answer.source", answerSource(routed, facts, knowledge, request.getMessage()));
        sendResponseMetadata(emitter, request, routed, facts, !trustedImageUrls(request.getImageUrls()).isEmpty(),
                facts != null && "READY".equals(facts.status()), facts == null ? null : facts.cropLabel(),
                facts == null ? null : facts.growthStageLabel(), knowledge);
        beginModelPresentation(emitter);
        String answer = streamModelAnswer(request, routed, facts, knowledge, userId, emitter);
        appendChatAnswer(request, routed, answer, userId, referenceImages);
        sendDone(emitter, "COMPLETED");
    }

    private AgentRun createAgentFromDiagnosis(String goal, String diagnosisId, String recommendationId, Long userId) {
        if (agentRuntime == null) throw new IllegalStateException("Agent Runtime 未初始化");
        return usesPi() ? agentRuntime.prepareDiagnosisApproval(goal, diagnosisId, recommendationId, userId)
                : agentRuntime.createRunFromPersistedDiagnosis(goal, diagnosisId, recommendationId, userId);
    }

    private void emitKnowledgeSummary(SseEmitter emitter, CopilotKnowledgeBundle knowledge) throws IOException {
        int count = knowledge == null || knowledge.items() == null ? 0 : knowledge.items().size();
        String status = knowledge == null || knowledge.status() == null ? "UNAVAILABLE" : knowledge.status();
        UiStream ui = uiStreams.get(emitter);
        if (ui != null) {
            ui.knowledgeCount = count;
            ui.knowledgeStatus = status;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", count);
        summary.put("status", status);
        send(emitter, "knowledge", summary);
    }

    private void sendResponseMetadata(SseEmitter emitter, CopilotChatRequest request, CopilotRouteResult routed,
                                      CopilotFacts facts, boolean hasImages, boolean fieldFactsReady,
                                      String cropLabel, String growthStageLabel, CopilotKnowledgeBundle knowledge) throws IOException {
        String messageId = request == null || request.getClientMessageId() == null || request.getClientMessageId().isBlank()
                ? routed == null ? "copilot-response" : routed.getTraceId() : request.getClientMessageId();
        String knowledgeStatus = knowledge == null || knowledge.status() == null ? "NOT_REQUESTED" : knowledge.status();
        List<Map<String, Object>> knowledgeItems = knowledge == null || knowledge.items() == null ? List.of() : knowledge.items();
        CopilotResponseMetadata metadata = CopilotResponsePolicy.build(routed,
                request == null ? "" : request.getMessage(), messageId, hasImages, fieldFactsReady,
                cropLabel, growthStageLabel, knowledgeStatus, knowledgeItems);
        metadata.setSources(List.of());
        if (metadata.getSuggestions() != null) metadata.setSuggestions(metadata.getSuggestions().stream()
                .filter(suggestion -> suggestion == null || !"查看知识依据".equals(suggestion.getText())).toList());
        send(emitter, "response.meta", metadata);
        UiStream ui = uiStreams.get(emitter);
        if (ui == null || routed == null) return;
        if (routed.isRequiresClarification() || routed.getIntent() == CopilotIntent.CLARIFICATION_REQUIRED) {
            String question = safe(routed.getClarificationQuestion());
            if (!question.isBlank()) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("id", "answer"); field.put("label", question); field.put("required", true);
                Integer farmId = routed.getResolvedContext() == null ? null : parseInt(routed.getResolvedContext().getFarmId());
                if (farmId != null && farmFieldService != null && ui.userId != null && contains(question, "地块")) {
                    List<Map<String, Object>> options = new ArrayList<>();
                    for (FarmField item : farmFieldService.list(farmId, ui.userId))
                        options.add(Map.of("label", item.getName(), "value", item.getFieldId()));
                    if (!options.isEmpty()) field.put("options", options);
                }
                emitBlock(emitter, "ClarificationForm", "补充信息", question,
                        Map.of("fields", List.of(field), "originalRequest", safe(request.getMessage())), "needs-input", List.of());
            }
        }
    }

    private ReferenceImageResponse emitReferenceImages(SseEmitter emitter, CopilotChatRequest request,
                                                       CopilotRouteResult routed, CopilotFacts facts) throws IOException {
        boolean hasUserImages = request != null && !trustedImageUrls(request.getImageUrls()).isEmpty();
        CopilotMediaNeed mediaNeed = CopilotMediaPolicy.decide(request == null ? null : request.getMessage(),
                routed == null ? null : routed.getIntent(), hasUserImages);
        ReferenceImageLookup lookup = mediaNeed == CopilotMediaNeed.NONE || referenceImageAttacher == null
                ? ReferenceImageLookup.notRequested()
                : facts != null && "UNKNOWN_CROP".equals(facts.status())
                ? ReferenceImageLookup.notRequested()
                : referenceImageAttacher.forChatResult(request == null ? null : request.getMessage(),
                facts == null ? null : facts.crop(), facts == null ? null : facts.growthStage());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", lookup.images().size());
        payload.put("items", lookup.images());
        payload.put("mediaNeed", mediaNeed.name());
        payload.put("status", lookup.status());
        payload.put("matchedRows", lookup.matchedRows());
        payload.put("acceptedRows", lookup.acceptedRows());
        String message = referenceImageMessage(lookup.status(), mediaNeed);
        if (message != null) payload.put("message", message);
        send(emitter, "reference.images", payload);
        log.info("COPILOT_REFERENCE_LOOKUP traceId={} intent={} mediaNeed={} status={} matchedRows={} acceptedRows={} returned={}",
                routed == null ? null : routed.getTraceId(), routed == null || routed.getIntent() == null ? null : routed.getIntent().name(),
                mediaNeed.name(), lookup.status(), lookup.matchedRows(), lookup.acceptedRows(), lookup.images().size());
        return new ReferenceImageResponse(lookup, mediaNeed, message);
    }

    private String referenceImageMessage(String status, CopilotMediaNeed mediaNeed) {
        if (mediaNeed == CopilotMediaNeed.NONE) return null;
        if ("NO_MATCH".equals(status)) return "当前知识库未找到与该问题匹配的参考图片。";
        if ("UNAVAILABLE".equals(status)) return "参考图谱当前不可用，未返回参考图片。";
        return null;
    }

    private boolean asksVisualEvidence(String message) {
        String value = safe(message);
        return contains(value, "现场图片", "图片", "图像", "照片", "拍照", "可见异常", "叶片", "病斑");
    }

    private boolean isCasualGreeting(String message) {
        String value = safe(message).toLowerCase(Locale.ROOT);
        return value.matches("^(你好|您好|嗨|哈喽|hello|hi|谢谢|多谢|辛苦了|在吗|你是谁)[！!。,.？? ]*$");
    }

    private boolean isDirectTaskRequest(String message) {
        String value = safe(message).toLowerCase(Locale.ROOT);
        boolean requested = (contains(value, "创建任务", "创建农事任务", "生成任务", "新建任务", "创建浇水任务", "创建施肥任务",
                "安排农事任务", "安排浇水任务", "安排施肥任务", "create task", "schedule task")
                || value.matches(".*(?:创建|生成|新建|安排).{0,12}任务.*")
                || isAgentPlanningRequest(value));
        return requested && !contains(value, "不要创建", "不创建", "无需创建", "只分析", "仅分析", "do not create", "without executing");
    }

    private boolean isAgentPlanningRequest(String value) {
        boolean planningObject = contains(value, "农事计划", "农事安排", "作业计划", "管理计划", "采收计划", "一周计划", "周计划", "未来一周", "本周计划", "farm plan", "weekly plan");
        boolean directive = contains(value, "帮我制定", "请制定", "给我制定", "制定一份", "帮我生成", "请生成", "给我生成", "生成一份",
                "帮我规划", "请规划", "给我规划", "规划一份", "create a plan", "make a plan");
        return planningObject && directive;
    }

    private String visualEvidenceClarification(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts) {
        String fieldId = routed == null || routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFieldId();
        String crop = facts == null ? null : facts.cropLabel();
        String known = (fieldId == null || fieldId.isBlank()) && (crop == null || crop.isBlank())
                ? "我已识别这是一次现场异常分析"
                : "我已知道你咨询的是" + (fieldId == null || fieldId.isBlank() ? "当前地块" : fieldId)
                + (crop == null || crop.isBlank() ? "" : "的" + crop) + "";
        String query = safe(request == null ? null : request.getMessage());
        String missingImage = query.contains("叶片") ? "叶片正反面照片" : query.contains("果") ? "果实和受影响部位照片" : "现场图片";
        String missingTime = contains(query, "发黄", "变色", "病斑", "腐烂", "掉果", "异常") ? "，并说明症状从什么时候开始" : "";
        return known + "。目前还缺少" + missingImage + missingTime + "；我不会在证据不足时猜测病因或直接给出用药结论。";
    }

    public static boolean isReadOnlyAnalysis(String message) {
        String value = message == null ? "" : message.trim();
        return List.of("不执行", "不要执行", "仅分析", "只读分析", "只诊断", "仅诊断", "只提供分析", "仅提供分析", "先分析再决定", "不要启动工作流")
                    .stream().anyMatch(value::contains)
                || java.util.regex.Pattern.compile("(?:不(?:要|用|必)?|无需|别)\\s*(?:创建|生成|新建|安排|执行)\\s*(?:任何|任何的|一条|一个|相关|新的|农事|现场|生产|实际|执行|作业|诊断|管理)*\\s*(?:任务|工单|工作流|作业)").matcher(value).find();
    }

    private CopilotFacts initialAnswerFacts(CopilotChatRequest request, CopilotRouteResult routed, Long userId) {
        if (!usesPi() || phenologyGuidance.asksTemporalPhenology(request.getMessage()) || CopilotMediaPolicy.decide(request.getMessage(),
                routed.getIntent(), !trustedImageUrls(request.getImageUrls()).isEmpty()) != CopilotMediaNeed.NONE) {
            return fieldContext.resolve(request, routed, userId);
        }
        return fieldContext.scopeOnly(request, routed, userId);
    }

    private CopilotKnowledgeBundle retrieveKnowledge(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts, Long userId) {
        return retrieveKnowledge(request, routed, facts, userId, null, null);
    }

    private CopilotKnowledgeBundle retrieveKnowledge(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts, Long userId,
                                              String stage, Integer topK) {
        if (knowledgeRetriever == null) return new CopilotKnowledgeBundle("UNAVAILABLE", List.of());
        // Without a crop, ask the retriever for only untagged public chunks;
        // this keeps a generic question from mixing crop-specific evidence.
        boolean untaggedOnly = facts == null || facts.crop() == null || facts.crop().isBlank();
        String crop = facts == null ? null : facts.crop();
        String growthStage = stage == null ? phenologyGuidance.knowledgeStageFor(request == null ? null : request.getMessage(), facts) : stage;
        DiagnosisAnalyzeRequest query = new DiagnosisAnalyzeRequest();
        CopilotPageContext page = request.getPageContext();
        String fieldId = routed == null || routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFieldId();
        if (fieldId == null || fieldId.isBlank()) fieldId = page == null ? null : page.getFieldId();
        String farmId = routed == null || routed.getResolvedContext() == null ? null : routed.getResolvedContext().getFarmId();
        if (farmId == null || farmId.isBlank()) farmId = page == null ? null : page.getFarmId();
        query.setFieldId(fieldId == null || fieldId.isBlank() ? null : fieldId);
        query.setFarmId(parseInt(farmId));
        Integer guidanceMonth = requestedGuidanceMonth(request);
        query.setQuery(guidanceMonth == null ? request.getMessage() : request.getMessage() + "（目标月份：" + guidanceMonth + "月）");
        query.setCrop(crop);
        query.setGrowthStage(growthStage);
        query.setTopK(topK);
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
        final com.mei.zhgy.service.rag.KnowledgeEvidenceRetriever.RetrievalResult result;
        try {
            result = knowledgeRetriever.retrieveWithStatus(query, userId);
        } catch (RuntimeException failure) {
            // Knowledge enrichment must not turn a normal Copilot question
            // into a total outage. The response model can still provide the
            // configured, clearly labelled L3 general-agriculture fallback.
            log.warn("COPILOT_KNOWLEDGE_DEGRADED userId={} cropBound={}", userId, crop != null && !crop.isBlank(), failure);
            return new CopilotKnowledgeBundle("UNAVAILABLE", List.of());
        }
        List<Map<String, Object>> output = new ArrayList<>();
        if (result == null || result.getEvidence() == null) return new CopilotKnowledgeBundle("UNAVAILABLE", output);
        result.getEvidence().forEach(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", item.getTitle());
            String excerpt = item.getPreview() == null ? null : item.getPreview().getTextSnippet();
            value.put("content", safe(excerpt).isBlank() ? item.getSummary() : excerpt);
            value.put("score", item.getSource() != null && item.getSource().getRerankScore() != null
                    ? item.getSource().getRerankScore() : item.getScore());
            if (item.getSource() != null) {
                value.put("documentName", item.getSource().getDocument());
                value.put("source", item.getSource().getName() == null ? item.getSource().getDocument() : item.getSource().getName());
                value.put("sourceId", item.getSource().getSourceId());
                value.put("documentId", item.getSource().getDocumentId());
                value.put("versionId", item.getSource().getVersionId());
                value.put("contentHash", item.getSource().getContentHash());
            }
            value.put("chunkId", item.getId());
            output.add(value);
        });
        return new CopilotKnowledgeBundle(result.getStatus(), output);
    }

    private String streamModelAnswer(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts,
                                     CopilotKnowledgeBundle knowledge, Long userId, SseEmitter emitter) {
        return streamModelAnswer(request, routed, facts, knowledge, userId, emitter, null);
    }

    private String streamModelAnswer(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts,
                                     CopilotKnowledgeBundle knowledge, Long userId, SseEmitter emitter, String commandReport) {
        return streamModelAnswer(request, routed, facts, knowledge, userId, emitter, commandReport, new PiTurn());
    }

    private String streamModelAnswer(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts,
                                     CopilotKnowledgeBundle knowledge, Long userId, SseEmitter emitter, String commandReport, PiTurn turn) {
        boolean compositePhenologyGuidance = phenologyGuidance.isCompositePhenologyGuidance(request.getMessage());
        String verifiedPhenologyAnswer = phenologyGuidance.temporalPhenologyAnswer(request.getMessage(), facts);
        String deterministicAnswer = compositePhenologyGuidance && !"UNKNOWN_CROP".equals(facts.status())
                ? null : verifiedPhenologyAnswer;
        if (deterministicAnswer != null && commandReport == null) {
            streamDelta(emitter, deterministicAnswer);
            if (usesPi()) {
                turn.privateFacts = facts.ready();
                turn.publicFacts = "PUBLIC_GENERIC".equals(facts.status()) || "FARM_CONTEXT".equals(facts.status());
                emitPiSource(emitter, turn);
            }
            return deterministicAnswer;
        }
        if (usesPi()) {
            String answer = runPiAnswer(request, routed, facts, userId, emitter, commandReport, turn, false);
            if (!allowUngrounded && commandReport == null) {
                if (!turn.grounded()) answer = "当前没有取得可验证的地块或农业知识依据。请明确作物、地块或需要查询的数据。";
                streamDelta(emitter, answer);
            }
            emitPiSource(emitter, turn);
            return answer;
        }
        boolean privateFactsAvailable = facts != null && "READY".equals(facts.status());
        List<Map<String, Object>> monthlyGuidance = readMonthlyGuidance(request, routed, userId);
        if (!allowUngrounded && !privateFactsAvailable && monthlyGuidance.isEmpty() && (knowledge == null || knowledge.items().isEmpty()) && commandReport == null) {
            String blocked = "当前没有可验证的地块或公共知识依据；通用模型兜底已关闭，暂不生成农业结论。请先选择地块，或明确作物并补充问题。";
            streamDelta(emitter, blocked);
            return blocked;
        }
        if (modelGateway == null && !usesPi()) {
            String unavailable = "当前 Copilot 模型暂不可用；知识检索结果已保留，请稍后重试。";
            streamDelta(emitter, unavailable);
            return unavailable;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        String dateHint = request.getPageContext() == null ? null : request.getPageContext().getReferenceDate();
        if (dateHint != null && dateHint.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try { context.put("用户选择的参考日期", LocalDate.parse(dateHint).toString()); }
            catch (java.time.format.DateTimeParseException ignored) { /* Invalid client date hints do not enter context. */ }
        }
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
        if (!monthlyGuidance.isEmpty()) context.put("monthlyGuidance", monthlyGuidance);
        UiStream ui = requestUiStreams.get(request);
        if (ui != null && ui.weather != null) context.put("currentWeather", ui.weather);
        if (compositePhenologyGuidance && verifiedPhenologyAnswer != null) {
            context.put("verifiedPhenologyAnswer", verifiedPhenologyAnswer);
            context.put("guidanceGrowthStage", phenologyGuidance.knowledgeStageFor(request.getMessage(), facts));
        }
        context.put("maxOutputTokens", Math.max(256, copilotMaxOutputTokens));
        Map<String, Object> source = answerSource(routed, facts, knowledge, request.getMessage());
        context.put("answerSource", source.get("layer"));
        context.put("answerSourceWarning", source.get("warning"));
        context.put("allowUngrounded", allowUngrounded);
        // Interactive chat must reach first-token latency quickly and should
        // not enter the large random answer pool. Grounding remains an
        // independent safety policy carried in the prompt/context.
        context.put("modelPolicy", "force_fast");
        context.put("groundingPolicy", allowUngrounded ? "allow_ungrounded" : "grounded_only");
        boolean privateFactQuestion = asksPrivateFacts(request.getMessage());
        String sourceInstruction = privateFactQuestion
                ? "这是涉及用户地块、数值、历史或检测的私有事实问题；缺少对应私有事实时绝不编造，只能明确指出缺口；若补充一般说明，必须标明‘以下为通用建议，未结合你的地块数据，仅供参考’。"
                : allowUngrounded
                ? "若没有私有地块事实或知识库命中，允许使用模型通用农业知识，但必须在回答开头明确写‘以下为通用建议，未结合你的地块数据，仅供参考’。"
                : "只使用当前地块事实或知识库依据；不能把模型记忆当作事实。";
        if (requestedGuidanceMonth(request) != null) {
            sourceInstruction += " 根据用户指定月份综合已登记的月度指导、地块事实和知识库证据。若月度记录缺失，说明缺口并使用已取得的知识依据。不要编造农情、月度登记或排期。";
        }
        if (context.get("fieldId") == null) {
            sourceInstruction += " 当前没有选中地块。农场作物信息只用于确定适用作物，按日期计算的阶段不代表任何地块的登记阶段。不要随意汇报地块的登记值、冲突或未来天数。";
        }
        sourceInstruction += " 最终答复使用用户可读名称，不展示内部字段名、状态枚举或农场编号。若已有本轮结构化天气观测或预报，准确引用数据及可用状态。";
        if (ui != null && ui.modelAnalysis) sourceInstruction += " 界面已显示核心结论和结构化卡片。直接解释业务数据对本问题的意义，说明关键缺口或下一步，避免复述卡片中的整套字段和预报表。";
        String phenologyInstruction = compositePhenologyGuidance
                ? "这是一个复合问题，必须同时回答两个部分：先给出已验证的当前/目标物候期结论，再根据目标物候期给出具体农事指导。不要只回答物候期名称；农事指导只使用农业知识依据，按肥水、病虫监测、农事操作或注意事项整理2到5条，明确适用阶段，不能把建议写成新的物候期。"
                : "事实优先级规则：当用户询问‘现在/当前/目前’的物候期、生育阶段或该做什么时，优先使用【当前日期】和【当前物候期】直接给出唯一结论，不要罗列全部物候期；上一个/下一个物候期必须使用给定的相邻阶段字段，不要让模型自行接词；没有地块时可以使用明确作物的通用历，但必须说明是通用历推算；日期、作物和依据都不足时，明确指出缺口。";
        String prompt = "用户问题：" + safe(request.getMessage())
                + "\n当前上下文：" + context
                + "\n最近对话（仅用于承接指代，不得覆盖当前页面事实）：" + recentConversation(request, userId)
                + "\n农业知识依据：" + knowledge.items()
                + "\n知识库状态：" + knowledge.status()
                + "\n物候事实来源：" + facts.sources()
                + "\n答案来源策略：优先使用当前地块私有事实，其次使用按作物过滤的公共农业知识，再按开关决定是否使用模型通用知识。当前答案层为“" + source.get("layer") + "”。"
                + "\n" + phenologyInstruction
                + "\n物候术语规则：物候期只能使用给定的有序阶段名；修剪、施肥、疏果、打药、清园等是农事操作，不是物候期；禁止自造阶段名，也禁止把其他作物的阶段套入当前作物。"
                + "\n" + sourceInstruction
                + "\n始终不要虚构地块温度、湿度、土壤、历史用药、诊断或产量；知识库不可用时不要跨作物猜测。请直接用中文回答，不要输出隐藏思维链或 <think> 标签。";
        StringBuilder answer = new StringBuilder();
        if (ui != null) ui.telemetry.mode("MODEL_GATEWAY");
        ModelCallResult result = modelGateway.stream(ModelCallRequest.builder()
                .requestId("copilot-chat-" + java.util.UUID.randomUUID())
                .taskType(AiTaskType.COPILOT)
                .systemPrompt("你是 FarMap 农业 Copilot，只输出用户可读的最终答复。")
                .userPrompt(prompt).structuredOutput(false).context(context).build(), delta -> {
                    answer.append(delta);
                    if (ui != null && !delta.isEmpty()) ui.telemetry.token();
                    streamDelta(emitter, delta);
                });
        if (ui != null) {
            var counts = new LinkedHashMap<String, Long>();
            if (result.getUsage() != null) {
                counts.put("inputTokens", result.getUsage().getInputTokens());
                counts.put("outputTokens", result.getUsage().getOutputTokens());
                counts.put("cacheReadTokens", result.getUsage().getCacheReadTokens());
            }
            ui.telemetry.model(result.getSelectedModel(), counts);
            ui.telemetry.source(source.get("layer"));
        }
        String finalAnswer = finalAnswer(result.getContent());
        if (answer.isEmpty()) streamDelta(emitter, finalAnswer);
        return finalAnswer;
    }

    private String deterministicPhenologyAnswer(String message, CopilotFacts facts) {
        if (phenologyGuidance.isCompositePhenologyGuidance(message)
                && (facts == null || !"UNKNOWN_CROP".equals(facts.status()))) return null;
        return phenologyGuidance.temporalPhenologyAnswer(message, facts);
    }

    private boolean usesPi() {
        return piRuntime != null && piRuntime.isEnabled();
    }

    private String runPiAnswer(CopilotChatRequest request, CopilotRouteResult routed, CopilotFacts facts, Long userId,
                               SseEmitter emitter, String commandReport, PiTurn turn, boolean drafting) {
        CopilotConversationView view = request.getConversationId() == null || conversations == null ? null
                : conversations.runtimeView(request.getConversationId(), userId);
        if (view != null && routed.getContextVersion() != null && routed.getContextVersion() != view.getContextVersion()) {
            throw new CopilotConversationException("CONVERSATION_CONFLICT", "会话上下文已更新。", 409);
        }
        CopilotStableContext scope = (routed.getResolutionStatus() == null || routed.getResolutionStatus() == CopilotResolutionStatus.RESOLVED)
                && routed.getResolvedContext() != null ? routed.getResolvedContext().copy() : new CopilotStableContext();
        List<PiAgentRuntime.ReadTool> tools = liveTools(request, scope, userId, drafting, turn, emitter != null);
        if (agriculturalTools != null) tools.addAll(agriculturalTools.tools(scope, userId, facts.cropLabel(), request.getMessage(),
                trustedImageUrls(request.getImageUrls()), emitter != null, turn.evidence));
        if (memoryProposals != null && emitter != null && routed.getTurnId() != null) {
            tools.add(memoryProposalTool(request, routed.getTurnId(), scope, userId));
        }
        Integer activeFarm = parseInt(scope.getFarmId());
        tools = tools.stream().map(tool -> CopilotPiToolEvidence.bind(tool, turn.evidence, activeFarm)).toList();
        AgriculturalAgentHarness.TurnContext aligned = harness.history(view == null ? List.of() : view.getRecentTurns(),
                routed.getTurnId(), routed.getTraceId());
        String message = drafting && routed.getRequestedAction() != null
                ? safe(routed.getRequestedAction()) : safe(request.getMessage());
        if (commandReport != null) {
            message = bound(message, 4000) + "\n页面回传的命令结果：\n" + commandReport;
        }
        String environment = harness.environment(LocalDate.now(COPILOT_ZONE).toString(), scope, facts.cropLabel(),
                facts.growthStage(), orchardProfile(scope, userId), trustedImageUrls(request.getImageUrls()), request.getMessage(),
                allowUngrounded, drafting, commandReport != null);
        boolean liveChat = view != null && emitter != null;
        String session = liveChat ? piSession(userId, view.getConversationId()) : userId + ":request:" + java.util.UUID.randomUUID();
        String scopeKey = java.util.stream.Stream.of(scope.getFarmId(), scope.getFieldId(), facts.crop(), facts.growthStage())
                .map(value -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        safe(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("."));
        UiStream ui = requestUiStreams.get(request);
        if (ui != null) ui.telemetry.mode(drafting ? "PI_DRAFT" : "PI_CHAT");
        boolean liveText = !drafting && (allowUngrounded || commandReport != null);
        var groundingKey = liveChat ? continuationKey(view, userId, routed.getTurnId()) : null;
        PiAgentRuntime.Result result = piRuntime.run(session, liveChat ? view.getContextVersion() : null, scopeKey,
                message, AgriculturalAgentHarness.SYSTEM_PROMPT, environment, liveChat ? routed.getTurnId() : null,
                liveChat ? aligned.lastCompletedTurnId() : null, aligned.history(), tools,
                emitter == null ? null : delta -> {
                    if (ui != null && !delta.isEmpty()) ui.telemetry.token();
                    if (liveText) streamDelta(emitter, delta);
                },
                emitter == null ? null : event -> streamPiEvent(emitter, event),
                observation -> {
                    observePiTool(emitter, request.getMessage(), turn, observation);
                    continuationGrounding.remember(groundingKey, turn.grounding());
                });
        if ("aborted".equals(result.status())) {
            continuationGrounding.remember(groundingKey, turn.grounding());
            throw new PiTurnAborted();
        }
        continuationGrounding.clear(groundingKey);
        return finalAnswer(result.text());
    }

    private CopilotContinuationGrounding.Key continuationKey(CopilotConversationView view, Long userId, String turnId) {
        return turnId == null ? null : new CopilotContinuationGrounding.Key(
                userId, view.getConversationId(), view.getContextVersion(), turnId);
    }

    private Map<String, Object> orchardProfile(CopilotStableContext scope, Long actor) {
        if (memoryProposals == null || (scope.getFarmId() == null && scope.getFieldId() == null)) return Map.of();
        try { return memoryProposals.profileMap(actor, scope.getFarmId(), scope.getFieldId()); }
        catch (RuntimeException failure) {
            log.debug("FarMap orchard profile unavailable: {}", failure.getClass().getSimpleName());
            return Map.of();
        }
    }

    private PiAgentRuntime.ReadTool memoryProposalTool(CopilotChatRequest request, String turnId, CopilotStableContext active, Long actor) {
        return AgriculturalToolRegistry.memoryProposal(
                args -> {
                    if (!isDisclosedProfileValue(request.getMessage(), args.text("value"))) {
                        return ToolReply.error("PROFILE_VALUE_NOT_DISCLOSED");
                    }
                    ToolScope scope = authorizeToolScope(active, null, null, "FIELD".equals(args.text("scope")), actor);
                    if (scope.farmId() == null || ("FIELD".equals(args.text("scope")) && scope.fieldId() == null)) {
                        return ToolReply.error("PROFILE_SCOPE_REQUIRED");
                    }
                    var proposal = memoryProposals.propose(actor, scope.farmId().toString(), scope.fieldId(),
                            args.text("key"), args.text("value"), request.getConversationId(), turnId);
                    return new ToolReply(Map.of("status", "PENDING_CONFIRMATION", "proposalId", proposal.proposalId(), "uiBlockEmitted", true),
                            Map.of("componentType", "MemoryProposalCard", "title", "确认保存果园资料", "status", "needs-input",
                                    "blockKey", proposal.proposalId(), "data", Map.of("memoryProposal", proposal)), false);
                });
    }

    static boolean isDisclosedProfileValue(String question, String value) {
        String original = CopilotQueryNormalizer.compactForMatching(question);
        String candidate = CopilotQueryNormalizer.compactForMatching(value);
        if (candidate.isEmpty()) return false;
        if (!candidate.matches("\\d+(?:\\.\\d+)?")) return original.contains(candidate);
        return java.util.regex.Pattern.compile("(?<![0-9.])" + java.util.regex.Pattern.quote(candidate) + "(?![0-9.])")
                .matcher(original).find();
    }

    private List<PiAgentRuntime.ReadTool> liveTools(CopilotChatRequest request, CopilotStableContext active, Long actor,
                                                    boolean drafting, PiTurn turn, boolean canRender) {
        var services = new AgriculturalToolRegistry.CoreServices(farmFieldService, currentWeatherService,
                agriculturalWeather, memories, maturityService, agentRuntime);
        var reads = new AgriculturalToolRegistry.CoreReads(scope -> readFieldFacts(request, scope, actor),
                (scope, args) -> searchKnowledge(request, scope, args, actor, turn),
                monthlyGuidanceService == null ? null : (farm, month) -> readMonthlyGuidance(farm, actor, month),
                scope -> orchardProfile(scope, actor), args -> readPreviousAnalysis(request, active, actor, args),
                request.getPageContext() == null ? null : request.getPageContext().getAnalysisRunId(), turn.evidence);
        return AgriculturalToolRegistry.coreTools(securityGuard, active, actor, agriculturalTools != null,
                drafting, canRender, services, reads);
    }

    private ToolReply searchKnowledge(CopilotChatRequest request, ToolScope scope, CopilotToolArguments args,
                                       Long actor, PiTurn turn) {
        String term = args.text("query");
        if (args.text("category") != null) term += "（知识类别：" + args.text("category") + "）";
        CopilotChatRequest query = toolQuery(request, term);
        CopilotRouteResult route = toolRoute(scope);
        CopilotFacts facts = fieldContext.resolve(query, route, actor);
        String requestedCrop = args.text("crop");
        if (requestedCrop != null) {
            if (scope.fieldId() != null && facts.crop() == null) return ToolReply.error("UNKNOWN_CROP");
            LocalDate today = LocalDate.now(COPILOT_ZONE);
            CopilotFacts requested = fieldContext.publicFacts(requestedCrop, today, today.format(COPILOT_DATE_FORMAT));
            if (requested == null) return ToolReply.error("UNKNOWN_CROP");
            if (scope.fieldId() != null && facts.crop() != null && !facts.crop().equals(requested.crop())) {
                return ToolReply.error("CROP_SCOPE_MISMATCH");
            }
            if (scope.fieldId() == null) facts = requested;
        }
        String stage = args.text("growthStage");
        if (stage != null) {
            var resolved = phenologyService.resolveStage(facts.crop(), stage);
            if (resolved == null) {
                var calendar = phenologyService.calendarForCrop(facts.crop());
                return invalid("growthStage", args.get("growthStage"),
                        calendar == null ? List.of() : calendar.stages().stream().map(value -> value.code()).toList(),
                        "请改用当前作物的标准物候阶段代码重试");
            }
            stage = resolved.code();
        }
        CopilotKnowledgeBundle hits = retrieveKnowledge(query, route, facts, actor, stage, args.integer("topK"));
        if (agronomyEvidence != null && args.text("productId") != null) {
            LocalDate today = LocalDate.now(COPILOT_ZONE);
            var verified = agronomyEvidence.spray(scope.farmId(), scope.fieldId(), facts.crop(), args.text("productId"), today);
            var registered = verified.label();
            if (registered != null) {
                List<Map<String, Object>> items = new ArrayList<>();
                var citation = new LinkedHashMap<String, Object>();
                citation.put("chunkId", "label-" + registered.productId());
                citation.put("title", "登记标签 " + registered.productId());
                citation.put("source", registered.provenance().sourceReference());
                citation.put("content", "该登记标签的采收安全间隔为 " + registered.preHarvestIntervalDays()
                        + " 天。适用性须同时核对登记作物、地块认证和计划日期。");
                citation.put("phiDays", registered.preHarvestIntervalDays());
                citation.put("labelVersion", registered.provenance().documentVersion());
                Long remaining = verified.expectedHarvestDate() == null ? null
                        : java.time.temporal.ChronoUnit.DAYS.between(today, verified.expectedHarvestDate());
                citation.put("daysUntilHarvest", remaining);
                boolean blocked = remaining != null && remaining < registered.preHarvestIntervalDays();
                citation.put("gateStatus", blocked ? "BLOCKED_BY_PHI" : "REQUIRES_STAGE_AND_CERTIFICATION_CHECK");
                if (blocked) citation.put("content", "BLOCKED_BY_PHI：当前距离登记预期采收日不足标签安全间隔期，不得推荐此次施用。");
                items.add(citation);
                items.addAll(hits.items());
                hits = new CopilotKnowledgeBundle("UNAVAILABLE".equals(hits.status()) ? "DEGRADED" : "READY", items);
            }
        }
        if (structuredAgronomy != null && "plum".equals(facts.crop())) {
            var assessment = structuredAgronomy.assessPlumDefense(
                    new com.mei.zhgy.service.knowledge.PlumDefenseKnowledgeService.AssessmentRequest(
                            args.text("query"), scope.fieldId() == null ? null : facts.growthStage(),
                            LocalDate.now(COPILOT_ZONE), scope.farmId(), scope.fieldId(), args.text("productId")));
            if (!assessment.rows().isEmpty()) {
                List<Map<String, Object>> items = new ArrayList<>();
                var rows = new ArrayList<>(assessment.rows());
                rows.sort(java.util.Comparator.comparing(row ->
                        row.evidenceScope() != com.mei.zhgy.service.knowledge.PlumDefenseKnowledgeService.EvidenceScope.VERIFIED_PRODUCT_LABEL));
                for (var row : rows) {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("chunkId", row.ruleId());
                    item.put("title", row.targetName() + " · " + row.kind().name());
                    item.put("source", row.sourceRef());
                    item.put("content", row.guidance() + " " + String.join(" ", row.gateReasons()));
                    item.put("structuredDefense", row);
                    item.put("gateStatus", row.gateStatus().name());
                    items.add(item);
                }
                items.addAll(hits.items());
                hits = new CopilotKnowledgeBundle("UNAVAILABLE".equals(hits.status()) ? "DEGRADED" : "READY", items);
            }
        }
        Integer limit = args.integer("topK");
        if (limit != null && hits.items().size() > limit) hits = new CopilotKnowledgeBundle(hits.status(), hits.items().subList(0, limit));
        return ToolReply.result(turn.cite(hits, scope));
    }

    private ToolReply readPreviousAnalysis(CopilotChatRequest request, CopilotStableContext active, Long actor,
                                           CopilotToolArguments args) {
        ToolScope scope = authorizeToolScope(active, null, null, true, actor);
        String analysisId = args.text("analysisRunId"), diagnosisId = args.text("diagnosisId"), workflowId = args.text("workflowRunId");
        boolean useLatest = analysisId == null && diagnosisId == null && workflowId == null;
        if (analysisId == null && diagnosisId == null && workflowId == null) {
            analysisId = request.getPageContext() == null ? null : request.getPageContext().getAnalysisRunId();
            diagnosisId = active.getDiagnosisId();
            workflowId = active.getRunId();
        }
        Map<String, Object> results = new LinkedHashMap<>();
        if (analysisId != null && maturityService != null) {
            var report = maturityService.run(analysisId, actor);
            if (!sameToolFarm(scope, report.get("farmId"))) return ToolReply.error("SCOPE_UNAVAILABLE");
            var summary = new LinkedHashMap<String, Object>();
            for (String key : List.of("runId", "farmId", "kind", "mode", "observedAt", "status", "summary")) {
                if (report.get(key) != null) summary.put(key, bound(String.valueOf(report.get(key)), 600));
            }
            results.put("visual", summary);
        }
        if (diagnosisId != null && diagnosticRecords != null) {
            var record = diagnosticRecords.findRecord(diagnosisId, actor).orElse(null);
            if (record == null || !sameToolFarm(scope, record.getFarmId())) return ToolReply.error("SCOPE_UNAVAILABLE");
            var analysis = diagnosticRecords.find(diagnosisId, actor).orElse(null);
            if (analysis != null) results.put("diagnosis", Map.of("diagnosisId", diagnosisId, "summary", CopilotDiagnosisPresentation.summary(analysis)));
        }
        if (workflowId != null && agentRuntime != null) {
            var run = agentRuntime.require(workflowId, actor);
            if (!sameToolFarm(scope, run.getContext() == null ? null : run.getContext().getFarmId())) return ToolReply.error("SCOPE_UNAVAILABLE");
            results.put("workflow", Map.of("runId", run.getRunId(), "status", run.getStatus(), "approvalState", run.getApprovalState()));
        }
        if (useLatest && scope.farmId() != null) {
            if (diagnosisId == null && monthlyGuidanceService != null) {
                var latest = monthlyGuidanceService.diagnoses(scope.farmId(), actor, scope.fieldId(), "COMPLETED", 1);
                if ("READY".equals(latest.status()) && !latest.items().isEmpty()) results.put("diagnosis", latest.items().get(0));
            }
            if (analysisId == null && maturityService != null) {
                var reports = maturityService.runs(scope.farmId(), actor);
                reports.stream().filter(report -> scope.fieldId() == null || reportSupportsField(report, scope.fieldId())).findFirst()
                        .ifPresent(report -> results.put("visual", Map.of("runId", report.get("runId"), "summary", safe(mapText(report, "summary")))));
            }
        }
        if (results.isEmpty()) return ToolReply.error("RUN_REQUIRED");
        results.put("status", "READY");
        if (scope.farmId() != null) results.put("farmId", scope.farmId());
        return ToolReply.result(results);
    }

    private boolean sameToolFarm(ToolScope scope, Object farmId) {
        return scope.farmId() == null || scope.farmId().toString().equals(String.valueOf(farmId));
    }

    private ToolScope authorizeToolScope(CopilotStableContext active, Integer farm, String field,
                                         boolean useSelectedField, Long actor) {
        return securityGuard.authorize(active, farm, field, useSelectedField, actor);
    }

    private CopilotRouteResult toolRoute(ToolScope scope) {
        return CopilotRouteResult.builder().resolvedContext(CopilotStableContext.builder()
                .farmId(scope.farmId() == null ? null : scope.farmId().toString()).fieldId(scope.fieldId()).build()).build();
    }

    private CopilotChatRequest toolQuery(CopilotChatRequest original, String query) {
        CopilotChatRequest request = new CopilotChatRequest();
        request.setMessage(query);
        request.setConversationId(original.getConversationId());
        return request;
    }

    private Map<String, Object> readFieldFacts(CopilotChatRequest original, ToolScope scope, Long actor) {
        Map<String, Object> result = new LinkedHashMap<>();
        CopilotFacts facts = fieldContext.resolve(toolQuery(original, original.getMessage()), toolRoute(scope), actor);
        putCopilotFacts(result, facts);
        result.put("farmId", scope.farmId());
        result.put("fieldId", scope.fieldId());
        if (scope.fieldId() != null) {
            FarmField field = farmFieldService.get(scope.fieldId(), actor);
            result.put("fieldName", field.getName());
            result.put("area", field.getArea());
            result.put("expectedRegisteredStage", field.getGrowthStage());
            var fromFacts = facts.sources();
            boolean heatLoaded = fromFacts != null && fromFacts.containsKey("accumulatedTemperatureStatus");
            var heat = heatLoaded ? Map.<String, Object>of() : farmFieldService.latestAccumulatedTemperature(scope.farmId(), actor);
            Double sum = heatLoaded ? CopilotPiToolEvidence.validNumber(fromFacts.get("accumulatedTemperature"))
                    : heat != null && "READY".equals(heat.get("status")) && heat.get("accumulatedTemp") instanceof Number number
                    && Double.isFinite(number.doubleValue()) ? number.doubleValue() : null;
            LocalDate measuredOn = null;
            String thermalDate = heatLoaded ? fromFacts.get("temperatureMeasuredOn") : heat == null ? null : mapText(heat, "date");
            if (sum != null && thermalDate != null) try { measuredOn = LocalDate.parse(thermalDate); }
            catch (java.time.format.DateTimeParseException ignored) { }
            result.put("accumulatedTemperature", sum);
            result.put("temperatureMeasuredOn", measuredOn == null ? null : measuredOn.toString());
            if (scope.farmId() != null) result.put("accumulatedTemperatureHistory",
                    farmFieldService.accumulatedTemperatureHistory(scope.farmId(), actor));
            if (agronomyRules != null && facts.ready()) {
                LocalDate today = LocalDate.now(COPILOT_ZONE);
                var inputs = new PlumAgronomyEvidenceCatalog.CorrectionInputs(List.of(), List.of());
                if (agronomyEvidence != null && agronomyEvidence.hasField(scope.farmId(), scope.fieldId(), facts.crop(), today)) {
                    inputs = agronomyEvidence.correction(scope.farmId(), scope.fieldId(), facts.crop(), today, sum, measuredOn);
                    result.put("thermalTarget", agronomyEvidence.thermalTarget(
                            scope.farmId(), scope.fieldId(), facts.crop(), today, facts.nextGrowthStage()));
                }
                var correction = agronomyRules.correctPhenology(new PlumAgronomyRuleEngine.PhenologyCorrectionRequest(
                        facts.crop(), today, facts.registeredGrowthStage(),
                        facts.dateDerivedGrowthStage(), new PlumAgronomyRuleEngine.Provenance(
                        PlumAgronomyRuleEngine.SourceKind.ORCHARD_RECORD, "FarMap:" + scope.fieldId(),
                        "farm-field-v1", Instant.now(), false, true), inputs.observations(), inputs.windows()));
                result.put("phenologyCorrection", correction);
                if (!correction.requiresReview() && correction.effectiveStageCode() != null
                        && !java.util.Objects.equals(facts.registeredGrowthStage(), correction.effectiveStageCode())) {
                    result.put("calibrationTarget", correction.effectiveStageCode());
                    result.put("stageMismatch", true);
                }
                if (correction.changed() && correction.effectiveStageCode() != null) {
                    String effectiveDate = facts.sources() == null ? null : facts.sources().get("phenologyEffectiveDate");
                    var corrected = phenologyService.calculate(facts.crop(), effectiveDate == null ? today : LocalDate.parse(effectiveDate),
                            correction.effectiveStageCode());
                    result.put("growthStage", corrected.currentCode());
                    result.put("currentGrowthStage", corrected.currentCode());
                    result.put("growthStageLabel", corrected.currentName());
                    result.put("currentGrowthStageLabel", corrected.currentName());
                    result.put("previousGrowthStage", corrected.previousCode());
                    result.put("previousGrowthStageLabel", corrected.previousName());
                    result.put("nextGrowthStage", corrected.nextCode());
                    result.put("nextGrowthStageLabel", corrected.nextName());
                    result.put("daysUntilNextPhenologyStage", corrected.daysUntilNext());
                } else if (correction.requiresReview() && correction.effectiveStageCode() == null) {
                    result.put("currentGrowthStage", null);
                    result.put("growthStage", null);
                    result.put("currentGrowthStageLabel", "待现场复核");
                    result.put("growthStageLabel", "待现场复核");
                    result.put("previousGrowthStage", null);
                    result.put("previousGrowthStageLabel", null);
                    result.put("nextGrowthStage", null);
                    result.put("nextGrowthStageLabel", null);
                    result.put("stageMismatch", true);
                }
            }
        }
        return result;
    }

    public record PhenologyCalibration(String expectedRegisteredStage, String targetStage) {}

    public FarmField calibrateField(String fieldId, PhenologyCalibration requested, Long actor) {
        if (requested == null || requested.targetStage() == null || farmFieldService == null) {
            throw new CopilotConversationException("PHENOLOGY_REVIEW_REQUIRED", "请先查看物候校准依据。", 422);
        }
        FarmField field = farmFieldService.get(fieldId, actor);
        var facts = readFieldFacts(new CopilotChatRequest(), new ToolScope(field.getFarmId(), field.getFieldId()), actor);
        if (!requested.targetStage().equals(facts.get("calibrationTarget"))) {
            throw new CopilotConversationException("PHENOLOGY_REVIEW_REQUIRED", "校准依据已变更，请重新读取。", 409);
        }
        return farmFieldService.calibratePhenology(fieldId, actor, requested.expectedRegisteredStage(), requested.targetStage());
    }

    public record UiTaskDraft(String fieldId, String title, String detail, String scheduledFor) {}
    public record UiTaskBatch(List<UiTaskDraft> tasks) {}

    public AgentRun draftFromComponent(UiTaskDraft requested, Long actor) {
        var command = validateComponentDraft(requested, actor);
        try {
            return agentRuntime.prepareTaskDraft("确认组件农事草稿", command.scope(), actor, command.draft());
        } catch (IllegalArgumentException invalid) {
            throw invalidComponentDraft();
        }
    }

    public List<AgentRun> draftBatchFromComponent(UiTaskBatch requested, Long actor) {
        if (requested == null || requested.tasks() == null || requested.tasks().isEmpty() || requested.tasks().size() > 8) {
            throw invalidComponentDraft();
        }
        var commands = requested.tasks().stream().map(task -> validateComponentDraft(task, actor)).toList();
        try {
            return agentRuntime.prepareTaskDraftBatch("确认清单农事草稿", actor, commands);
        } catch (IllegalArgumentException invalid) {
            throw invalidComponentDraft();
        }
    }

    private AgentRuntime.ScopedTaskDraft validateComponentDraft(UiTaskDraft requested, Long actor) {
        if (requested == null || safe(requested.fieldId()).isEmpty() || safe(requested.title()).isEmpty()
                || safe(requested.detail()).isEmpty() || agentRuntime == null || farmFieldService == null) throw invalidComponentDraft();
        if ((requested.title() + requested.detail()).matches("(?is).*(喷药|施药|打药|农药|喷雾|喷施|pesticide).*")) {
            throw new CopilotConversationException("SPRAY_EVIDENCE_REQUIRED", "施药任务需要先核对登记标签、安全间隔期和当前气象。", 422);
        }
        FarmField target = farmFieldService.get(requested.fieldId(), actor);
        try {
            var draft = new AgentRuntime.TaskDraft(requested.title(), requested.detail(), requested.scheduledFor());
            var scope = AgentContext.builder().farmId(target.getFarmId().toString()).fieldId(target.getFieldId()).build();
            var evidence = com.mei.zhgy.vo.DiagnosisEvidenceVO.builder().id("field:" + target.getFieldId()).modality("field")
                    .title("已核验的任务地块").summary(safe(target.getName()))
                    .source(com.mei.zhgy.vo.DiagnosisEvidenceVO.Source.builder().type("field-context")
                            .name("FarMap 地块档案").fieldId(target.getFieldId()).build()).build();
            return new AgentRuntime.ScopedTaskDraft(scope, draft, List.of(evidence));
        } catch (IllegalArgumentException invalid) {
            throw invalidComponentDraft();
        }
    }

    private CopilotConversationException invalidComponentDraft() {
        return new CopilotConversationException("TASK_DRAFT_INVALID", "请提供有效的地块、任务标题、说明与日期。", 422);
    }

    private static final class PiTurn {
        final Map<Object, Map<String, Object>> knowledge = new LinkedHashMap<>();
        final AgriculturalEvidenceLedger evidence = new AgriculturalEvidenceLedger();
        String knowledgeStatus = "NOT_REQUESTED";
        boolean privateFacts;
        boolean publicFacts;
        final int inheritedKnowledgeCount;
        AgentRuntime.TaskDraft draft;
        AgriculturalTaskDraftTool.Batch batch;
        boolean conflictingDrafts;

        PiTurn() { this(CopilotContinuationGrounding.Evidence.EMPTY); }

        PiTurn(CopilotContinuationGrounding.Evidence inherited) {
            privateFacts = inherited.privateFacts();
            publicFacts = inherited.publicFacts();
            inheritedKnowledgeCount = inherited.knowledgeCount();
        }

        CopilotContinuationGrounding.Evidence grounding() {
            return new CopilotContinuationGrounding.Evidence(privateFacts, publicFacts, inheritedKnowledgeCount + knowledge.size());
        }

        boolean grounded() { return grounding().grounded(); }

        CopilotKnowledgeBundle cite(CopilotKnowledgeBundle result, ToolScope scope) {
            List<Map<String, Object>> cited = new ArrayList<>();
            for (var item : result.items()) {
                Object key = item.get("chunkId");
                if (key == null) key = item;
                Map<String, Object> copy = new LinkedHashMap<>(item);
                var entry = evidence.add(String.valueOf(key), String.valueOf(item.getOrDefault("title", "知识依据")),
                        String.valueOf(item.getOrDefault("content", "")), item.get("source") instanceof String source ? source : "FarMap 知识库",
                        "agricultural_knowledge", scope.farmId(), scope.fieldId());
                if (entry != null) copy.put("evidenceId", entry.alias());
                cited.add(copy);
            }
            return new CopilotKnowledgeBundle(result.status(), cited);
        }
    }

    private void observePiTool(SseEmitter emitter, String question, PiTurn turn, PiAgentRuntime.ToolObservation observation) {
        Object data = observation.reply().data();
        try {
            if (emitter != null) {
                for (var citation : observation.reply().citations()) {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("evidenceId", citation.evidenceId()); payload.put("title", citation.title());
                    payload.put("modality", citation.modality()); payload.put("summary", citation.snippet());
                    payload.put("source", citation.sourceName()); payload.put("stageLabel", citation.stageLabel());
                    payload.put("imageUrl", citation.imageUrl()); payload.put("documentName", citation.documentName());
                    payload.put("score", citation.score()); payload.put("expertStatus", citation.expertStatus());
                    send(emitter, "knowledge.evidence", payload);
                }
            }
            if (data instanceof CopilotKnowledgeBundle hits) {
                if (turn == null) {
                    if (emitter != null) emitKnowledgeSummary(emitter, hits);
                } else {
                    for (var hit : hits.items()) {
                        Object id = hit.get("chunkId");
                        turn.knowledge.putIfAbsent(id == null ? hit : id, hit);
                    }
                    turn.knowledgeStatus = turn.knowledge.isEmpty() ? hits.status() : "DEGRADED".equals(hits.status()) ? "DEGRADED" : "READY";
                    if (emitter != null) emitKnowledgeSummary(emitter, new CopilotKnowledgeBundle(turn.knowledgeStatus, List.copyOf(turn.knowledge.values())));
                }
            } else if (data instanceof AgentRuntime.TaskDraft draft && turn != null) {
                if (turn.draft == null) turn.draft = draft;
                else if (!turn.draft.equals(draft)) turn.conflictingDrafts = true;
            } else if (data instanceof AgriculturalTaskDraftTool.Batch batch && turn != null) {
                if (turn.batch == null) turn.batch = batch;
                else if (!turn.batch.equals(batch)) turn.conflictingDrafts = true;
            } else if ("read_field_context".equals(observation.name()) && data instanceof Map<?, ?> facts && turn != null) {
                Object status = facts.get("fieldFactsStatus");
                turn.privateFacts |= "READY".equals(status);
                turn.publicFacts |= "PUBLIC_GENERIC".equals(status) || "FARM_CONTEXT".equals(status);
            } else if ("read_monthly_guidance".equals(observation.name()) && data instanceof List<?> entries && !entries.isEmpty() && turn != null) {
                turn.privateFacts = true;
            } else if ("read_workflow_status".equals(observation.name()) || "read_previous_visual_analysis".equals(observation.name())) {
                if (turn != null) turn.privateFacts = true;
            }
            Map<String, Object> ui = observation.reply().uiBlock();
            if (data instanceof Map<?, ?> value && turn != null && "READY".equals(value.get("status"))) {
                if (Set.of("query_operations_tasks", "analyze_uploaded_images", "read_current_weather", "read_previous_analysis").contains(observation.name())
                        || "OWNER_EXPERT_REVIEW".equals(value.get("source"))) turn.privateFacts = true;
                if ("lookup_reference_atlas".equals(observation.name()) || "search_historical_cases".equals(observation.name())) turn.publicFacts = true;
            }
            if (data instanceof Map<?, ?> report && turn != null && "COMPLETED".equals(report.get("status"))
                    && Set.of("inspect_camera_maturity", "analyze_uploaded_images").contains(observation.name())) {
                turn.privateFacts = true;
            }
            if (ui != null && emitter != null) {
                if (ui.get("componentType") instanceof String type && ui.get("data") instanceof Map<?, ?> fields) {
                    Map<String, Object> blockData = new LinkedHashMap<>();
                    fields.forEach((key, value) -> { if (key instanceof String name) blockData.put(name, value); });
                    List<Map<String, Object>> sources = ui.get("evidenceIds") instanceof List<?> ids
                            ? ids.stream().filter(String.class::isInstance).map(id -> Map.<String, Object>of("label", id, "reference", id)).toList()
                            : observation.reply().citations().stream().map(citation -> Map.<String, Object>of(
                                    "label", citation.title(), "reference", citation.evidenceId())).toList();
                    emitBlock(emitter, type, String.valueOf(ui.get("title")),
                            ui.get("summary") instanceof String summary ? summary : null, blockData,
                            String.valueOf(ui.getOrDefault("status", "ready")), sources,
                            ui.get("blockKey") instanceof String key ? key : null);
                    if ("FarmMaturityCard".equals(type) && "FARM".equals(blockData.get("mode")) && blockData.get("runId") != null) {
                        send(emitter, "analysis.workspace", Map.of("runId", blockData.get("runId"), "farmId", blockData.get("farmId"), "mode", "FARM"));
                    }
                    if ("FarmMaturityCard".equals(type) && ui.get("maturitySeries") instanceof List<?> series && !series.isEmpty()) {
                        emitBlock(emitter, "EChartsAnalyticsCard", "多地块有效画面成熟度占比",
                                "仅统计已判读的画面。", Map.of("chartKind", "stacked-bar", "series", series), "ready",
                                sources, "maturity-chart:" + blockData.get("runId"));
                    }
                    if ("PlumPhenologyTimeline".equals(type) && data instanceof Map<?, ?> context
                            && context.get("accumulatedTemperatureHistory") instanceof List<?> history && history.size() > 1) {
                        emitThermalTrend(emitter, context, history, sources);
                    }
                }
                if (ui.get("referenceImages") instanceof List<?> images) {
                    send(emitter, "reference.images", Map.of("status", "READY", "count", images.size(), "items", images));
                }
            }
            if (ui != null && ui.get("weather") instanceof com.mei.zhgy.vo.CurrentWeatherResponse weather) {
                if ("read_current_weather".equals(observation.name()) && "READY".equals(weather.status())) {
                    if (turn != null) turn.privateFacts = true;
                    UiStream stream = emitter == null ? null : uiStreams.get(emitter);
                    if (stream == null || stream.blocks.stream().anyMatch(block -> "SprayWeatherWindowCard".equals(block.get("componentType")))) return;
                    emitWeatherArtifacts(emitter, weather, contains(safe(question), "趋势", "降雨概率"));
                }
            }
        } catch (IOException failure) { throw new ChatDeliveryException(failure); }
    }

    private void emitThermalTrend(SseEmitter emitter, Map<?, ?> context, List<?> history,
                                  List<Map<String, Object>> sources) throws IOException {
        var points = new ArrayList<Map<String, Object>>();
        for (Object value : history) {
            if (value instanceof Map<?, ?> row && row.get("label") instanceof String date
                    && row.get("value") instanceof Number reading && Double.isFinite(reading.doubleValue())) {
                points.add(Map.of("label", date, "value", reading));
            }
        }
        if (points.size() < 2) return;
        var data = new LinkedHashMap<String, Object>();
        data.put("chartKind", "thermal");
        data.put("series", List.of(CopilotUiBlockValidator.chartSeries("thermal", "农场累计积温", points)));
        if (context.get("thermalTarget") instanceof Number threshold) {
            data.put("thresholds", List.of(Map.of("label", "下一物候阶段校准阈值", "value", threshold, "unit", "℃·d")));
        }
        emitBlock(emitter, "EChartsAnalyticsCard", "累计积温", null, data, "ready", sources,
                "EChartsAnalyticsCard:thermal:" + context.get("fieldId"));
    }

    private void emitPiSource(SseEmitter emitter, PiTurn turn) {
        if (emitter == null) return;
        try {
            send(emitter, "answer.source", Map.of("layer", piSource(turn), "grounded", turn.grounded(),
                    "fieldBound", turn.privateFacts, "knowledgeCount", turn.grounding().knowledgeCount()));
        } catch (IOException failure) { throw new ChatDeliveryException(failure); }
    }

    private String piSource(PiTurn turn) {
        if (turn.privateFacts) return "L1_FIELD_PRIVATE";
        if (turn.grounded()) return "L2_PUBLIC_AGRICULTURE";
        return allowUngrounded ? "L3_MODEL_GENERAL" : "BLOCKED_NO_GROUNDING";
    }

    private String piSession(Long userId, String conversationId) {
        return userId + ":" + conversationId;
    }

    private void streamPiEvent(SseEmitter emitter, Map<String, Object> event) {
        try {
            UiStream ui = uiStreams.get(emitter);
            if (ui != null) ui.telemetry.event(event);
            send(emitter, "agent.runtime", event);
            String type = String.valueOf(event.get("type"));
            String mapped = switch (type) {
                case "tool_execution_start" -> "tool.started";
                case "tool_execution_update" -> "tool.updated";
                case "tool_execution_end" -> "tool.completed";
                default -> null;
            };
            if (mapped != null) {
                Map<String, Object> activity = new LinkedHashMap<>(event);
                activity.put("type", mapped); activity.put("at", Instant.now().toString());
                if (ui != null) { activity.put("runId", ui.runId); recordActivity(ui, activity); }
                send(emitter, mapped, event);
            }
        }
        catch (IOException error) { throw new ChatDeliveryException(error); }
    }

    private static final class PiTurnAborted extends RuntimeException {}

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
        if (facts.sources() != null) {
            context.put("phenologyBasis", facts.sources().get("phenologyBasis"));
            context.put("phenologyEffectiveDate", facts.sources().get("phenologyEffectiveDate"));
        }
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

    private Map<String, Object> answerSource(CopilotRouteResult routed, CopilotFacts facts, CopilotKnowledgeBundle knowledge, String message) {
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
        if (emitter == null || content == null || content.isEmpty()) return;
        UiStream ui = uiStreams.get(emitter);
        int chunkSize = 24;
        for (int offset = 0; offset < content.length(); offset += chunkSize) {
            String delta = content.substring(offset, Math.min(content.length(), offset + chunkSize));
            try {
                Map<String, String> payload = Map.of("delta", delta);
                if (ui != null && ui.modelAnalysis) send(emitter, "presentation.analysis.delta", payload);
                send(emitter, "content.delta", payload);
                send(emitter, "message.delta", payload);
            }
            catch (IOException error) { throw new ChatDeliveryException(error); }
        }
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        UiStream ui = uiStreams.get(emitter);
        if (ui != null && data instanceof Map<?, ?> row && "answer.source".equals(event)) ui.telemetry.source(row.get("layer"));
        if ("knowledge.evidence".equals(event)) {
            if (ui != null) ui.parts.afterLegacyEvent(emitter, event, data);
            return;
        }
        if (ui != null && "presentation.summary".equals(event)) {
            ui.parts.afterLegacyEvent(emitter, event, data);
            emitter.send(SseEmitter.event().name(event).data(data));
            return;
        }
        emitter.send(SseEmitter.event().name(event).data(data));
        if (ui != null) ui.parts.afterLegacyEvent(emitter, event, data);
    }

    private void sendDone(SseEmitter emitter, String status) throws IOException {
        UiStream ui = uiStreams.remove(emitter);
        if (ui != null) synchronized (requestUiStreams) { requestUiStreams.values().remove(ui); }
        String event = "FAILED".equals(status) || "REJECTED".equals(status) ? "run.failed"
                : "ABORTED".equals(status) ? "run.cancelled" : "run.completed";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (ui != null) payload.put("runId", ui.runId);
        if (ui != null) { Map<String, Object> activity = new LinkedHashMap<>(payload); activity.put("type", event); activity.put("at", Instant.now().toString()); recordActivity(ui, activity); }
        if (ui != null) ui.parts.afterLegacyEvent(emitter, "done", payload);
        send(emitter, event, payload);
        send(emitter, "done", Map.of("status", status));
        emitter.complete();
    }

    private void appendChatAnswer(CopilotChatRequest request, CopilotRouteResult routed, String answer, Long userId) {
        appendChatAnswer(request, routed, answer, userId, null);
    }

    private void appendChatAnswer(CopilotChatRequest request, CopilotRouteResult routed, String answer, Long userId,
                                  ReferenceImageResponse referenceImages) {
        if (request.getConversationId() == null || conversations == null) return;
        UiStream ui = requestUiStreams.get(request);
        if (ui != null && ui.modelAnalysis && answer != null && !answer.isBlank()) {
            ui.presentation.put("analysis", answer);
            answer = ui.presentation.get("summary") + "\n\n" + answer;
        }
        try { conversations.appendAssistantAnswer(request.getConversationId(), userId, routed.getIntent(), routed.getTraceId(), answer,
                responsePersistence(referenceImages, ui, routed.getTraceId())); }
        catch (RuntimeException ignored) { /* optional conversation persistence must not break the answer */ }
    }

    private void emitInitialScope(SseEmitter emitter, CopilotChatRequest request, CopilotRouteResult routed, Long userId) throws IOException {
        if (routed.getResolvedContext() == null || farmFieldService == null || !presentationAllowed(routed)
                || routed.getResolutionStatus() != CopilotResolutionStatus.RESOLVED) return;
        String query = safe(request.getMessage());
        String fieldId = routed.getResolvedContext().getFieldId();
        Integer farmId = parseInt(routed.getResolvedContext().getFarmId());
        if (farmId == null && fieldId != null && !fieldId.isBlank() && contains(query, "天气", "气象"))
            farmId = farmFieldService.get(fieldId, userId).getFarmId();
        boolean fieldQuery = containsAny(query, "地块档案", "地块信息", "地块概况", "地块状态", "地块面积", "地块种了什么", "当前地块详情");
        boolean farmQuery = containsAny(query, "有哪些地块", "几个地块", "多少个地块", "地块列表", "地块清单", "列出地块", "列出所有地块");
        if (fieldQuery && !farmQuery && fieldId != null && !fieldId.isBlank()) {
            FarmField field = farmFieldService.get(fieldId, userId);
            List<Map<String, Object>> rows = new ArrayList<>();
            addItem(rows, "地块", field.getName()); addItem(rows, "作物", field.getCrop());
            addItem(rows, "面积", field.getArea()); addItem(rows, "登记生育期", field.getGrowthStage());
            emitBlock(emitter, "ParcelOverviewCard", "当前地块", null, Map.of("items", rows, "fieldId", fieldId),
                    "ready", List.of(Map.of("label", "地块档案", "reference", fieldId)));
        } else if (farmQuery && farmId != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (FarmField field : farmFieldService.list(farmId, userId)) addItem(rows, "地块", field.getName());
            emitBlock(emitter, "FarmAnalyticsCard", "农场地块", rows.isEmpty() ? "暂无地块记录。" : null,
                    Map.of("items", rows), "ready", List.of(Map.of("label", "农场地块档案")));
        }
        if (containsAny(query, "农场地图", "地块地图", "地块位置", "地块分布", "地块在哪里", "农场位置") && farmId != null) {
            List<Map<String, Object>> locations = new ArrayList<>();
            for (FarmField field : farmFieldService.list(farmId, userId)) {
                if (field.getLatitude() != null && field.getLongitude() != null)
                    locations.add(Map.of("id", field.getFieldId(), "label", field.getName(),
                            "latitude", field.getLatitude(), "longitude", field.getLongitude()));
            }
            emitBlock(emitter, "FarmMapCard", "地块位置", locations.isEmpty() ? "暂无地块坐标。" : null,
                    Map.of("locations", locations), locations.isEmpty() ? "unavailable" : "ready", List.of(Map.of("label", "地块档案")));
        }
        if (containsAny(query, "长势趋势", "长势图", "生长趋势", "生长曲线")) {
            emitBlock(emitter, "CropGrowthChart", "作物生长趋势", "暂无可用的生长观测序列。",
                    Map.of("series", List.of()), "unavailable", List.of());
        }
        if (!usesPi() && contains(query, "天气", "气象") && farmId != null && currentWeatherService != null) {
            var weather = readWeather(emitter, farmId, userId);
            emitWeatherArtifacts(emitter, weather, containsAny(query, "趋势", "降雨概率"));
        }
    }

    private boolean streamCurrentWeather(CopilotChatRequest request, CopilotRouteResult route, Long userId, SseEmitter emitter) throws IOException {
        String message = safe(request.getMessage());
        if (currentWeatherService == null || !containsAny(message, "天气", "气象")
                || message.matches("(?s).*\\d{1,4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}.*|(?s).*\\d{1,2}月\\d{1,2}[日号]?.*")
                || containsAny(message, "是什么", "为什么", "怎么管理", "建议", "指导", "结合", "适合", "能否", "安排", "明天", "后天", "昨天", "历史", "预报", "未来", "本周", "对比", "影响", "怎么防")
                || !presentationAllowed(route)) return false;
        var page = request.getPageContext();
        Integer farmId = operationFarm(route, page);
        if (farmId == null || farmId <= 0) {
            maturityGuidance(request, route, userId, emitter, "请先选择农场，我才能查询当前天气。");
            return true;
        }
        var weather = readWeather(emitter, farmId, userId);
        String farm = safe(weather.farmName()).isBlank() ? "当前农场" : weather.farmName();
        boolean ready = "READY".equals(weather.status());
        String summary = ready
                ? farm + (safe(weather.condition()).isBlank() ? "已获取最新天气观测。" : "目前天气为" + weather.condition() + "。")
                : "气象服务暂未返回" + farm + "的有效观测。";
        publishSummary(emitter, summary);
        emitWeatherArtifacts(emitter, weather, false);
        String analysis = !ready ? null : weather.rainfallForecast24h() == null && weather.rainfallProbability24h() == null
                ? "暂缺降水量和降雨概率，安排灌溉或田间作业前请结合现场土壤湿度与降雨实况。"
                : "预报可用作农事排期的参考。施药、采收等对风雨敏感的作业，还需结合地块现场条件。";
        var actions = ready ? List.of(
                Map.of("label", "分析近期农事安排", "prompt", "请结合当前农场的天气和田间条件，分析近期的农事安排。"),
                Map.of("label", "查看低温风险", "prompt", "请结合当前农场的气象观测和预报，评估低温对农事活动的影响。")) : List.<Map<String, String>>of();
        finishPresentedTurn(request, route, userId, emitter, summary, analysis, actions);
        return true;
    }

    private com.mei.zhgy.vo.CurrentWeatherResponse readWeather(SseEmitter emitter, Integer farmId, Long userId) throws IOException {
            streamPiEvent(emitter, Map.of("type", "tool_execution_start", "toolName", "read_current_weather"));
            var weather = currentWeatherService.get(farmId, userId);
            UiStream ui = uiStreams.get(emitter);
            if (ui != null) ui.weather = weather;
            streamPiEvent(emitter, Map.of("type", "tool_execution_end", "toolName", "read_current_weather"));
            return weather;
    }

    private void emitWeatherArtifacts(SseEmitter emitter, com.mei.zhgy.vo.CurrentWeatherResponse weather, boolean showTrend) throws IOException {
        for (var card : CopilotWeatherCards.from(weather, showTrend)) emitCard(emitter, card);
    }

    private void emitCard(SseEmitter emitter, CopilotPresentationCard card) throws IOException {
        emitBlock(emitter, card.type(), card.title(), card.summary(), card.data(), card.status(), card.sources(), card.key());
    }

    private void addItem(List<Map<String, Object>> items, String label, Object value) {
        if (value != null && !value.toString().isBlank()) items.add(Map.of("label", label, "value", value));
    }

    private static final class UiStream {
        volatile boolean detached;
        final String runId = "chat-" + java.util.UUID.randomUUID();
        final CopilotPresentationEmitter parts = new CopilotPresentationEmitter(runId);
        final CopilotTurnTelemetry telemetry = new CopilotTurnTelemetry();
        final List<Map<String, Object>> blocks = parts.blocks();
        final Map<String, Object> presentation = new LinkedHashMap<>();
        boolean modelAnalysis;
        String conversationId;
        String traceId;
        CopilotIntent intent;
        Long userId;
        Integer knowledgeCount;
        String knowledgeStatus;
        com.mei.zhgy.vo.CurrentWeatherResponse weather;
    }

    private void recordActivity(UiStream ui, Map<String, Object> event) {
        if (conversations != null && ui.conversationId != null && ui.userId != null)
            conversations.recordActivity(ui.conversationId, ui.userId, event);
    }

    private Map<String, Object> emitBlock(SseEmitter emitter, String type, String title, String summary, Map<String, Object> data,
                           String status, List<Map<String, Object>> provenance) throws IOException {
        return emitBlock(emitter, type, title, summary, data, status, provenance, null);
    }

    private Map<String, Object> emitBlock(SseEmitter emitter, String type, String title, String summary, Map<String, Object> data,
                           String status, List<Map<String, Object>> provenance, String blockKey) throws IOException {
        UiStream ui = uiStreams.get(emitter);
        if (ui == null) return null;
        var change = ui.parts.upsertBlock(type, title, summary, data, status, provenance, blockKey);
        ui.telemetry.block(change.block());
        send(emitter, change.created() ? "ui.block.created" : "ui.block.updated", change.block());
        return change.block();
    }

    private void updateArtifact(SseEmitter emitter, Map<String, Object> block, String status) throws IOException {
        if (block == null) return;
        block.put("status", status);
        block.put("updatedAt", Instant.now().toString());
        send(emitter, "ui.block.updated", block);
    }

    private void emitWorkflowBlock(CopilotChatRequest request, CopilotRouteResult routed, Long userId,
                                   SseEmitter emitter, String runId, String diagnosisId) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId); if (diagnosisId != null) data.put("diagnosisId", diagnosisId);
        emitBlock(emitter, diagnosisId == null ? "FarmPlanCard" : "TaskProgressCard", diagnosisId == null ? "农事计划" : "农事工作流", "正在准备草稿，确认后创建任务。", data,
                "loading", List.of(Map.of("label", "农事工作流", "reference", runId)));
    }

    private void projectWorkflow(CopilotChatRequest request, CopilotRouteResult routed, Long userId,
                                 SseEmitter emitter, String runId) throws IOException {
        UiStream ui = uiStreams.get(emitter);
        if (ui == null || ui.blocks.isEmpty()) return;
        AgentRun current = agentRuntime.require(runId, userId);
        if (current == null || current.getStatus() == null) return;
        String state = current.getStatus().name();
        Map<String, Object> block = ui.blocks.get(ui.blocks.size() - 1);
        block.put("status", switch (state) {
            case "WAITING_FOR_APPROVAL" -> "needs-input";
            case "COMPLETED" -> "completed";
            case "FAILED", "CANCELLED" -> "failed";
            default -> "loading";
        });
        if ("WAITING_FOR_APPROVAL".equals(state)) { block.put("componentType", "ConfirmationCard"); block.put("title", "确认农事计划"); }
        block.put("summary", switch (state) {
            case "WAITING_FOR_APPROVAL" -> "任务草案等待你的确认。";
            case "COMPLETED" -> "工作流已完成，可查看关联任务的执行状态。";
            case "FAILED" -> "任务执行失败，请查看详情。";
            case "CANCELLED" -> "任务已取消。";
            default -> "正在准备任务草案。";
        });
        block.put("updatedAt", Instant.now().toString());
        ui.telemetry.block(block);
        if (conversations != null && request.getConversationId() != null)
            conversations.updateAssistantUiBlocks(request.getConversationId(), userId, routed.getTraceId(), List.copyOf(ui.blocks));
        send(emitter, "ui.block.updated", block);
        if ("WAITING_FOR_APPROVAL".equals(state)) {
            List<Map<String, Object>> actions = new ArrayList<>();
            if (current.getActions() != null) for (var action : current.getActions()) {
                if (action.getId() != null) actions.add(Map.of("actionId", action.getId(), "title", safe(action.getTitle())));
            }
            send(emitter, "ui.confirmation.required", Map.of("runId", runId, "actions", actions));
        }
    }

    private Map<String, Object> responsePersistence(ReferenceImageResponse images, UiStream ui, String traceId) {
        Map<String, Object> metadata = images == null ? new LinkedHashMap<>() : images.persistenceMetadata();
        if (ui != null && !ui.parts.snapshot().isEmpty()) metadata.put("messageParts", ui.parts.snapshot());
        if (ui != null && !ui.blocks.isEmpty()) metadata.put("uiBlocks", List.copyOf(ui.blocks));
        if (ui != null && !ui.presentation.isEmpty()) metadata.put("presentation", Map.copyOf(ui.presentation));
        if (ui != null) {
            metadata.put("knowledgeCount", ui.knowledgeCount);
            metadata.put("knowledgeStatus", ui.knowledgeStatus);
            metadata.put("telemetry", ui.telemetry.snapshot(traceId != null ? traceId : ui.runId));
        }
        return metadata.isEmpty() ? null : metadata;
    }

    private record ReferenceImageResponse(ReferenceImageLookup lookup, CopilotMediaNeed mediaNeed, String message) {
        private Map<String, Object> persistenceMetadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schemaVersion", 1);
            metadata.put("referenceImages", lookup.images());
            metadata.put("referenceImageStatus", lookup.status());
            if (lookup.match() != null && !lookup.match().isBlank()) metadata.put("referenceImageMatch", lookup.match());
            if (message != null && !message.isBlank()) metadata.put("referenceImageNotice", message);
            return metadata;
        }
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }

    private List<String> trustedImageUrls(List<String> references) {
        return CopilotSecurityGuard.trustedImages(references, uploadBaseUrl, cosPublicBaseUrl, cosBucket, cosRegion);
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static final class ChatDeliveryException extends RuntimeException {
        private ChatDeliveryException(IOException cause) { super(cause); }
    }

    /**
     * Produces a concise, evidence-grounded Copilot summary after the real
     * read-only command has completed. This endpoint intentionally returns a
     * safe answer and model metadata, never private chain-of-thought.
     */
    public Result<Map<String, Object>> answer(AnswerRequest request, Long userId) {
        if (usesPi()) return answerWithPi(request, userId);
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
        if (contextString(context, "fieldId") == null
                && !"READY".equalsIgnoreCase(String.valueOf(context.getOrDefault("fieldFactsStatus", "")))) {
            CopilotFacts publicFacts = fieldContext.publicFacts(message, LocalDate.now(COPILOT_ZONE), LocalDate.now(COPILOT_ZONE).format(COPILOT_DATE_FORMAT));
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

    private Result<Map<String, Object>> answerWithPi(AnswerRequest request, Long userId) {
        long started = System.nanoTime();
        CopilotChatRequest chat = new CopilotChatRequest();
        chat.setMessage(request == null || request.message == null ? "" : request.message.trim());
        chat.setConversationId(request == null ? null : request.conversationId);
        CopilotConversationView view = chat.getConversationId() == null || chat.getConversationId().isBlank() || conversations == null
                ? null : conversations.runtimeView(chat.getConversationId(), userId);
        CopilotStableContext context = view == null || view.getActiveContext() == null
                ? new CopilotStableContext() : view.getActiveContext().copy();
        Object hint = request == null || request.context == null ? null : request.context.get("fieldId");
        if (hint != null && !String.valueOf(hint).isBlank()) {
            String fieldId = String.valueOf(hint);
            if (!java.util.Objects.equals(fieldId, context.getFieldId())) {
                context = new CopilotStableContext();
                context.setFieldId(fieldId);
            }
        }
        CopilotIntent intent = CopilotIntent.GENERAL_QA;
        if (request != null && request.intent != null) {
            try { intent = CopilotIntent.valueOf(request.intent); } catch (IllegalArgumentException ignored) { }
        }
        CopilotRouteResult routed = CopilotRouteResult.builder().resolvedContext(context).intent(intent)
                .traceId(request == null ? null : request.traceId)
                .contextVersion(view == null ? null : view.getContextVersion()).build();
        CopilotFacts facts = initialAnswerFacts(chat, routed, userId);
        String commandReport = request != null && hasEvidence(request.evidence) ? bound(String.valueOf(request.evidence), 6000) : null;
        Map<String, Object> response = new LinkedHashMap<>();
        PiTurn turn = new PiTurn();
        try {
            String answer = streamModelAnswer(chat, routed, facts, CopilotKnowledgeBundle.notRequested(), userId, null, commandReport, turn);
            response.put("answer", answer);
            response.put("available", true);
            response.put("latencyMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            appendAnswer(request, request == null || request.intent == null ? "UNKNOWN" : request.intent, answer, userId);
        } catch (IllegalStateException failure) {
            response.put("answer", "当前 Copilot 模型暂不可用，已完成上下文收集；请稍后重试。");
            response.put("available", false);
            response.put("providerStatus", "UNAVAILABLE");
            response.put("latencyMs", null);
        }
        response.put("model", null);
        if (commandReport != null) response.put("sourceProvenance", "CLIENT_REPORTED");
        response.put("grounded", turn.grounded());
        response.put("answerSource", piSource(turn));
        response.put("evidenceCount", turn.knowledge.size());
        return Result.success(response);
    }
    private String recentConversation(AnswerRequest request, Long userId) {
        if (request == null || request.conversationId == null || request.conversationId.isBlank() || conversations == null) return "无";
        try {
            return formatRecentConversation(conversations.runtimeView(request.conversationId, userId));
        } catch (RuntimeException ignored) {
            return "无";
        }
    }

    private String recentConversation(CopilotChatRequest request, Long userId) {
        if (request == null || request.getConversationId() == null || request.getConversationId().isBlank() || conversations == null) return "无";
        try {
            return formatRecentConversation(conversations.runtimeView(request.getConversationId(), userId));
        } catch (RuntimeException ignored) {
            return "无";
        }
    }

    /** Keeps the latest ten turns while reserving a small prefix for the server-owned summary. */
    private String formatRecentConversation(CopilotConversationView view) {
        if (view == null) return "无";
        List<com.mei.zhgy.service.copilot.CopilotConversationTurn> turns = view.getRecentTurns() == null
                ? List.of() : view.getRecentTurns();
        List<com.mei.zhgy.service.copilot.CopilotConversationTurn> visibleTurns = turns.stream()
                .filter(com.mei.zhgy.service.copilot.CopilotConversationTurnPolicy::isVisibleConversationTurn)
                .toList();
        String joined = visibleTurns.stream()
                .skip(Math.max(0, visibleTurns.size() - 10L))
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

    public java.util.List<com.mei.zhgy.service.copilot.CopilotMemoryView> listMemories(String farmId, String fieldId, Long userId) {
        if (memories == null) throw new CopilotConversationException("MEMORY_UNAVAILABLE", "memory service unavailable", 503);
        return memories.views(userId, farmId, fieldId);
    }

    public com.mei.zhgy.service.copilot.CopilotMemoryView revokeMemory(String memoryId, Long userId) {
        if (memories == null) throw new CopilotConversationException("MEMORY_UNAVAILABLE", "memory service unavailable", 503);
        return memories.revokeById(memoryId, userId);
    }

    public CopilotMemoryProposalService.ProposalConfirmation confirmMemoryProposal(String proposalId, Long actor) {
        if (memoryProposals == null) throw new CopilotConversationException("MEMORY_UNAVAILABLE", "果园资料提案服务不可用", 503);
        return memoryProposals.confirm(proposalId, actor);
    }

    public CopilotMemoryProposalService.ProposalView readMemoryProposal(String proposalId, Long actor) {
        if (memoryProposals == null) throw new CopilotConversationException("MEMORY_UNAVAILABLE", "果园资料提案服务不可用", 503);
        return memoryProposals.read(proposalId, actor);
    }

    public CopilotMemoryProposalService.ProposalView ignoreMemoryProposal(String proposalId, Long actor) {
        if (memoryProposals == null) throw new CopilotConversationException("MEMORY_UNAVAILABLE", "果园资料提案服务不可用", 503);
        return memoryProposals.ignore(proposalId, actor);
    }

    private void requireConversationService() { if (conversations == null) throw new CopilotConversationException("CONVERSATION_UNAVAILABLE", "conversation service unavailable", 503); }

}

package com.mei.zhgy.service.copilot;

import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Orchestrates classification, server-side resolution and canonical capability routing. */
@Service
public class CopilotRouteService {
    private final CopilotIntentRouter router;
    private final CopilotContextResolver resolver;
    private final CopilotSessionContextStore sessions;
    private final CopilotConversationService conversations;
    private final CopilotLongTermMemoryService longTermMemory;

    public CopilotRouteService(CopilotIntentRouter router, CopilotContextResolver resolver, CopilotSessionContextStore sessions) {
        this(router, resolver, sessions, null, null);
    }

    public CopilotRouteService(CopilotIntentRouter router, CopilotContextResolver resolver, CopilotSessionContextStore sessions, CopilotConversationService conversations) {
        this(router, resolver, sessions, conversations, null);
    }

    @Autowired
    public CopilotRouteService(CopilotIntentRouter router, CopilotContextResolver resolver, CopilotSessionContextStore sessions, CopilotConversationService conversations, CopilotLongTermMemoryService longTermMemory) {
        this.router = router;
        this.resolver = resolver;
        this.sessions = sessions;
        this.conversations = conversations;
        this.longTermMemory = longTermMemory;
    }

    public CopilotRouteResult route(CopilotRouteRequest request, Long userId) {
        return route(request, userId, false);
    }

    public CopilotRouteResult route(CopilotRouteRequest request, Long userId, boolean hasUserImages) {
        String traceId = "trace-" + UUID.randomUUID();
        CopilotRouteRequest safeRequest = request == null ? new CopilotRouteRequest() : request;
        CopilotConversationService.ConversationHandle conversation = conversations == null ? null : conversations.begin(safeRequest, userId);
        if (conversation != null && conversation.duplicateResult() != null) return conversation.duplicateResult();
        CopilotIntentRouter.HybridContext hybridContext = new CopilotIntentRouter.HybridContext(
                recentConversation(conversation == null ? null : conversation.snapshot()),
                safeRequest.getPageContext(), null);
        boolean pendingActionConfirmation = conversation != null
                && isPendingActionConfirmation(conversation.snapshot(), safeRequest.getMessage());
        String effectiveMessage = pendingActionConfirmation
                ? pendingActionMessage(conversation.snapshot(), safeRequest.getMessage())
                : safeRequest.getMessage();
        CopilotRouteRequest routingRequest = pendingActionConfirmation
                ? copyWithMessage(safeRequest, effectiveMessage) : safeRequest;
        CopilotIntentRouter.HybridClassification hybrid = router.classifyHybrid(effectiveMessage, hybridContext, hasUserImages);
        CopilotIntentRouter.Classification classification = hybrid.finalClassification();
        CopilotIntent intent = classification.intent();
        boolean pendingContinuation = conversation != null && pendingRecommendationContinuation(conversation.snapshot(), safeRequest.getMessage());
        if (pendingContinuation || pendingActionConfirmation) intent = CopilotIntent.ACTION_REQUEST;

        if (intent == CopilotIntent.UNSUPPORTED) return persist(conversation, safeRequest, withIntentDiagnostics(result(traceId, intent, CopilotResolutionStatus.UNSUPPORTED, null, null, false, "当前请求不属于 FarMap Copilot 支持范围。", "REJECT", classification.safeRoute(), classification.confidence()), hybrid, safeRequest.getMessage(), effectiveMessage, hasUserImages));
        if (intent == CopilotIntent.CLARIFICATION_REQUIRED || (!pendingContinuation && router.belowThreshold(classification.confidence()))) {
            return persist(conversation, safeRequest, withIntentDiagnostics(result(traceId, CopilotIntent.CLARIFICATION_REQUIRED, CopilotResolutionStatus.CLARIFICATION_REQUIRED, null, null, true, "请明确要查询的对象或希望执行的动作。", "CLARIFY_CONTEXT", classification.safeRoute(), classification.confidence()), hybrid, safeRequest.getMessage(), effectiveMessage, hasUserImages));
        }

        CopilotStableContext session = conversation == null
                ? sessions.find(safeRequest.getSessionId()).orElse(null)
                : conversation.snapshot().getActiveContext();
        if (longTermMemory != null && isMemoryIntent(intent)) {
            CopilotStableContext active = mergeStable(safeRequest, session);
            CopilotMemoryDecision decision = memoryDecision(intent, safeRequest, active, userId);
            CopilotRouteResult memoryResult = withIntentDiagnostics(memoryResult(traceId, intent, active, decision, classification.confidence()), hybrid,
                    safeRequest.getMessage(), effectiveMessage, hasUserImages);
            CopilotRouteResult persisted = persist(conversation, safeRequest, memoryResult);
            if (persisted.getResolutionStatus() == CopilotResolutionStatus.RESOLVED && decision.accepted()) {
                if (intent == CopilotIntent.MEMORY_WRITE_REQUEST && decision.candidate() != null) {
                    CopilotLongTermMemory memory = longTermMemory.write(decision.candidate(), userId, persisted.getConversationId(), persisted.getTurnId());
                    persisted.setMemoryId(memory.getMemoryId()); persisted.setMemoryScope(memory.getScope()); persisted.setMemoryType(memory.getType());
                    persisted.setMemoryStatus(memory.getStatus() == CopilotMemoryStatus.ACTIVE && memory.getSupersedesMemoryId() == null ? "ACTIVE" : memory.getStatus().name());
                } else if (intent == CopilotIntent.MEMORY_FORGET_REQUEST && decision.target() != null) {
                    CopilotLongTermMemory memory = longTermMemory.revoke(decision.target(), userId);
                    persisted.setMemoryId(memory.getMemoryId()); persisted.setMemoryScope(memory.getScope()); persisted.setMemoryType(memory.getType()); persisted.setMemoryStatus(memory.getStatus().name());
                }
                conversations.updatePersistedResult(persisted, userId);
            }
            return persisted;
        }
        CopilotContextResolver.Resolution resolution = resolver.resolve(routingRequest, routingRequest.getPageContext(), session, intent, userId);
        // Keep the intent-to-capability contract visible even while context is
        // incomplete; execution remains impossible until resolution is RESOLVED.
        CopilotCapability capability = capability(intent);
        boolean clarification = resolution.status() == CopilotResolutionStatus.CLARIFICATION_REQUIRED || resolution.status() == CopilotResolutionStatus.STALE;
        String question = resolution.question();
        String nextAction = nextAction(intent, resolution.status());
        if (resolution.status() == CopilotResolutionStatus.RESOLVED && conversation == null) sessions.update(safeRequest.getSessionId(), resolution.context(), intent);
        CopilotRouteResult routed = withIntentDiagnostics(result(traceId, intent, resolution.status(), resolution.context(), capability, clarification, question, nextAction, classification.safeRoute(), classification.confidence()), hybrid,
                safeRequest.getMessage(), effectiveMessage, hasUserImages);
        if (longTermMemory != null) routed.setRelevantLongTermMemories(longTermMemory.retrieve(userId, mergeStable(safeRequest, session), safeRequest.getPageContext()).stream().map(CopilotMemoryView::from).toList());
        return persist(conversation, safeRequest, routed);
    }

    private CopilotRouteResult persist(CopilotConversationService.ConversationHandle conversation, CopilotRouteRequest request, CopilotRouteResult result) {
        if (conversation == null) return result;
        conversations.commit(conversation, request, result);
        return result;
    }

    private CopilotRouteResult withIntentDiagnostics(CopilotRouteResult result,
                                                     CopilotIntentRouter.HybridClassification hybrid,
                                                     String originalQuery,
                                                     String normalizedSource,
                                                     boolean hasUserImages) {
        if (result == null || hybrid == null) return result;
        result.setOriginalQuery(originalQuery);
        result.setNormalizedQuery(CopilotQueryNormalizer.forMatching(normalizedSource));
        result.setMediaNeed(CopilotMediaPolicy.decide(normalizedSource, result.getIntent(), hasUserImages));
        boolean directAction = result.getIntent() == CopilotIntent.ACTION_REQUEST
                && (result.getResolvedContext() == null || blank(result.getResolvedContext().getRecommendationId()));
        if (directAction && normalizedSource != null && !normalizedSource.isBlank()) {
            result.setRequestedAction(normalizedSource.trim());
        }
        if (hybrid.rule() != null) result.setRuleIntent(hybrid.rule().intent());
        result.setFinalIntent(result.getIntent());
        if (hybrid.finalClassification() != null && hybrid.finalClassification() != hybrid.rule()) {
            result.setLlmIntent(hybrid.finalClassification().intent());
        }
        result.setIntentSlots(hybrid.slots());
        if (hybrid.llm() != null) {
            result.setIntentTier(hybrid.llm().tier());
            result.setIntentModel(hybrid.llm().model());
            result.setIntentModelConfidence(hybrid.llm().ok() ? hybrid.llm().confidence() : null);
            result.setIntentLatencyMs(hybrid.llm().latencyMs());
            result.setIntentFallbackReason(hybrid.llm().fallbackReason());
            if (hybrid.llm().intent() != null) result.setLlmIntent(hybrid.llm().intent());
        }
        return result;
    }

    private String recentConversation(CopilotConversationSnapshot snapshot) {
        if (snapshot == null || snapshot.getRecentTurns() == null || snapshot.getRecentTurns().isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        List<CopilotConversationTurn> turns = snapshot.getRecentTurns().stream()
                .filter(CopilotConversationTurnPolicy::isVisibleConversationTurn)
                .toList();
        int from = Math.max(0, turns.size() - 4);
        for (int i = from; i < turns.size(); i++) {
            CopilotConversationTurn turn = turns.get(i);
            if (turn == null || turn.getMessage() == null || turn.getMessage().isBlank()) continue;
            String message = turn.getMessage().trim();
            if (message.length() > 160) message = message.substring(0, 160);
            String role = turn.getRole() == null ? "turn" : turn.getRole().trim().toLowerCase(Locale.ROOT);
            if (output.length() > 0) output.append('\n');
            output.append(role).append(": ").append(message);
            if (output.length() >= 512) break;
        }
        return output.length() <= 512 ? output.toString() : output.substring(0, 512);
    }

    private boolean pendingRecommendationContinuation(CopilotConversationSnapshot snapshot, String message) {
        if (snapshot == null || snapshot.getPendingClarification() == null || !"RECOMMENDATION".equals(snapshot.getPendingClarification().getType()) || message == null) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return message.contains("第一条") || normalized.contains("first recommendation") || normalized.matches(".*\\bfirst\\b.*");
    }

    private boolean isPendingActionConfirmation(CopilotConversationSnapshot snapshot, String message) {
        if (snapshot == null || snapshot.getPendingClarification() == null
                || !"ACTION".equals(snapshot.getPendingClarification().getType())) return false;
        String normalized = message == null ? "" : message.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？!?、]+", "");
        return normalized.matches("^(对|对的|是|是的|确认|确认执行|好|好的|可以|就这样|没问题|yes|ok|okay|confirm)$");
    }

    private String pendingActionMessage(CopilotConversationSnapshot snapshot, String fallback) {
        if (snapshot != null && snapshot.getRecentTurns() != null) {
            for (int index = snapshot.getRecentTurns().size() - 1; index >= 0; index--) {
                CopilotConversationTurn turn = snapshot.getRecentTurns().get(index);
                if (turn != null && "USER".equalsIgnoreCase(turn.getRole())
                        && turn.getIntent() == CopilotIntent.ACTION_REQUEST
                        && turn.getMessage() != null && !turn.getMessage().isBlank()) return turn.getMessage();
            }
        }
        return fallback;
    }

    /** Copy only the public routing fields; the persisted turn keeps the real user message. */
    private CopilotRouteRequest copyWithMessage(CopilotRouteRequest source, String message) {
        CopilotRouteRequest copy = new CopilotRouteRequest();
        copy.setMessage(message);
        copy.setSessionId(source.getSessionId()); copy.setConversationId(source.getConversationId());
        copy.setClientMessageId(source.getClientMessageId()); copy.setContextVersion(source.getContextVersion());
        copy.setUseConversationContext(source.getUseConversationContext()); copy.setPageContext(source.getPageContext());
        copy.setFarmId(source.getFarmId()); copy.setFieldId(source.getFieldId()); copy.setDiagnosisId(source.getDiagnosisId());
        copy.setRecommendationId(source.getRecommendationId()); copy.setRunId(source.getRunId()); copy.setActionId(source.getActionId());
        copy.setTaskId(source.getTaskId()); copy.setOutcomeId(source.getOutcomeId());
        return copy;
    }

    private boolean isMemoryIntent(CopilotIntent intent) { return intent == CopilotIntent.MEMORY_WRITE_REQUEST || intent == CopilotIntent.MEMORY_QUERY || intent == CopilotIntent.MEMORY_FORGET_REQUEST; }

    private CopilotMemoryDecision memoryDecision(CopilotIntent intent, CopilotRouteRequest request, CopilotStableContext active, Long userId) {
        if (intent == CopilotIntent.MEMORY_WRITE_REQUEST) return longTermMemory.decideWrite(request.getMessage(), active, request.getPageContext(), userId);
        if (intent == CopilotIntent.MEMORY_FORGET_REQUEST) return longTermMemory.decideForget(request.getMessage(), active, request.getPageContext(), userId);
        return CopilotMemoryDecision.memories(longTermMemory.retrieve(userId, active, request.getPageContext()));
    }

    private CopilotRouteResult memoryResult(String traceId, CopilotIntent intent, CopilotStableContext active, CopilotMemoryDecision decision, double confidence) {
        CopilotCapability capability = capability(intent);
        if (!decision.accepted()) {
            if (decision.question() != null) return result(traceId, intent, CopilotResolutionStatus.CLARIFICATION_REQUIRED, active, capability, true, decision.question(), "CLARIFY_CONTEXT", null, confidence);
            return result(traceId, intent, CopilotResolutionStatus.UNSUPPORTED, active, capability, false, "该长期记忆请求不符合受支持的安全或结构化范围。", "REJECT", null, confidence);
        }
        CopilotRouteResult result = result(traceId, intent, CopilotResolutionStatus.RESOLVED, active, capability, false, null, switch (intent) { case MEMORY_WRITE_REQUEST -> "MEMORY_WRITE"; case MEMORY_QUERY -> "READ_MEMORY"; case MEMORY_FORGET_REQUEST -> "REVOKE_MEMORY"; default -> "REJECT"; }, null, confidence);
        if (intent == CopilotIntent.MEMORY_QUERY) result.setRelevantLongTermMemories(decision.memories().stream().map(CopilotMemoryView::from).toList());
        return result;
    }

    private CopilotStableContext mergeStable(CopilotRouteRequest request, CopilotStableContext session) {
        CopilotPageContext page = request == null ? null : request.getPageContext();
        return CopilotStableContext.builder().farmId(first(request == null ? null : request.getFarmId(), page == null ? null : page.getFarmId(), session == null ? null : session.getFarmId()))
                .fieldId(first(request == null ? null : request.getFieldId(), page == null ? null : page.getFieldId(), session == null ? null : session.getFieldId())).build();
    }

    private String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return null; }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private CopilotRouteResult result(String traceId, CopilotIntent intent, CopilotResolutionStatus status, CopilotStableContext context,
                                      CopilotCapability capability, boolean clarification, String question, String nextAction,
                                      String safeRoute, double confidence) {
        return CopilotRouteResult.builder().traceId(traceId).intent(intent).resolutionStatus(status).resolvedContext(context)
                .capability(capability).requiresClarification(clarification).clarificationQuestion(question)
                .allowedNextAction(nextAction).safeRoute(safeRoute).confidence(confidence).build();
    }

    private CopilotCapability capability(CopilotIntent intent) {
        return switch (intent) {
            case GENERAL_QA -> CopilotCapability.CONTEXTUAL_QA;
            case FARM_STATUS_QUERY -> CopilotCapability.FARM_CONTEXT_READ;
            case FIELD_STATUS_QUERY -> CopilotCapability.FIELD_CONTEXT_READ;
            case PRIORITIES_QUERY -> CopilotCapability.PRIORITIES_READ;
            case HISTORICAL_CASE_QUERY -> CopilotCapability.HISTORICAL_CASE_READ;
            case DIAGNOSIS_QUERY -> CopilotCapability.DIAGNOSIS_READ;
            case DIAGNOSIS_REQUEST -> CopilotCapability.DIAGNOSIS_CREATE;
            case RECOMMENDATION_QUERY -> CopilotCapability.RECOMMENDATION_READ;
            case ACTION_REQUEST -> CopilotCapability.AGENT_START;
            case OPERATIONS_STATUS_QUERY -> CopilotCapability.OPERATIONS_READ;
            case OUTCOME_QUERY -> CopilotCapability.OUTCOME_READ;
            case KNOWLEDGE_QUERY -> CopilotCapability.KNOWLEDGE_SEARCH;
            case NAVIGATION_REQUEST -> CopilotCapability.NAVIGATE;
            case MEMORY_WRITE_REQUEST -> CopilotCapability.MEMORY_WRITE;
            case MEMORY_QUERY -> CopilotCapability.MEMORY_READ;
            case MEMORY_FORGET_REQUEST -> CopilotCapability.MEMORY_REVOKE;
            case CLARIFICATION_REQUIRED, UNSUPPORTED -> null;
        };
    }

    private String nextAction(CopilotIntent intent, CopilotResolutionStatus status) {
        if (status == CopilotResolutionStatus.UNSUPPORTED) return "REJECT";
        if (status == CopilotResolutionStatus.FORBIDDEN) return "DENY";
        if (status == CopilotResolutionStatus.NOT_FOUND) return "RESELECT_CONTEXT";
        if (status == CopilotResolutionStatus.CLARIFICATION_REQUIRED || status == CopilotResolutionStatus.STALE) return "CLARIFY_CONTEXT";
        return switch (intent) {
            case GENERAL_QA -> "ANSWER_WITH_CONTEXT";
            case FARM_STATUS_QUERY -> "READ_FARM_CONTEXT";
            case FIELD_STATUS_QUERY -> "READ_FIELD_CONTEXT";
            case PRIORITIES_QUERY -> "READ_PRIORITIES";
            case HISTORICAL_CASE_QUERY -> "READ_HISTORICAL_CASES";
            case DIAGNOSIS_QUERY -> "READ_DIAGNOSIS";
            case DIAGNOSIS_REQUEST -> "DIAGNOSIS_CREATE";
            case RECOMMENDATION_QUERY -> "READ_RECOMMENDATIONS";
            case ACTION_REQUEST -> "START_AGENT_REQUIRES_APPROVAL";
            case OPERATIONS_STATUS_QUERY -> "READ_OPERATIONS";
            case OUTCOME_QUERY -> "READ_OUTCOME";
            case KNOWLEDGE_QUERY -> "SEARCH_KNOWLEDGE";
            case NAVIGATION_REQUEST -> "NAVIGATE_SAFE_ROUTE";
            case MEMORY_WRITE_REQUEST -> "MEMORY_WRITE";
            case MEMORY_QUERY -> "READ_MEMORY";
            case MEMORY_FORGET_REQUEST -> "REVOKE_MEMORY";
            case CLARIFICATION_REQUIRED -> "CLARIFY_CONTEXT";
            case UNSUPPORTED -> "REJECT";
        };
    }
}

package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** V1 bounded conversation lifecycle, ownership, idempotency and optimistic versioning. */
@Service
public class CopilotConversationService {
    private static final Duration TTL = Duration.ofHours(2);
    private final CopilotConversationRepository repository;
    private final CopilotContextAssembler assembler;
    private final ObjectMapper mapper;

    @Autowired
    public CopilotConversationService(CopilotConversationRepository repository, CopilotContextAssembler assembler, ObjectMapper mapper) {
        this.repository = repository;
        this.assembler = assembler;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper;
    }

    public CopilotConversationService(CopilotConversationRepository repository) {
        this(repository, new CopilotContextAssembler(10, 4000), new ObjectMapper().findAndRegisterModules());
    }

    public ConversationHandle begin(CopilotRouteRequest request, Long userId) {
        String conversationId = normalize(request == null ? null : request.getConversationId());
        CopilotConversationSnapshot snapshot = conversationId == null ? create(userId) : load(conversationId, userId);
        if (expired(snapshot)) throw new CopilotConversationException("CONVERSATION_EXPIRED", "conversation 已过期，请创建新对话。", 409);
        String clientMessageId = normalize(request == null ? null : request.getClientMessageId());
        if (clientMessageId != null) {
            Optional<CopilotConversationTurn> prior = repository.findByClientMessageId(snapshot.getConversationId(), userId, clientMessageId);
            if (prior.isPresent() && prior.get().getResultJson() != null) {
                try {
                    CopilotRouteResult cached = mapper.readValue(prior.get().getResultJson(), CopilotRouteResult.class);
                    cached.setConversationId(snapshot.getConversationId());
                    cached.setTurnId(prior.get().getTurnId());
                    cached.setContextVersion(snapshot.getVersion());
                    cached.setMemoryStatus("DUPLICATE_REPLAY");
                    return new ConversationHandle(snapshot, cached);
                } catch (Exception ignored) {
                    throw new CopilotConversationException("CONVERSATION_CONFLICT", "重复请求结果不可恢复，请重试新的 clientMessageId。", 409);
                }
            }
        }
        if (request != null && request.getContextVersion() != null && request.getContextVersion() != snapshot.getVersion()) {
            throw new CopilotConversationException("CONVERSATION_CONFLICT", "conversation 上下文版本已变化，请刷新后重试。", 409);
        }
        snapshot.setPendingClarification(expirePending(snapshot));
        return new ConversationHandle(snapshot, null);
    }

    public CopilotConversationSnapshot create(Long userId) {
        Instant now = Instant.now();
        CopilotConversationSnapshot snapshot = CopilotConversationSnapshot.builder()
                .conversationId("conversation-" + UUID.randomUUID()).userId(userId).status("ACTIVE")
                .activeContext(new CopilotStableContext()).summary("{}").summaryVersion(0).turnCount(0).version(0)
                .createdAt(now).updatedAt(now).expiresAt(now.plus(TTL)).recentTurns(new ArrayList<>()).build();
        return repository.create(snapshot);
    }

    public CopilotConversationView view(String conversationId, Long userId) {
        CopilotConversationSnapshot snapshot = load(conversationId, userId);
        if (expired(snapshot)) throw new CopilotConversationException("CONVERSATION_EXPIRED", "conversation 已过期，请创建新对话。", 409);
        CopilotContextAssembler.AssembledContext context = assembler.assemble(snapshot, null);
        return CopilotConversationView.builder().conversationId(snapshot.getConversationId()).status(snapshot.getStatus())
                .activeContext(context.activeContext()).lastIntent(snapshot.getLastIntent()).summary(snapshot.getSummary())
                .summaryVersion(snapshot.getSummaryVersion()).turnCount(snapshot.getTurnCount()).contextVersion(snapshot.getVersion())
                .pendingClarification(context.pendingClarification()).recentTurns(context.recentTurns())
                .relevantLongTermMemories(context.relevantLongTermMemories()).build();
    }

    public CopilotContextAssembler.AssembledContext assemble(CopilotConversationSnapshot snapshot, CopilotPageContext page) {
        return assembler.assemble(snapshot, page);
    }

    public void commit(ConversationHandle handle, CopilotRouteRequest request, CopilotRouteResult result) {
        if (handle == null || handle.duplicateResult() != null) return;
        CopilotConversationSnapshot current = handle.snapshot();
        CopilotConversationSnapshot next = current.copy();
        next.setVersion(current.getVersion() + 1);
        next.setTurnCount(current.getTurnCount() + 2);
        next.setUpdatedAt(Instant.now());
        next.setLastIntent(result.getIntent());
        if ((result.getResolutionStatus() == CopilotResolutionStatus.RESOLVED || result.getResolutionStatus() == CopilotResolutionStatus.CLARIFICATION_REQUIRED) && result.getResolvedContext() != null) {
            next.setActiveContext(result.getResolvedContext().copy());
            next.getActiveContext().setLastIntent(result.getIntent());
            next.getActiveContext().setUpdatedAt(next.getUpdatedAt());
        }
        if (result.isRequiresClarification()) {
            String type = result.getIntent() == CopilotIntent.ACTION_REQUEST || result.getIntent() == CopilotIntent.RECOMMENDATION_QUERY ? "RECOMMENDATION" : "CONTEXT";
            next.setPendingClarification(CopilotPendingClarification.builder().type(type)
                    .question(result.getClarificationQuestion()).createdAtTurn(next.getTurnCount()).expiresAfterTurns(3).build());
        } else {
            next.setPendingClarification(null);
        }
        next.setSummary(summary(next, result));
        next.setSummaryVersion(current.getSummaryVersion() + 1);
        String clientMessageId = normalize(request == null ? null : request.getClientMessageId());
        String userTurnId = "turn-" + UUID.randomUUID();
        String traceId = result.getTraceId();
        Instant now = Instant.now();
        CopilotConversationTurn userTurn = CopilotConversationTurn.builder().turnId(userTurnId)
                .conversationId(next.getConversationId()).userId(next.getUserId()).role("USER")
                .message(redact(request == null ? "" : request.getMessage())).intent(result.getIntent()).traceId(traceId)
                .clientMessageId(clientMessageId).resultJson(write(result)).createdAt(now).build();
        CopilotConversationTurn assistantTurn = CopilotConversationTurn.builder().turnId("turn-" + UUID.randomUUID())
                .conversationId(next.getConversationId()).userId(next.getUserId()).role("ASSISTANT")
                .message(assistantMessage(result)).intent(result.getIntent()).traceId(traceId).createdAt(now).build();
        if (!repository.commit(next, current.getVersion(), List.of(userTurn, assistantTurn))) {
            throw new CopilotConversationException("CONVERSATION_CONFLICT", "conversation 上下文已被其他请求更新，请刷新后重试。", 409);
        }
        result.setConversationId(next.getConversationId());
        result.setTurnId(userTurnId);
        result.setContextVersion(next.getVersion());
        result.setMemoryStatus("PERSISTED");
    }

    public void updatePersistedResult(CopilotRouteResult result, Long userId) {
        if (result == null || result.getConversationId() == null || result.getTurnId() == null) return;
        try { repository.updateResult(result.getConversationId(), result.getTurnId(), userId, mapper.writeValueAsString(result)); }
        catch (Exception ignored) { /* response remains authoritative; the original turn is still persisted */ }
    }

    public void appendAssistantAnswer(String conversationId, Long userId, CopilotIntent intent, String traceId, String message) {
        if (normalize(conversationId) == null || normalize(message) == null) return;
        load(conversationId, userId);
        repository.appendTurn(CopilotConversationTurn.builder()
                .turnId("turn-" + UUID.randomUUID()).conversationId(conversationId).userId(userId)
                .role("ASSISTANT").message(redact(message)).intent(intent).traceId(traceId)
                .createdAt(Instant.now()).build());
    }

    private CopilotConversationSnapshot load(String conversationId, Long userId) {
        return repository.find(conversationId, userId).orElseThrow(() -> new CopilotConversationException("CONVERSATION_NOT_FOUND", "conversation 不存在或不属于当前用户。", 404));
    }

    private boolean expired(CopilotConversationSnapshot snapshot) {
        return snapshot == null || !"ACTIVE".equals(snapshot.getStatus()) || snapshot.getExpiresAt() != null && snapshot.getExpiresAt().isBefore(Instant.now());
    }

    private CopilotPendingClarification expirePending(CopilotConversationSnapshot snapshot) {
        CopilotPendingClarification pending = snapshot.getPendingClarification();
        if (pending == null) return null;
        return snapshot.getTurnCount() - pending.getCreatedAtTurn() >= pending.getExpiresAfterTurns() ? null : pending;
    }

    private String summary(CopilotConversationSnapshot snapshot, CopilotRouteResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("topic", result.getIntent() == null ? null : result.getIntent().name());
        value.put("confirmedFacts", List.of("server_validated_context"));
        value.put("userGoals", result.getIntent() == null ? List.of() : List.of(result.getIntent().name()));
        value.put("resolvedEntityIds", snapshot.getActiveContext());
        value.put("lastOutcome", result.getAllowedNextAction());
        value.put("pendingQuestion", snapshot.getPendingClarification() == null ? null : snapshot.getPendingClarification().getQuestion());
        try { return mapper.writeValueAsString(value); } catch (Exception error) { return "{}"; }
    }

    private String assistantMessage(CopilotRouteResult result) {
        if (result.isRequiresClarification()) return result.getClarificationQuestion() == null ? "请明确上下文。" : result.getClarificationQuestion();
        if (result.getResolutionStatus() == CopilotResolutionStatus.UNSUPPORTED) return "当前请求不属于 FarMap Copilot 支持范围。";
        if (result.getResolutionStatus() == CopilotResolutionStatus.FORBIDDEN) return "当前账号无权访问该上下文，未继续处理。";
        if (result.getIntent() == CopilotIntent.GENERAL_QA) return "已识别为普通问答，正在读取当前页面上下文并调用快速回答模型。";
        if (result.getIntent() == CopilotIntent.ACTION_REQUEST) return "动作请求已解析；下一步仍需人工批准，未直接创建 Operations 任务。";
        if (result.getIntent() == CopilotIntent.PRIORITIES_QUERY) return "已识别为今日优先事项查询，正在读取农场诊断、天气与 Operations 数据。";
        if (result.getIntent() == CopilotIntent.FARM_STATUS_QUERY) return "已识别为农场状态查询，正在汇总地块、天气、诊断和待处理任务。";
        if (result.getIntent() == CopilotIntent.FIELD_STATUS_QUERY) return "已识别为地块状态查询，正在读取地块、天气和近期农情。";
        if (result.getIntent() == CopilotIntent.KNOWLEDGE_QUERY) return "已识别为农业知识检索，正在查询当前知识库并返回来源。";
        if (result.getIntent() == CopilotIntent.HISTORICAL_CASE_QUERY) return "已识别为历史病例检索，将基于现场图片和专家确认索引返回相似案例。";
        if (result.getIntent() == CopilotIntent.DIAGNOSIS_QUERY) return "已识别为诊断查询，正在读取已保存 Diagnosis 与 Evidence。";
        if (result.getIntent() == CopilotIntent.DIAGNOSIS_REQUEST) return "已准备 AI 调查入口；创建 Diagnosis 和 Agent Run 前仍需确认地块。";
        return "请求已完成上下文解析，正在准备可验证的数据结果。";
    }

    private String write(CopilotRouteResult result) {
        try { return mapper.writeValueAsString(result); } catch (Exception error) { throw new CopilotConversationException("CONVERSATION_PERSISTENCE_FAILED", "conversation 结果无法持久化。", 500); }
    }

    private String redact(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
                .replaceAll("(?i)(password|passwd|api[_-]?key|jwt|secret)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ConversationHandle(CopilotConversationSnapshot snapshot, CopilotRouteResult duplicateResult) {}
}

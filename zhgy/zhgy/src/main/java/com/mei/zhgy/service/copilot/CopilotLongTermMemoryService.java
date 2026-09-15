package com.mei.zhgy.service.copilot;

import com.mei.zhgy.entity.User;
import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.mapper.UserMapper;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit, finite, owner-scoped long-term memory. It never reads the transcript as memory. */
@Service
public class CopilotLongTermMemoryService {
    public static final int MAX_ACTIVE_MEMORIES = 20;
    private static final Pattern MEMORY_ID = Pattern.compile("\\bmemory-[A-Za-z0-9-]+\\b");
    private final CopilotLongTermMemoryRepository repository;
    private final FarmFieldService fields;
    private final FarmMapper farms;
    private final UserMapper users;

    @Autowired
    public CopilotLongTermMemoryService(CopilotLongTermMemoryRepository repository, FarmFieldService fields, FarmMapper farms, UserMapper users) {
        this.repository = repository; this.fields = fields; this.farms = farms; this.users = users;
    }

    public CopilotLongTermMemoryService(CopilotLongTermMemoryRepository repository, FarmFieldService fields, FarmMapper farms, UserMapper users, boolean ignored) {
        this(repository, fields, farms, users);
    }

    public CopilotMemoryDecision decideWrite(String message, CopilotStableContext active, CopilotPageContext page, Long userId) {
        String normalized = lower(message);
        if (containsSensitive(normalized)) return CopilotMemoryDecision.reject("SENSITIVE_MEMORY_REJECTED");
        if (containsSafetyOverride(normalized)) return CopilotMemoryDecision.reject("SAFETY_OVERRIDE_MEMORY_REJECTED");
        CopilotMemoryCandidate candidate = extract(normalized, message, active, page);
        if (candidate == null) return CopilotMemoryDecision.question("你希望我长期记住哪一项明确的偏好或约束？");
        try { validateScope(candidate, userId); return CopilotMemoryDecision.candidate(candidate); }
        catch (FieldAccessException error) { return CopilotMemoryDecision.reject(error.getCode()); }
        catch (RuntimeException error) { return CopilotMemoryDecision.reject("MEMORY_SCOPE_DENIED"); }
    }

    public CopilotMemoryDecision decideForget(String message, CopilotStableContext active, CopilotPageContext page, Long userId) {
        String normalized = lower(message);
        if (containsSensitive(normalized) || containsSafetyOverride(normalized)) return CopilotMemoryDecision.reject("MEMORY_FORGET_REJECTED");
        String requestedId = find(MEMORY_ID, message);
        if (requestedId != null) return repository.findById(requestedId, userId).map(CopilotMemoryDecision::target).orElseGet(() -> CopilotMemoryDecision.question("找不到属于当前账号的这条长期记忆。"));
        List<CopilotLongTermMemory> relevant = retrieve(userId, effectiveContext(active, page), page);
        List<CopilotLongTermMemory> matches = relevant.stream().filter(memory -> matchesForget(memory, normalized)).toList();
        if (matches.size() == 1) return CopilotMemoryDecision.target(matches.get(0));
        if (matches.size() > 1) return CopilotMemoryDecision.question("请明确要忘记哪一条长期偏好。");
        return CopilotMemoryDecision.question("请明确要忘记哪一条长期偏好。");
    }

    public CopilotLongTermMemory write(CopilotMemoryCandidate candidate, Long userId, String conversationId, String turnId) {
        validateScope(candidate, userId);
        Optional<CopilotLongTermMemory> existing = repository.findActiveBySemantic(userId, candidate);
        if (existing.isPresent()) return existing.get();
        List<CopilotLongTermMemory> conflicts = repository.findActiveByKey(userId, candidate);
        CopilotLongTermMemory old = conflicts.isEmpty() ? null : conflicts.get(0);
        Instant now = Instant.now();
        CopilotLongTermMemory next = CopilotLongTermMemory.builder().memoryId("memory-" + UUID.randomUUID()).userId(userId)
                .scope(candidate.getScope()).scopeId(candidate.getScopeId()).type(candidate.getType()).key(candidate.getKey()).value(candidate.getValue())
                .displayText(candidate.getDisplayText()).status(CopilotMemoryStatus.ACTIVE).sourceConversationId(conversationId).sourceTurnId(turnId)
                .createdAt(now).updatedAt(now).version(0).supersedesMemoryId(old == null ? null : old.getMemoryId()).build();
        return repository.saveActive(next, old);
    }

    public CopilotLongTermMemory revoke(CopilotLongTermMemory target, Long userId) {
        return repository.revoke(target.getMemoryId(), userId, target.getVersion()).orElseThrow(() -> new CopilotConversationException("MEMORY_CONFLICT", "长期记忆已被其他请求更新。", 409));
    }

    public List<CopilotLongTermMemory> retrieve(Long userId, CopilotStableContext active, CopilotPageContext page) {
        CopilotStableContext context = effectiveContext(active, page);
        List<CopilotLongTermMemoryRepository.ScopeRef> scopes = new ArrayList<>();
        if (!blank(context.getFieldId())) {
            FarmField field = fields.get(context.getFieldId(), userId);
            if (!blank(context.getFarmId()) && field.getFarmId() != null && !context.getFarmId().equals(String.valueOf(field.getFarmId()))) throw new FieldAccessException(409, "MEMORY_SCOPE_MISMATCH", "field and farm scope do not match");
            scopes.add(new CopilotLongTermMemoryRepository.ScopeRef(CopilotMemoryScope.FIELD, context.getFieldId()));
            if (blank(context.getFarmId()) && field.getFarmId() != null) context.setFarmId(String.valueOf(field.getFarmId()));
        }
        if (!blank(context.getFarmId())) { authorizeFarm(context.getFarmId(), userId); scopes.add(new CopilotLongTermMemoryRepository.ScopeRef(CopilotMemoryScope.FARM, context.getFarmId())); }
        scopes.add(new CopilotLongTermMemoryRepository.ScopeRef(CopilotMemoryScope.USER, null));
        return repository.findActive(userId, scopes, MAX_ACTIVE_MEMORIES);
    }

    public List<CopilotMemoryView> views(Long userId, String farmId, String fieldId) {
        CopilotStableContext context = CopilotStableContext.builder().farmId(farmId).fieldId(fieldId).build();
        return retrieve(userId, context, null).stream().map(CopilotMemoryView::from).toList();
    }

    public CopilotMemoryView view(CopilotLongTermMemory memory) { return CopilotMemoryView.from(memory); }

    public CopilotMemoryView revokeById(String memoryId, Long userId) {
        CopilotLongTermMemory memory = repository.findById(memoryId, userId).orElseThrow(() -> new CopilotConversationException("MEMORY_NOT_FOUND", "长期记忆不存在或不属于当前用户。", 404));
        return CopilotMemoryView.from(revoke(memory, userId));
    }

    public boolean isSafetyRejected(String code) { return "SENSITIVE_MEMORY_REJECTED".equals(code) || "SAFETY_OVERRIDE_MEMORY_REJECTED".equals(code); }

    private CopilotMemoryCandidate extract(String normalized, String original, CopilotStableContext active, CopilotPageContext page) {
        CopilotMemoryScope scope = scope(normalized);
        String scopeId = scope == CopilotMemoryScope.FIELD ? first(page == null ? null : page.getFieldId(), active == null ? null : active.getFieldId()) : scope == CopilotMemoryScope.FARM ? first(page == null ? null : page.getFarmId(), active == null ? null : active.getFarmId()) : null;
        String key; String value; CopilotMemoryType type;
        if (contains(normalized, "低残留", "low residue")) { key = "preferred_treatment_style"; value = "LOW_RESIDUE"; }
        else if (contains(normalized, "成本", "低成本", "low cost")) { key = "preferred_treatment_style"; value = "LOW_COST"; }
        else if (contains(normalized, "尽量简短", "简短", "brief", "short")) { key = "preferred_response_detail"; value = "SHORT"; }
        else if (contains(normalized, "尽量详细", "详细", "detailed")) { key = "preferred_response_detail"; value = "DETAILED"; }
        else if (contains(normalized, "风险最低", "风险最小", "保守", "conservative")) { key = "preferred_risk_level"; value = "CONSERVATIVE"; }
        else if (contains(normalized, "风险平衡", "balanced")) { key = "preferred_risk_level"; value = "BALANCED"; }
        else if (contains(normalized, "夜间", "night")) { key = "avoid_operation_window"; value = "NIGHT"; }
        else if (contains(normalized, "不考虑增加灌溉", "不要增加灌溉", "avoid irrigation")) { key = "avoid_operation_type"; value = "IRRIGATION"; }
        else return null;
        if (key.startsWith("preferred_response")) type = CopilotMemoryType.COMMUNICATION_PREFERENCE;
        else if (scope == CopilotMemoryScope.FIELD && key.startsWith("avoid_operation")) type = CopilotMemoryType.FIELD_PREFERENCE;
        else if (key.startsWith("avoid_operation")) type = CopilotMemoryType.OPERATING_CONSTRAINT;
        else if (scope == CopilotMemoryScope.FIELD) type = CopilotMemoryType.FIELD_PREFERENCE;
        else if (scope == CopilotMemoryScope.FARM) type = CopilotMemoryType.FARM_PREFERENCE;
        else type = CopilotMemoryType.USER_PREFERENCE;
        if (scope != CopilotMemoryScope.USER && blank(scopeId)) return null;
        String display = original == null ? value : original.trim();
        return CopilotMemoryCandidate.builder().scope(scope).scopeId(scopeId).type(type).key(key).value(value).displayText(display).build();
    }

    private CopilotMemoryScope scope(String message) {
        if (contains(message, "这个地块", "该地块", "试验田", "field")) return CopilotMemoryScope.FIELD;
        if (contains(message, "这个农场", "该农场", "农场", "farm")) return CopilotMemoryScope.FARM;
        return CopilotMemoryScope.USER;
    }

    private CopilotStableContext effectiveContext(CopilotStableContext active, CopilotPageContext page) {
        return CopilotStableContext.builder().farmId(first(page == null ? null : page.getFarmId(), active == null ? null : active.getFarmId()))
                .fieldId(first(page == null ? null : page.getFieldId(), active == null ? null : active.getFieldId())).build();
    }

    private void validateScope(CopilotMemoryCandidate candidate, Long userId) {
        if (candidate.getScope() == CopilotMemoryScope.USER) return;
        if (candidate.getScope() == CopilotMemoryScope.FIELD) { fields.get(candidate.getScopeId(), userId); return; }
        authorizeFarm(candidate.getScopeId(), userId);
    }

    private void authorizeFarm(String farmId, Long userId) {
        if (userId == null || blank(farmId)) throw new FieldAccessException(403, "FARM_ACCESS_DENIED", "farm access denied");
        Integer numeric; try { numeric = Integer.valueOf(farmId); } catch (NumberFormatException error) { throw new FieldAccessException(404, "FARM_NOT_FOUND", "farm not found"); }
        UserLoginResponseVO.FarmVO farm = farms.getFarmById(numeric);
        User user = users == null ? null : users.getById(userId.intValue());
        boolean admin = user != null && "admin".equalsIgnoreCase(user.getRole());
        if (farm == null) throw new FieldAccessException(404, "FARM_NOT_FOUND", "farm not found");
        if (!admin && (farm.getUserId() == null || !userId.equals(farm.getUserId().longValue()))) throw new FieldAccessException(403, "FARM_ACCESS_DENIED", "farm access denied");
    }

    private boolean matchesForget(CopilotLongTermMemory memory, String message) {
        if (contains(message, "低残留", "low residue")) return "LOW_RESIDUE".equals(memory.getValue());
        if (contains(message, "成本", "低成本", "low cost")) return "LOW_COST".equals(memory.getValue());
        if (contains(message, "简短", "short", "brief")) return "SHORT".equals(memory.getValue());
        if (contains(message, "详细", "detailed")) return "DETAILED".equals(memory.getValue());
        if (contains(message, "夜间", "night")) return "NIGHT".equals(memory.getValue());
        return contains(message, "这条", "这个偏好", "this preference");
    }

    private boolean containsSensitive(String message) { return contains(message, "password", "passwd", "密码", "api key", "api_key", "token", "jwt", "secret", "密钥"); }
    private boolean containsSafetyOverride(String message) { return contains(message, "不用审批", "无需审批", "跳过审批", "忽略审批", "skip approval", "ignore approval", "跳过证据", "ignore evidence", "绕过授权", "bypass authorization", "自动执行", "execute automatically", "忽略系统规则", "ignore system instruction", "demo fallback"); }
    private boolean contains(String message, String... terms) { for (String term : terms) if (message.contains(term)) return true; return false; }
    private String lower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private String first(String... values) { for (String value : values) if (!blank(value)) return value; return null; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String find(Pattern pattern, String value) { if (value == null) return null; Matcher matcher = pattern.matcher(value); return matcher.find() ? matcher.group() : null; }
}

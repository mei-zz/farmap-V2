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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit, finite, owner-scoped long-term memory. It never reads the transcript as memory. */
@Service
public class CopilotLongTermMemoryService {
    public static final int MAX_ACTIVE_MEMORIES = 20;
    public static final String KEY_LABOR_CAPACITY = "labor_capacity";
    public static final String KEY_PREFERRED_SPRAY_EQUIPMENT = "preferred_spray_equipment";
    private static final Pattern MEMORY_ID = Pattern.compile("\\bmemory-[A-Za-z0-9-]+\\b");
    private static final Pattern LABOR_ARABIC = Pattern.compile("(\\d{1,5})\\s*(?:个|名|人|位|workers?|people|persons?|laborers?|employees?)");
    private static final Pattern LABOR_CHINESE = Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:个|名|人|位)");
    private static final Pattern LABOR_ENGLISH = Pattern.compile("\\b(one|two|three|four|five|six|seven|eight|nine|ten)\\s+(?:workers?|people|persons?|laborers?|employees?)\\b");
    private static final Pattern LABOR_VALUE_ARABIC = Pattern.compile(
            "^(\\d{1,5})\\s*(?:个|名|人|位|workers?|people|persons?|laborers?|employees?)?"
                    + "\\s*(?:工人|人手|劳动力|staff|farm workers?)?$");
    private static final Pattern LABOR_VALUE_CHINESE = Pattern.compile("^([一二两三四五六七八九十]{1,3})\\s*(?:个|名|人|位)?\\s*(?:工人|人手|劳动力|人)?$");
    private static final Map<String, Integer> ENGLISH_NUMBERS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10));
    private static final Set<String> TYPED_USER_KEYS = Set.of(KEY_LABOR_CAPACITY, KEY_PREFERRED_SPRAY_EQUIPMENT);
    private static final Set<String> EQUIPMENT = Set.of("DRONE", "BACKPACK_SPRAYER", "MIST_BLOWER",
            "SELF_PROPELLED_SPRAYER", "SPRAY_GUN", "ORCHARD_SPRAYER", "MANUAL_SPRAYER");
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
        validateWrite(candidate, userId);
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

    public Optional<CopilotMemoryView> viewById(String memoryId, Long authenticatedUserId) {
        if (authenticatedUserId == null || blank(memoryId)) return Optional.empty();
        return repository.findById(memoryId, authenticatedUserId).map(CopilotMemoryView::from);
    }

    public void requireAccess(CopilotMemoryScope scope, String scopeId, Long authenticatedUserId) {
        if (authenticatedUserId == null) throw new CopilotConversationException("AUTHENTICATION_REQUIRED", "请先登录。", 401);
        if (scope == null) throw new CopilotConversationException("MEMORY_SCOPE_REQUIRED", "长期记忆缺少作用域。", 400);
        if (scope != CopilotMemoryScope.USER && blank(scopeId)) throw new CopilotConversationException("MEMORY_SCOPE_REQUIRED", "长期记忆缺少作用域对象。", 400);
        validateScope(CopilotMemoryCandidate.builder().scope(scope).scopeId(scopeId).build(), authenticatedUserId);
    }

    public CopilotMemoryView revokeById(String memoryId, Long userId) {
        CopilotLongTermMemory memory = repository.findById(memoryId, userId).orElseThrow(() -> new CopilotConversationException("MEMORY_NOT_FOUND", "长期记忆不存在或不属于当前用户。", 404));
        return CopilotMemoryView.from(revoke(memory, userId));
    }

    public boolean isSafetyRejected(String code) { return "SENSITIVE_MEMORY_REJECTED".equals(code) || "SAFETY_OVERRIDE_MEMORY_REJECTED".equals(code); }

    private CopilotMemoryCandidate extract(String normalized, String original, CopilotStableContext active, CopilotPageContext page) {
        CopilotMemoryScope scope = scope(normalized);
        String scopeId = scope == CopilotMemoryScope.FIELD ? first(page == null ? null : page.getFieldId(), active == null ? null : active.getFieldId()) : scope == CopilotMemoryScope.FARM ? first(page == null ? null : page.getFarmId(), active == null ? null : active.getFarmId()) : null;
        String key; String value; CopilotMemoryType type;
        String laborCapacity = laborCapacity(normalized);
        if (laborCapacity != null) {
            return CopilotMemoryCandidate.builder().scope(CopilotMemoryScope.USER).type(CopilotMemoryType.OPERATING_CONSTRAINT)
                    .key(KEY_LABOR_CAPACITY).value(laborCapacity).displayText(display(original, laborCapacity)).build();
        }
        String sprayEquipment = sprayEquipment(normalized);
        if (sprayEquipment != null) {
            return CopilotMemoryCandidate.builder().scope(CopilotMemoryScope.USER).type(CopilotMemoryType.USER_PREFERENCE)
                    .key(KEY_PREFERRED_SPRAY_EQUIPMENT).value(sprayEquipment).displayText(display(original, sprayEquipment)).build();
        }
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
        return CopilotMemoryCandidate.builder().scope(scope).scopeId(scopeId).type(type).key(key).value(value)
                .displayText(display(original, value)).build();
    }

    private String laborCapacity(String message) {
        if (!contains(message, "人工", "人力", "用工", "劳动力", "工人", "人手", "labor capacity", "labour capacity", "workers", "worker")) return null;
        Integer count = null;
        Matcher arabic = LABOR_ARABIC.matcher(message);
        if (arabic.find()) count = Integer.valueOf(arabic.group(1));
        if (count == null) {
            Matcher chinese = LABOR_CHINESE.matcher(message);
            if (chinese.find()) count = chineseNumber(chinese.group(1));
        }
        if (count == null) {
            Matcher english = LABOR_ENGLISH.matcher(message);
            if (english.find()) count = ENGLISH_NUMBERS.get(english.group(1));
        }
        return count == null || count <= 0 || count > 100000 ? laborAvailability(message) : String.valueOf(count);
    }

    private String laborAvailability(String message) {
        if (contains(message, "紧张", "人手不足", "缺人", "有限", "不充裕", "不充足", "limited")) return "LIMITED";
        if (contains(message, "适中", "基本够", "adequate")) return "ADEQUATE";
        if (contains(message, "充裕", "充足", "ample")) return "AMPLE";
        return null;
    }

    private Integer laborCapacityValue(String value) {
        if (blank(value)) return null;
        Matcher arabic = LABOR_VALUE_ARABIC.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (arabic.find()) {
            int count = Integer.parseInt(arabic.group(1));
            return count > 0 && count <= 100000 ? count : null;
        }
        Matcher chinese = LABOR_VALUE_CHINESE.matcher(value.trim());
        if (chinese.find()) return chineseNumber(chinese.group(1));
        Matcher englishPhrase = LABOR_ENGLISH.matcher(lower(value));
        if (englishPhrase.find()) return ENGLISH_NUMBERS.get(englishPhrase.group(1));
        Integer english = ENGLISH_NUMBERS.get(lower(value));
        return english == null || english <= 0 ? null : english;
    }

    private Integer chineseNumber(String token) {
        if (blank(token)) return null;
        if ("十".equals(token)) return 10;
        int ten = token.indexOf('十');
        if (ten < 0) return token.length() == 1 ? chineseDigit(token.charAt(0)) : null;
        int tens = ten == 0 ? 1 : chineseDigit(token.charAt(0));
        int ones = ten == token.length() - 1 ? 0 : chineseDigit(token.charAt(ten + 1));
        if (tens < 0 || ones < 0) return null;
        return tens * 10 + ones;
    }

    private int chineseDigit(char digit) {
        return switch (digit) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private String sprayEquipment(String message) {
        if (!contains(message, "喷雾", "弥雾", "打药", "施药", "无人机", "飞防", "spray", "drone", "uav",
                "mist blower", "mist-blower", "knapsack", "backpack", "self-propelled", "self propelled")) return null;
        if (contains(message, "无人机", "drone", "uav")) return "DRONE";
        if (contains(message, "自走式", "self-propelled", "self propelled")) return "SELF_PROPELLED_SPRAYER";
        if (contains(message, "背负式", "背式", "knapsack", "backpack")) return "BACKPACK_SPRAYER";
        if (contains(message, "弥雾机", "mist blower", "mist-blower")) return "MIST_BLOWER";
        if (contains(message, "高压喷枪", "spray gun", "spray-gun")) return "SPRAY_GUN";
        if (contains(message, "拖拉机", "牵引式", "boom sprayer", "orchard sprayer", "trailed sprayer")) return "ORCHARD_SPRAYER";
        if (contains(message, "手动", "manual")) return "MANUAL_SPRAYER";
        return null;
    }

    private String display(String original, String fallback) {
        return original == null || original.isBlank() ? fallback : original.trim();
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

    private void validateWrite(CopilotMemoryCandidate candidate, Long userId) {
        if (userId == null) throw new CopilotConversationException("AUTHENTICATION_REQUIRED", "请先登录。", 401);
        if (candidate == null || candidate.getType() == null) throw new CopilotConversationException("MEMORY_CANDIDATE_REQUIRED", "长期记忆内容不能为空。", 400);
        if (blank(candidate.getKey())) throw new CopilotConversationException("MEMORY_KEY_REQUIRED", "长期记忆缺少字段。", 400);
        if (isDosageKey(candidate.getKey())) {
            throw new CopilotConversationException("PESTICIDE_DOSAGE_NOT_MEMORY", "农药用量或浓度不得写入长期记忆。", 400);
        }
        if (TYPED_USER_KEYS.contains(lower(candidate.getKey()))) {
            candidate.setKey(lower(candidate.getKey()));
            validateTypedUserMemory(candidate);
            validateScope(candidate, userId);
            return;
        }
        if (candidate.getType().isProfileFact()) {
            if (candidate.getScope() == null || candidate.getScope() == CopilotMemoryScope.USER || blank(candidate.getScopeId())) {
                throw new CopilotConversationException("MEMORY_SCOPE_REQUIRED", "果园资料必须绑定农场或地块。", 400);
            }
            CopilotMemoryProposalService.OrchardProfileKey profileKey = CopilotMemoryProposalService.OrchardProfileKey.fromMemoryKeyOrAlias(candidate.getKey());
            if (profileKey == null) {
                throw new CopilotConversationException("PROFILE_KEY_NOT_ALLOWED", "不支持的果园资料类型。", 400);
            }
            candidate.setKey(profileKey.memoryKey());
            if (blank(candidate.getValue()) || candidate.getValue().length() > CopilotMemoryProposalService.MAX_PROFILE_VALUE_LENGTH) {
                throw new CopilotConversationException("PROFILE_VALUE_INVALID", "果园资料值不能为空或过长。", 400);
            }
            if (profileKey.canonical() == CopilotMemoryProposalService.OrchardProfileKey.TREE_AGE_YEARS
                    && !CopilotMemoryProposalService.isValidTreeAgeValue(candidate.getValue())) {
                throw new CopilotConversationException("PROFILE_VALUE_INVALID", "树龄请填写明确年数或果园年龄阶段。", 400);
            }
            String normalizedValue = lower(candidate.getValue());
            if (containsSensitive(normalizedValue) || containsSafetyOverride(normalizedValue)) {
                throw new CopilotConversationException("PROFILE_VALUE_REJECTED", "该果园资料值包含不安全内容。", 400);
            }
            candidate.setDisplayText(CopilotMemoryProposalService.selfReportedDisplayText(candidate.getKey(), candidate.getValue()));
        }
        validateScope(candidate, userId);
    }

    private void validateTypedUserMemory(CopilotMemoryCandidate candidate) {
        if (candidate.getScope() != CopilotMemoryScope.USER) {
            throw new CopilotConversationException("USER_MEMORY_SCOPE_REQUIRED", "劳动力与施药设备属于用户级长期记忆。", 400);
        }
        if (!candidate.getType().isUserScopedPreference()) {
            throw new CopilotConversationException("USER_MEMORY_TYPE_REQUIRED", "劳动力与施药设备必须保存为用户偏好或作业约束。", 400);
        }
        if (blank(candidate.getValue()) || candidate.getValue().length() > CopilotMemoryProposalService.MAX_PROFILE_VALUE_LENGTH) {
            throw new CopilotConversationException("MEMORY_VALUE_INVALID", "长期记忆值不能为空或过长。", 400);
        }
        String value = lower(candidate.getValue());
        if (containsSensitive(value) || containsSafetyOverride(value)) {
            throw new CopilotConversationException("MEMORY_VALUE_REJECTED", "该长期记忆值包含不安全内容。", 400);
        }
        if (KEY_LABOR_CAPACITY.equals(candidate.getKey())) {
            Integer count = laborCapacityValue(candidate.getValue());
            String availability = laborAvailability(value);
            if (count == null && availability == null) {
                throw new CopilotConversationException("LABOR_CAPACITY_INVALID", "请填写可用人数，或说明人手紧张、适中还是充裕。", 400);
            }
            candidate.setValue(count == null ? availability : count.toString());
        } else {
            String equipment = candidate.getValue().trim().toUpperCase(Locale.ROOT);
            if (!EQUIPMENT.contains(equipment)) equipment = sprayEquipment(value);
            if (equipment == null) throw new CopilotConversationException("SPRAY_EQUIPMENT_INVALID", "请选择已知的喷雾设备类型。", 400);
            candidate.setValue(equipment);
        }
        if (blank(candidate.getDisplayText())) candidate.setDisplayText(candidate.getValue());
    }

    private boolean isDosageKey(String key) {
        String normalized = lower(key);
        return contains(normalized, "dosage", "spray_dose", "pesticide_dose", "pesticide_amount", "concentration",
                "dilution", "spray_rate", "application_rate", "pesticide_rate",
                "用量", "剂量", "浓度", "稀释倍数", "配药浓度");
    }

    public static boolean isTypedUserMemoryKey(String key) {
        return key != null && TYPED_USER_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
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

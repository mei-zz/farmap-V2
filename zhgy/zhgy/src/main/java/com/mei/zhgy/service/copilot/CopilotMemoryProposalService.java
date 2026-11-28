package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confirmation-backed, owner-scoped orchard profile proposals.
 *
 * <p>A proposal is durable metadata only. It never becomes active long-term
 * memory until the authenticated owner confirms the server-issued proposal ID.
 * Confirmation revalidates current farm/field access and delegates the actual
 * memory write to {@link CopilotLongTermMemoryService#write}.</p>
 */
@Service
public class CopilotMemoryProposalService {
    public static final String SOURCE_SELF_REPORTED = "SELF_REPORTED";
    public static final int MAX_PROFILE_VALUE_LENGTH = 200;
    public static final List<String> PROFILE_SCHEMA_SLOTS = List.of(
            "plum_variety", "tree_age_years", "elevation_band", "irrigation_facility",
            "quality_certification", "historical_high_risk_pests");
    private static final Pattern PROPOSAL_ID = Pattern.compile("^proposal-[A-Za-z0-9-]{8,84}$");
    private static final Pattern PROFILE_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern ELEVATION_RANGE = Pattern.compile("[-–—~～]|至|到|between|\\bto\\b");
    private static final Pattern ELEVATION_BOUND = Pattern.compile("以上|以下|大于|小于|超过|不足|above|below|over|under|at least|at most");
    private static final int MAX_PROVENANCE_LENGTH = 96;

    private final ProposalStore store;
    private final CopilotLongTermMemoryService memories;
    private final FarmFieldService fields;

    @Autowired
    public CopilotMemoryProposalService(JdbcTemplate jdbc, CopilotLongTermMemoryService memories, FarmFieldService fields) {
        this(new JdbcProposalStore(jdbc), memories, fields);
    }

    CopilotMemoryProposalService(ProposalStore store, CopilotLongTermMemoryService memories, FarmFieldService fields) {
        this.store = store;
        this.memories = memories;
        this.fields = fields;
    }

    @PostConstruct
    public void ensureSchema() {
        store.ensureSchema();
    }

    public ProposalView propose(Long authenticatedUserId, ProposalRequest request) {
        requireAuthenticated(authenticatedUserId);
        if (request == null) throw invalid("PROFILE_PROPOSAL_INVALID", "缺少果园资料提案内容。");
        return propose(authenticatedUserId, request.farmId(), request.fieldId(), request.profileKey(), request.value(),
                request.conversationId(), request.turnId());
    }

    public ProposalView propose(ProposalRequest request, Long authenticatedUserId) {
        return propose(authenticatedUserId, request);
    }

    public ProposalView propose(Long authenticatedUserId, String farmId, String fieldId, String profileKey, String value) {
        return propose(authenticatedUserId, farmId, fieldId, profileKey, value, null, null);
    }

    public ProposalView propose(Long authenticatedUserId, String farmId, String fieldId, String profileKey,
                                String value, String conversationId, String turnId) {
        OrchardProfileKey key = OrchardProfileKey.parse(profileKey);
        if (key == null) throw invalid("PROFILE_KEY_NOT_ALLOWED", "不支持的果园资料字段。");
        return propose(authenticatedUserId, farmId, fieldId, key, value, conversationId, turnId);
    }

    public ProposalView propose(Long authenticatedUserId, String farmId, String fieldId, OrchardProfileKey profileKey,
                                String value, String conversationId, String turnId) {
        requireAuthenticated(authenticatedUserId);
        if (profileKey == null) throw invalid("PROFILE_KEY_NOT_ALLOWED", "不支持的果园资料字段。");
        store.ensureSchema();
        String normalizedValue = normalizeValue(profileKey, value);
        ScopeBinding binding = resolveBinding(authenticatedUserId, farmId, fieldId);
        Instant now = Instant.now();
        ProposalRecord proposal = new ProposalRecord("proposal-" + UUID.randomUUID(), authenticatedUserId,
                binding.farmId(), binding.fieldId(), binding.scope(), profileKey, normalizedValue, ProposalStatus.PENDING,
                provenance(conversationId), provenance(turnId), now, null, null);
        store.insert(proposal);
        return view(proposal);
    }

    @Transactional
    public ProposalConfirmation confirm(String proposalId, Long authenticatedUserId) {
        requireAuthenticated(authenticatedUserId);
        store.ensureSchema();
        if (proposalId == null || !PROPOSAL_ID.matcher(proposalId).matches()) throw proposalNotFound();
        ProposalRecord proposal = store.findForOwnerForUpdate(proposalId, authenticatedUserId).orElseThrow(this::proposalNotFound);
        revalidateAccess(proposal, authenticatedUserId);
        if (proposal.status() == ProposalStatus.IGNORED) throw conflict("MEMORY_PROPOSAL_IGNORED", "这条资料提案已忽略。");

        if (proposal.status() == ProposalStatus.CONFIRMED && proposal.memoryId() != null) {
            CopilotMemoryView memory = memories.viewById(proposal.memoryId(), authenticatedUserId).orElse(null);
            return new ProposalConfirmation(view(proposal), memory);
        }

        CopilotLongTermMemory written = memories.write(candidate(proposal), authenticatedUserId,
                firstNonBlank(proposal.sourceConversationId(), "proposal-" + proposal.proposalId()),
                firstNonBlank(proposal.sourceTurnId(), proposal.proposalId()));
        Instant confirmedAt = Instant.now();
        boolean updated = store.markConfirmed(proposal.proposalId(), authenticatedUserId, written.getMemoryId(), confirmedAt);
        if (!updated) {
            ProposalRecord current = store.findForOwner(proposal.proposalId(), authenticatedUserId).orElseThrow(this::proposalNotFound);
            if (current.status() == ProposalStatus.CONFIRMED && current.memoryId() != null) {
                CopilotMemoryView memory = memories.viewById(current.memoryId(), authenticatedUserId)
                        .orElse(CopilotMemoryView.from(written));
                return new ProposalConfirmation(view(current), memory);
            }
            throw conflict("MEMORY_PROPOSAL_CONFLICT", "该果园资料提案已被其他请求处理，请重新读取。");
        }
        ProposalRecord confirmed = store.findForOwner(proposal.proposalId(), authenticatedUserId)
                .orElseGet(() -> proposal.confirmed(written.getMemoryId(), confirmedAt));
        return new ProposalConfirmation(view(confirmed), CopilotMemoryView.from(written));
    }

    public ProposalConfirmation confirm(Long authenticatedUserId, String proposalId) {
        return confirm(proposalId, authenticatedUserId);
    }

    @Transactional
    public ProposalView ignore(String proposalId, Long authenticatedUserId) {
        requireAuthenticated(authenticatedUserId);
        if (proposalId == null || !PROPOSAL_ID.matcher(proposalId).matches()) throw proposalNotFound();
        var proposal = store.findForOwnerForUpdate(proposalId, authenticatedUserId).orElseThrow(this::proposalNotFound);
        revalidateAccess(proposal, authenticatedUserId);
        if (proposal.status() == ProposalStatus.IGNORED) return view(proposal);
        if (proposal.status() != ProposalStatus.PENDING || !store.markIgnored(proposalId, authenticatedUserId)) {
            throw conflict("MEMORY_PROPOSAL_CONFLICT", "这条资料提案已处理，请重新读取。");
        }
        return view(proposal.ignored());
    }

    public ProposalView read(String proposalId, Long authenticatedUserId) {
        requireAuthenticated(authenticatedUserId);
        if (proposalId == null || !PROPOSAL_ID.matcher(proposalId).matches()) throw proposalNotFound();
        var proposal = store.findForOwner(proposalId, authenticatedUserId).orElseThrow(this::proposalNotFound);
        revalidateAccess(proposal, authenticatedUserId);
        return view(proposal);
    }

    /**
     * Typed, owner-scoped projection for callers that need to distinguish an exact elevation from a band.
     * Exact elevation is selected only from the most specific scope with elevation information: field first,
     * then farm. A field band therefore suppresses a farm-level exact elevation instead of silently overriding it.
     */
    public OrchardProfileProjection projectProfile(Long authenticatedUserId, String farmId, String fieldId) {
        requireAuthenticated(authenticatedUserId);
        ScopeBinding binding = resolveBinding(authenticatedUserId, farmId, fieldId);
        List<CopilotLongTermMemory> active = memories.retrieve(authenticatedUserId,
                CopilotStableContext.builder().farmId(binding.farmId()).fieldId(binding.fieldId()).build(), null);
        return projectProfile(binding, active);
    }

    public Map<String, ProfileValue> profileValues(Long authenticatedUserId, String farmId, String fieldId) {
        return projectProfile(authenticatedUserId, farmId, fieldId).compatibleValues();
    }

    public Map<String, Object> profileMap(Long authenticatedUserId, String farmId, String fieldId) {
        return projectProfile(authenticatedUserId, farmId, fieldId).promptProfile();
    }

    private OrchardProfileProjection projectProfile(ScopeBinding binding, List<CopilotLongTermMemory> active) {
        Map<String, ProfileValue> found = new LinkedHashMap<>();
        List<CopilotLongTermMemory> elevationMemories = new ArrayList<>();
        for (CopilotLongTermMemory memory : active) {
            if (memory.getType() != CopilotMemoryType.ORCHARD_PROFILE || !inProfileScope(memory, binding)) continue;
            OrchardProfileKey key = OrchardProfileKey.fromMemoryKey(memory.getKey());
            if (key == null) continue;
            if (key.isElevation()) {
                elevationMemories.add(memory);
                continue;
            }
            OrchardProfileKey slot = key.canonical();
            found.putIfAbsent(slot.wireKey(), profileValue(slot.wireKey(), memory));
        }

        ElevationProjection elevation = projectElevation(elevationMemories);
        if (elevation.canonicalBand() != null) found.put(OrchardProfileKey.ELEVATION_BAND.wireKey(), elevation.canonicalBand());

        Map<String, ProfileValue> canonical = new LinkedHashMap<>();
        for (OrchardProfileKey slot : OrchardProfileKey.canonicalValues()) {
            ProfileValue value = found.get(slot.wireKey());
            if (value != null) canonical.put(slot.wireKey(), value);
        }

        Map<String, ProfileValue> compatible = new LinkedHashMap<>();
        for (OrchardProfileKey slot : OrchardProfileKey.canonicalValues()) {
            ProfileValue value = canonical.get(slot.wireKey());
            if (value == null) continue;
            compatible.put(slot.wireKey(), value);
            OrchardProfileKey legacy = slot.legacyAlias();
            if (legacy != null) compatible.put(legacy.wireKey(), value);
        }
        if (elevation.legacyElevation() != null) compatible.put(OrchardProfileKey.ELEVATION.wireKey(), elevation.legacyElevation());

        Map<String, Object> promptProfile = new LinkedHashMap<>();
        compatible.forEach((key, value) -> promptProfile.put(key, value.value()));
        if (elevation.exactMeters() != null) promptProfile.put("elevationMeters", elevation.exactMeters());
        ProfileValue treeAge = canonical.get(OrchardProfileKey.TREE_AGE_YEARS.wireKey());
        if (treeAge != null) {
            Integer years = treeAgeYears(treeAge.value());
            if (years != null) promptProfile.put("treeAgeYears", years);
            String group = treeAgeClass(treeAge.value());
            if (group != null) promptProfile.put("treeAgeClass", group);
        }
        promptProfile.put("source", SOURCE_SELF_REPORTED);
        promptProfile.put("verified", false);
        promptProfile.put("authoritative", false);

        return new OrchardProfileProjection(canonical, compatible, promptProfile, elevation.exactMeters(),
                elevation.band(), SOURCE_SELF_REPORTED, false, false);
    }

    private ElevationProjection projectElevation(List<CopilotLongTermMemory> memories) {
        List<CopilotLongTermMemory> field = memories.stream().filter(memory -> memory.getScope() == CopilotMemoryScope.FIELD).toList();
        List<CopilotLongTermMemory> selected = field.isEmpty()
                ? memories.stream().filter(memory -> memory.getScope() == CopilotMemoryScope.FARM).toList()
                : field;

        ProfileValue canonicalBand = null;
        ProfileValue legacyElevation = null;
        Double exactMeters = null;
        String band = null;
        for (CopilotLongTermMemory memory : selected) {
            OrchardProfileKey key = OrchardProfileKey.fromMemoryKey(memory.getKey());
            if (key == OrchardProfileKey.ELEVATION) {
                if (legacyElevation == null) legacyElevation = profileValue(OrchardProfileKey.ELEVATION.wireKey(), memory);
                if (exactMeters == null) exactMeters = exactElevationMeters(memory.getValue());
            } else if (key == OrchardProfileKey.ELEVATION_BAND) {
                if (canonicalBand == null) canonicalBand = profileValue(OrchardProfileKey.ELEVATION_BAND.wireKey(), memory);
                if (band == null && isElevationBand(memory.getValue())) band = memory.getValue();
                if (exactMeters == null) exactMeters = exactElevationMeters(memory.getValue());
            }
        }
        if (band == null && exactMeters != null) {
            band = exactMeters < 500 ? "低山" : exactMeters <= 800 ? "中山" : "高山";
            ProfileValue source = canonicalBand != null ? canonicalBand : legacyElevation;
            if (source != null) canonicalBand = new ProfileValue(OrchardProfileKey.ELEVATION_BAND.wireKey(), band,
                    source.source(), source.verified(), source.authoritative(), source.memoryId(), source.updatedAt());
        }
        return new ElevationProjection(canonicalBand, legacyElevation, exactMeters, band);
    }

    private ProfileValue profileValue(String key, CopilotLongTermMemory memory) {
        return new ProfileValue(key, memory.getValue(), SOURCE_SELF_REPORTED, false, false,
                memory.getMemoryId(), memory.getUpdatedAt());
    }

    private static Double exactElevationMeters(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replace(",", "").replace("，", "").trim();
        if (ELEVATION_RANGE.matcher(normalized).find() || ELEVATION_BOUND.matcher(normalized).find()) return null;
        Matcher matcher = PROFILE_NUMBER.matcher(normalized);
        if (!matcher.find()) return null;
        String number = matcher.group();
        if (matcher.find()) return null;
        double meters = Double.parseDouble(number);
        return meters >= 0 && meters <= 9000 ? meters : null;
    }

    private static boolean isElevationBand(String value) {
        if (value == null || value.isBlank()) return false;
        return ELEVATION_RANGE.matcher(value).find() || ELEVATION_BOUND.matcher(value).find()
                || exactElevationMeters(value) == null;
    }

    private boolean inProfileScope(CopilotLongTermMemory memory, ScopeBinding binding) {
        if (memory.getScope() == CopilotMemoryScope.FIELD) return binding.fieldId() != null && binding.fieldId().equals(memory.getScopeId());
        if (memory.getScope() == CopilotMemoryScope.FARM) return binding.farmId().equals(memory.getScopeId());
        return false;
    }

    public static boolean isAllowedMemoryKey(String memoryKey) {
        return OrchardProfileKey.fromMemoryKeyOrAlias(memoryKey) != null;
    }

    public static boolean isValidTreeAgeValue(String value) {
        return treeAgeClass(value) != null;
    }

    public static String selfReportedDisplayText(String memoryKey, String value) {
        OrchardProfileKey key = OrchardProfileKey.fromMemoryKeyOrAlias(memoryKey);
        return key == null ? "用户自述果园资料（未经核验，不作为认证或登记依据）：" + value
                : selfReportedDisplayText(key, value);
    }

    public static String selfReportedDisplayText(OrchardProfileKey key, String value) {
        return "用户自述果园资料（未经核验，不作为认证或登记依据）：" + key.label() + " = " + value;
    }

    private ProposalView view(ProposalRecord proposal) {
        return new ProposalView(proposal.proposalId(), proposal.status(), proposal.scope(), proposal.farmId(),
                proposal.fieldId(), proposal.profileKey(), proposal.profileKey().wireKey(), proposal.value(),
                selfReportedDisplayText(proposal.profileKey(), proposal.value()), SOURCE_SELF_REPORTED,
                false, false, proposal.sourceConversationId(), proposal.sourceTurnId(), proposal.createdAt(),
                proposal.confirmedAt(), proposal.memoryId());
    }

    private CopilotMemoryCandidate candidate(ProposalRecord proposal) {
        return CopilotMemoryCandidate.builder().scope(proposal.scope())
                .scopeId(proposal.scope() == CopilotMemoryScope.FIELD ? proposal.fieldId() : proposal.farmId())
                .type(CopilotMemoryType.ORCHARD_PROFILE).key(proposal.profileKey().memoryKey()).value(proposal.value())
                .displayText(selfReportedDisplayText(proposal.profileKey(), proposal.value())).build();
    }

    private ScopeBinding resolveBinding(Long authenticatedUserId, String farmId, String fieldId) {
        String requestedFarm = trimToNull(farmId);
        String requestedField = trimToNull(fieldId);
        if (requestedField != null) {
            FarmField field = fields.get(requestedField, authenticatedUserId);
            String fieldFarm = field.getFarmId() == null ? null : String.valueOf(field.getFarmId());
            if (fieldFarm == null) throw mismatch();
            if (requestedFarm != null && !requestedFarm.equals(fieldFarm)) throw mismatch();
            memories.requireAccess(CopilotMemoryScope.FARM, fieldFarm, authenticatedUserId);
            return new ScopeBinding(CopilotMemoryScope.FIELD, requestedField, fieldFarm);
        }
        if (requestedFarm == null) throw new FieldAccessException(403, "FARM_REQUIRED", "farm context is required");
        memories.requireAccess(CopilotMemoryScope.FARM, requestedFarm, authenticatedUserId);
        return new ScopeBinding(CopilotMemoryScope.FARM, null, requestedFarm);
    }

    private void revalidateAccess(ProposalRecord proposal, Long authenticatedUserId) {
        if (proposal.scope() == CopilotMemoryScope.FIELD) {
            FarmField field = fields.get(proposal.fieldId(), authenticatedUserId);
            if (field.getFarmId() == null || !proposal.farmId().equals(String.valueOf(field.getFarmId()))) throw mismatch();
            memories.requireAccess(CopilotMemoryScope.FARM, proposal.farmId(), authenticatedUserId);
            return;
        }
        if (proposal.scope() != CopilotMemoryScope.FARM) throw invalid("MEMORY_SCOPE_REQUIRED", "果园资料提案必须绑定农场或地块。");
        memories.requireAccess(CopilotMemoryScope.FARM, proposal.farmId(), authenticatedUserId);
    }

    private String normalizeValue(OrchardProfileKey key, String value) {
        if (value == null || value.isBlank()) throw invalid("PROFILE_VALUE_REQUIRED", "果园资料值不能为空。");
        String normalized = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (normalized.length() > MAX_PROFILE_VALUE_LENGTH) throw invalid("PROFILE_VALUE_TOO_LONG", "果园资料值过长。");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (containsSensitive(lower) || containsSafetyOverride(lower)) {
            throw invalid("PROFILE_VALUE_REJECTED", "该果园资料值包含不安全内容。");
        }
        if (key.canonical() == OrchardProfileKey.TREE_AGE_YEARS && !isValidTreeAgeValue(normalized)) {
            throw invalid("PROFILE_VALUE_INVALID", "树龄请填写 1 至 500 年，或选择幼树、初果期、盛果期、老龄园。");
        }
        return normalized;
    }

    private static Integer treeAgeYears(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = PROFILE_NUMBER.matcher(value.replace(",", "").replace("，", ""));
        if (!matcher.find()) return null;
        String number = matcher.group();
        if (matcher.find()) return null;
        double years = Double.parseDouble(number);
        return years >= 1 && years <= 500 && years == Math.rint(years) ? (int) years : null;
    }

    private static String treeAgeClass(String value) {
        if (value == null) return null;
        if (value.contains("幼树")) return "幼树";
        if (value.contains("初果期")) return "初果期";
        if (value.contains("盛果期")) return "盛果期";
        if (value.contains("老龄园")) return "老龄园";
        Integer years = treeAgeYears(value);
        return years == null ? null : years <= 3 ? "幼树" : years <= 5 ? "初果期" : years <= 15 ? "盛果期" : "老龄园";
    }

    private void requireAuthenticated(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new CopilotConversationException("AUTHENTICATION_REQUIRED", "请先登录。", 401);
        }
    }

    private CopilotConversationException invalid(String code, String message) {
        return new CopilotConversationException(code, message, 400);
    }

    private FieldAccessException mismatch() {
        return new FieldAccessException(409, "MEMORY_SCOPE_MISMATCH", "field and farm scope do not match");
    }

    private CopilotConversationException proposalNotFound() {
        return new CopilotConversationException("MEMORY_PROPOSAL_NOT_FOUND", "果园资料提案不存在或不属于当前账号。", 404);
    }

    private CopilotConversationException conflict(String code, String message) {
        return new CopilotConversationException(code, message, 409);
    }

    private String provenance(String value) {
        String cleaned = trimToNull(value);
        if (cleaned == null) return null;
        return cleaned.length() <= MAX_PROVENANCE_LENGTH ? cleaned : cleaned.substring(0, MAX_PROVENANCE_LENGTH);
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean containsSensitive(String value) {
        return contains(value, "password", "passwd", "密码", "api key", "api_key", "token", "jwt", "secret", "密钥");
    }

    private boolean containsSafetyOverride(String value) {
        return contains(value, "不用审批", "无需审批", "跳过审批", "忽略审批", "skip approval", "ignore approval",
                "跳过证据", "ignore evidence", "绕过授权", "bypass authorization", "自动执行", "execute automatically",
                "忽略系统规则", "ignore system instruction");
    }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private record ScopeBinding(CopilotMemoryScope scope, String fieldId, String farmId) {}

    public enum OrchardProfileKey {
        VARIETY("variety", "品种", "orchard_profile_variety", "cultivar", "crop variety", "品种"),
        PLUM_VARIETY("plum_variety", "李子品种", "orchard_profile_plum_variety",
                "plum variety", "plum-cultivar", "plum cultivar", "李子品种"),
        TREE_AGE("tree_age", "树龄", "orchard_profile_tree_age", "treeAge", "tree-age", "tree age", "age", "树龄"),
        TREE_AGE_YEARS("tree_age_years", "树龄（年）", "orchard_profile_tree_age_years",
                "tree age years", "tree-age-years", "age_years", "树龄（年）", "树龄年"),
        ELEVATION("elevation", "海拔", "orchard_profile_elevation", "altitude", "elevation_m", "海拔"),
        ELEVATION_BAND("elevation_band", "海拔带", "orchard_profile_elevation_band",
                "elevation band", "elevation-band", "altitude_band", "altitude band", "海拔带", "海拔范围"),
        CERTIFICATION("certification", "认证", "orchard_profile_certification", "certification_status", "cert", "认证"),
        QUALITY_CERTIFICATION("quality_certification", "质量认证", "orchard_profile_quality_certification",
                "quality certification", "quality-certification", "quality_cert", "product_certification", "质量认证", "品质认证"),
        IRRIGATION("irrigation", "灌溉", "orchard_profile_irrigation", "irrigation_type", "irrigation_method", "灌溉"),
        IRRIGATION_FACILITY("irrigation_facility", "灌溉设施", "orchard_profile_irrigation_facility",
                "irrigation facility", "irrigation infrastructure", "watering_facility", "irrigation_system", "灌溉设施"),
        HISTORICAL_HIGH_RISK_PESTS("historical_high_risk_pests", "历史高风险病虫害",
                "orchard_profile_historical_high_risk_pests", "high_risk_pests", "pest_history", "historical_pests",
                "history_pests", "历史高风险病虫害", "高风险病虫害", "病虫害历史");

        private final String wireKey;
        private final String label;
        private final String memoryKey;
        private final List<String> aliases;

        OrchardProfileKey(String wireKey, String label, String memoryKey, String... aliases) {
            this.wireKey = wireKey;
            this.label = label;
            this.memoryKey = memoryKey;
            this.aliases = List.of(aliases);
        }

        public String wireKey() { return wireKey; }
        public String label() { return label; }
        public String memoryKey() { return memoryKey; }
        public boolean isElevation() { return this == ELEVATION || this == ELEVATION_BAND; }

        public OrchardProfileKey canonical() {
            return switch (this) {
                case VARIETY -> PLUM_VARIETY;
                case TREE_AGE -> TREE_AGE_YEARS;
                case ELEVATION -> ELEVATION_BAND;
                case CERTIFICATION -> QUALITY_CERTIFICATION;
                case IRRIGATION -> IRRIGATION_FACILITY;
                default -> this;
            };
        }

        public OrchardProfileKey legacyAlias() {
            return switch (this) {
                case PLUM_VARIETY -> VARIETY;
                case TREE_AGE_YEARS -> TREE_AGE;
                case QUALITY_CERTIFICATION -> CERTIFICATION;
                case IRRIGATION_FACILITY -> IRRIGATION;
                default -> null;
            };
        }

        public static List<OrchardProfileKey> canonicalValues() {
            return List.of(PLUM_VARIETY, TREE_AGE_YEARS, ELEVATION_BAND, IRRIGATION_FACILITY,
                    QUALITY_CERTIFICATION, HISTORICAL_HIGH_RISK_PESTS);
        }

        public static OrchardProfileKey parse(String value) {
            String token = token(value);
            if (token == null) return null;
            for (OrchardProfileKey key : values()) {
                if (token.equals(token(key.wireKey)) || token.equals(token(key.name())) || token.equals(token(key.memoryKey))) return key;
                for (String alias : key.aliases) if (token.equals(token(alias))) return key;
            }
            return null;
        }

        public static OrchardProfileKey fromMemoryKey(String memoryKey) {
            if (memoryKey == null) return null;
            for (OrchardProfileKey key : values()) if (key.memoryKey.equals(memoryKey)) return key;
            return null;
        }

        public static OrchardProfileKey fromMemoryKeyOrAlias(String value) {
            OrchardProfileKey exact = fromMemoryKey(value);
            return exact == null ? parse(value) : exact;
        }

        private static String token(String value) {
            if (value == null || value.isBlank()) return null;
            return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }
    }

    public enum ProposalStatus {
        PENDING,
        CONFIRMED,
        IGNORED
    }

    public record ProposalRequest(String farmId, String fieldId, String profileKey, String value,
                                  String conversationId, String turnId) {}

    public record ProposalView(String proposalId, ProposalStatus status, CopilotMemoryScope scope, String farmId,
                               String fieldId, OrchardProfileKey profileKey, String key, String value, String displayText,
                               String source, boolean verified, boolean authoritative, String sourceConversationId,
                               String sourceTurnId, Instant createdAt, Instant confirmedAt, String memoryId) {}

    public record ProposalConfirmation(ProposalView proposal, CopilotMemoryView memory) {
        public String proposalId() { return proposal == null ? null : proposal.proposalId(); }
        public String memoryId() { return memory == null ? (proposal == null ? null : proposal.memoryId()) : memory.getMemoryId(); }
    }

    /**
     * Canonical orchard profile projection. {@code values} contains only the exact schema slots;
     * {@code compatibleValues} additionally carries legacy aliases for existing callers.
     * Exact elevation is never inferred from a band or converted into a dosage instruction.
     */
    public record OrchardProfileProjection(Map<String, ProfileValue> values,
                                           Map<String, ProfileValue> compatibleValues,
                                           Map<String, Object> promptProfile,
                                           Double exactElevationMeters,
                                           String elevationBand,
                                           String source,
                                           boolean verified,
                                           boolean authoritative) {
        public OrchardProfileProjection {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            compatibleValues = Collections.unmodifiableMap(new LinkedHashMap<>(compatibleValues));
            promptProfile = Collections.unmodifiableMap(new LinkedHashMap<>(promptProfile));
        }

        public boolean hasExactElevation() { return exactElevationMeters != null; }
        public OptionalDouble elevationMeters() {
            return exactElevationMeters == null ? OptionalDouble.empty() : OptionalDouble.of(exactElevationMeters);
        }
        public Map<String, Object> asMap() { return promptProfile; }
    }

    private record ElevationProjection(ProfileValue canonicalBand, ProfileValue legacyElevation,
                                       Double exactMeters, String band) {}

    public record ProfileValue(String key, String value, String source, boolean verified, boolean authoritative,
                               String memoryId, Instant updatedAt) {}

    record ProposalRecord(String proposalId, Long ownerUserId, String farmId, String fieldId, CopilotMemoryScope scope,
                          OrchardProfileKey profileKey, String value, ProposalStatus status, String sourceConversationId,
                          String sourceTurnId, Instant createdAt, Instant confirmedAt, String memoryId) {
        ProposalRecord confirmed(String confirmedMemoryId, Instant confirmedAt) {
            return new ProposalRecord(proposalId, ownerUserId, farmId, fieldId, scope, profileKey, value,
                    ProposalStatus.CONFIRMED, sourceConversationId, sourceTurnId, createdAt, confirmedAt, confirmedMemoryId);
        }
        ProposalRecord ignored() {
            return new ProposalRecord(proposalId, ownerUserId, farmId, fieldId, scope, profileKey, value,
                    ProposalStatus.IGNORED, sourceConversationId, sourceTurnId, createdAt, null, null);
        }
    }

    interface ProposalStore {
        void ensureSchema();
        void insert(ProposalRecord proposal);
        Optional<ProposalRecord> findForOwner(String proposalId, Long ownerUserId);
        Optional<ProposalRecord> findForOwnerForUpdate(String proposalId, Long ownerUserId);
        boolean markConfirmed(String proposalId, Long ownerUserId, String memoryId, Instant confirmedAt);
        boolean markIgnored(String proposalId, Long ownerUserId);
    }

    static final class JdbcProposalStore implements ProposalStore {
        private static final String COLUMNS = "proposal_id,owner_user_id,farm_id,field_id,memory_scope,profile_key,profile_value,status,memory_id,source_conversation_id,source_turn_id,created_at,confirmed_at";
        private final JdbcTemplate jdbc;
        private volatile boolean schemaChecked;

        JdbcProposalStore(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void ensureSchema() {
            if (schemaChecked) return;
            synchronized (this) {
                if (schemaChecked) return;
                jdbc.execute("CREATE TABLE IF NOT EXISTS copilot_memory_proposals (proposal_id VARCHAR(96) PRIMARY KEY,owner_user_id BIGINT NOT NULL,farm_id VARCHAR(128) NOT NULL,field_id VARCHAR(128) NULL,memory_scope VARCHAR(16) NOT NULL,profile_key VARCHAR(32) NOT NULL,profile_value VARCHAR(256) NOT NULL,status VARCHAR(16) NOT NULL,memory_id VARCHAR(96) NULL,source_conversation_id VARCHAR(96) NULL,source_turn_id VARCHAR(96) NULL,created_at TIMESTAMP(3) NOT NULL,confirmed_at TIMESTAMP(3) NULL,version BIGINT NOT NULL DEFAULT 0,INDEX idx_copilot_proposal_owner_status (owner_user_id,status),INDEX idx_copilot_proposal_scope (owner_user_id,farm_id,field_id),INDEX idx_copilot_proposal_memory (memory_id))");
                schemaChecked = true;
            }
        }

        @Override
        public void insert(ProposalRecord proposal) {
            ensureSchema();
            jdbc.update("INSERT INTO copilot_memory_proposals(proposal_id,owner_user_id,farm_id,field_id,memory_scope,profile_key,profile_value,status,memory_id,source_conversation_id,source_turn_id,created_at,confirmed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    proposal.proposalId(), proposal.ownerUserId(), proposal.farmId(), proposal.fieldId(), proposal.scope().name(),
                    proposal.profileKey().name(), proposal.value(), proposal.status().name(), proposal.memoryId(),
                    proposal.sourceConversationId(), proposal.sourceTurnId(), timestamp(proposal.createdAt()), timestamp(proposal.confirmedAt()));
        }

        @Override
        public Optional<ProposalRecord> findForOwner(String proposalId, Long ownerUserId) {
            ensureSchema();
            return jdbc.query("SELECT " + COLUMNS + " FROM copilot_memory_proposals WHERE proposal_id=? AND owner_user_id=?",
                    (rs, row) -> map(rs), proposalId, ownerUserId).stream().findFirst();
        }

        @Override
        public Optional<ProposalRecord> findForOwnerForUpdate(String proposalId, Long ownerUserId) {
            ensureSchema();
            return jdbc.query("SELECT " + COLUMNS + " FROM copilot_memory_proposals WHERE proposal_id=? AND owner_user_id=? FOR UPDATE",
                    (rs, row) -> map(rs), proposalId, ownerUserId).stream().findFirst();
        }

        @Override
        public boolean markConfirmed(String proposalId, Long ownerUserId, String memoryId, Instant confirmedAt) {
            ensureSchema();
            return jdbc.update("UPDATE copilot_memory_proposals SET status='CONFIRMED',memory_id=?,confirmed_at=?,version=version+1 WHERE proposal_id=? AND owner_user_id=? AND status='PENDING' AND memory_id IS NULL",
                    memoryId, timestamp(confirmedAt), proposalId, ownerUserId) == 1;
        }

        @Override
        public boolean markIgnored(String proposalId, Long ownerUserId) {
            return jdbc.update("UPDATE copilot_memory_proposals SET status='IGNORED',version=version+1 WHERE proposal_id=? AND owner_user_id=? AND status='PENDING'",
                    proposalId, ownerUserId) == 1;
        }

        private ProposalRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new ProposalRecord(rs.getString("proposal_id"), rs.getLong("owner_user_id"), rs.getString("farm_id"),
                    rs.getString("field_id"), CopilotMemoryScope.valueOf(rs.getString("memory_scope")),
                    OrchardProfileKey.valueOf(rs.getString("profile_key")), rs.getString("profile_value"),
                    ProposalStatus.valueOf(rs.getString("status")), rs.getString("source_conversation_id"),
                    rs.getString("source_turn_id"), instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("confirmed_at")), rs.getString("memory_id"));
        }

        private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
        private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    }
}

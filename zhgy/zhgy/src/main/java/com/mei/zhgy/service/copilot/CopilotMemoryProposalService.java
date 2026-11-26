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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private static final Pattern PROPOSAL_ID = Pattern.compile("^proposal-[A-Za-z0-9-]{8,84}$");
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
        if (key == null) throw invalid("PROFILE_KEY_NOT_ALLOWED", "只支持品种、树龄、海拔、认证和灌溉资料。");
        return propose(authenticatedUserId, farmId, fieldId, key, value, conversationId, turnId);
    }

    public ProposalView propose(Long authenticatedUserId, String farmId, String fieldId, OrchardProfileKey profileKey,
                                String value, String conversationId, String turnId) {
        requireAuthenticated(authenticatedUserId);
        if (profileKey == null) throw invalid("PROFILE_KEY_NOT_ALLOWED", "只支持品种、树龄、海拔、认证和灌溉资料。");
        store.ensureSchema();
        String normalizedValue = normalizeValue(value);
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

    public ProposalView read(String proposalId, Long authenticatedUserId) {
        requireAuthenticated(authenticatedUserId);
        if (proposalId == null || !PROPOSAL_ID.matcher(proposalId).matches()) throw proposalNotFound();
        var proposal = store.findForOwner(proposalId, authenticatedUserId).orElseThrow(this::proposalNotFound);
        revalidateAccess(proposal, authenticatedUserId);
        return view(proposal);
    }

    public Map<String, ProfileValue> profileValues(Long authenticatedUserId, String farmId, String fieldId) {
        requireAuthenticated(authenticatedUserId);
        ScopeBinding binding = resolveBinding(authenticatedUserId, farmId, fieldId);
        List<CopilotLongTermMemory> active = memories.retrieve(authenticatedUserId,
                CopilotStableContext.builder().farmId(binding.farmId()).fieldId(binding.fieldId()).build(), null);
        Map<String, ProfileValue> values = new LinkedHashMap<>();
        for (CopilotLongTermMemory memory : active) {
            if (memory.getType() != CopilotMemoryType.ORCHARD_PROFILE) continue;
            if (!inProfileScope(memory, binding)) continue;
            OrchardProfileKey key = OrchardProfileKey.fromMemoryKey(memory.getKey());
            if (key == null || values.containsKey(key.wireKey())) continue;
            values.put(key.wireKey(), new ProfileValue(key.wireKey(), memory.getValue(), SOURCE_SELF_REPORTED,
                    false, false, memory.getMemoryId(), memory.getUpdatedAt()));
        }
        return Collections.unmodifiableMap(values);
    }

    public Map<String, Object> profileMap(Long authenticatedUserId, String farmId, String fieldId) {
        Map<String, Object> result = new LinkedHashMap<>();
        profileValues(authenticatedUserId, farmId, fieldId).forEach((key, value) -> result.put(key, value.value()));
        result.put("source", SOURCE_SELF_REPORTED);
        result.put("verified", false);
        result.put("authoritative", false);
        return Collections.unmodifiableMap(result);
    }

    private boolean inProfileScope(CopilotLongTermMemory memory, ScopeBinding binding) {
        if (memory.getScope() == CopilotMemoryScope.FIELD) return binding.fieldId() != null && binding.fieldId().equals(memory.getScopeId());
        if (memory.getScope() == CopilotMemoryScope.FARM) return binding.farmId().equals(memory.getScopeId());
        return false;
    }

    public static boolean isAllowedMemoryKey(String memoryKey) {
        return OrchardProfileKey.fromMemoryKey(memoryKey) != null;
    }

    public static String selfReportedDisplayText(String memoryKey, String value) {
        OrchardProfileKey key = OrchardProfileKey.fromMemoryKey(memoryKey);
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

    private String normalizeValue(String value) {
        if (value == null || value.isBlank()) throw invalid("PROFILE_VALUE_REQUIRED", "果园资料值不能为空。");
        String normalized = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (normalized.length() > MAX_PROFILE_VALUE_LENGTH) throw invalid("PROFILE_VALUE_TOO_LONG", "果园资料值过长。");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (containsSensitive(lower) || containsSafetyOverride(lower)) {
            throw invalid("PROFILE_VALUE_REJECTED", "该果园资料值包含不安全内容。");
        }
        return normalized;
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
        VARIETY("variety", "品种", "orchard_profile_variety", "cultivar", "crop variety"),
        TREE_AGE("tree_age", "树龄", "orchard_profile_tree_age", "treeAge", "tree-age", "tree age", "age"),
        ELEVATION("elevation", "海拔", "orchard_profile_elevation", "altitude", "elevation_m"),
        CERTIFICATION("certification", "认证", "orchard_profile_certification", "certification_status", "cert"),
        IRRIGATION("irrigation", "灌溉", "orchard_profile_irrigation", "irrigation_type", "irrigation_method");

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

        private static String token(String value) {
            if (value == null || value.isBlank()) return null;
            return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }
    }

    public enum ProposalStatus {
        PENDING,
        CONFIRMED
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

    public record ProfileValue(String key, String value, String source, boolean verified, boolean authoritative,
                               String memoryId, Instant updatedAt) {}

    record ProposalRecord(String proposalId, Long ownerUserId, String farmId, String fieldId, CopilotMemoryScope scope,
                          OrchardProfileKey profileKey, String value, ProposalStatus status, String sourceConversationId,
                          String sourceTurnId, Instant createdAt, Instant confirmedAt, String memoryId) {
        ProposalRecord confirmed(String confirmedMemoryId, Instant confirmedAt) {
            return new ProposalRecord(proposalId, ownerUserId, farmId, fieldId, scope, profileKey, value,
                    ProposalStatus.CONFIRMED, sourceConversationId, sourceTurnId, createdAt, confirmedAt, confirmedMemoryId);
        }
    }

    interface ProposalStore {
        void ensureSchema();
        void insert(ProposalRecord proposal);
        Optional<ProposalRecord> findForOwner(String proposalId, Long ownerUserId);
        Optional<ProposalRecord> findForOwnerForUpdate(String proposalId, Long ownerUserId);
        boolean markConfirmed(String proposalId, Long ownerUserId, String memoryId, Instant confirmedAt);
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

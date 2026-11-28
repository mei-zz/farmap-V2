package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.copilot.PlumAgronomyEvidenceCatalog;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.CertificationStatus;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.LabelInput;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.OrchardCertification;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.Provenance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic safety guardrail for named plum pests and diseases.
 *
 * <p>The curated rows contain only general cultural, physical, biological and monitoring
 * measures. They deliberately contain no product name, PHI, registration claim or organic
 * approval. A chemical row is emitted only from a currently valid, verified plum label returned
 * by {@link PlumAgronomyEvidenceCatalog}. The catalog does not expose the label's target pest or
 * disease, so a non-blocked chemical row still requires the caller to confirm that the label
 * covers the requested target before it is shown as an executable recommendation.</p>
 */
@Slf4j
@Service
public class PlumDefenseKnowledgeService {
    public static final String RULE_VERSION = "plum-defense-guardrail/2026-11-27";
    public static final String SOURCE_REF = "curated://plum-defense-guardrail-v1";
    private static final String CROP_CODE = "plum";

    private static final List<String> ALL_STAGES = List.of(
            "dormancy", "germination", "budding", "flowering", "leafing",
            "fruiting", "fruit_development", "ripening", "harvest", "post_harvest");
    private static final List<String> CHEMICAL_FORBIDDEN_STAGES = List.of("flowering", "harvest");
    private static final Map<String, String> STAGE_ALIASES = stageAliases();

    public enum TargetType { DISEASE, PEST }

    public enum MeasureKind { CULTURAL, PHYSICAL, BIOLOGICAL, MONITORING, CHEMICAL_LABEL }

    public enum OrganicPermission {
        APPROVED_BY_VERIFIED_CERTIFICATION,
        PROHIBITED_BY_VERIFIED_CERTIFICATION,
        NOT_APPROVED_BY_VERIFIED_CERTIFICATION,
        NO_CERTIFICATION_RESTRICTION,
        GENERAL_NONCHEMICAL_PRACTICE,
        UNVERIFIED
    }

    public enum GateStatus {
        ELIGIBLE,
        BLOCKED_BY_STAGE,
        BLOCKED_BY_PHI,
        BLOCKED_BY_ORGANIC,
        BLOCKED_BY_LABEL,
        INSUFFICIENT_DATA,
        NOT_APPLICABLE,
        LABEL_TARGET_CONFIRMATION_REQUIRED
    }

    public enum EvidenceScope { GENERAL_GUARDRAIL, VERIFIED_PRODUCT_LABEL }

    public enum AssessmentStatus { READY, UNKNOWN_TARGET, INVALID_REQUEST }

    public record AssessmentRequest(String target, String stageCode, LocalDate assessmentDate,
                                    Integer farmId, String fieldId, String productId) {
        public AssessmentRequest {
            target = trimToNull(target);
            stageCode = trimToNull(stageCode);
            fieldId = trimToNull(fieldId);
            productId = trimToNull(productId);
        }
    }

    public record TargetSummary(String targetCode, String targetName, TargetType targetType,
                                List<String> aliases, List<String> applicableStages,
                                List<String> forbiddenStages, List<String> chemicalForbiddenStages) {
        public TargetSummary {
            aliases = immutable(aliases);
            applicableStages = immutable(applicableStages);
            forbiddenStages = immutable(forbiddenStages);
            chemicalForbiddenStages = immutable(chemicalForbiddenStages);
        }
    }

    public record DefenseRow(String ruleId, String targetCode, String targetName, TargetType targetType,
                             MeasureKind kind, String guidance,
                             List<String> applicableStages, List<String> forbiddenStages,
                             Integer phiDays, OrganicPermission organicPermission, Boolean organicAllowed,
                             GateStatus gateStatus, List<String> gateReasons,
                             EvidenceScope evidenceScope, String sourceRef,
                             String productId, String productName, String activeIngredient,
                             String labelVersion, Boolean labelTargetUseVerified) {
        public DefenseRow {
            applicableStages = immutable(applicableStages);
            forbiddenStages = immutable(forbiddenStages);
            gateReasons = immutable(gateReasons);
        }
    }

    public record Assessment(String ruleVersion, AssessmentStatus status,
                             String requestedTarget, String targetCode, String targetName,
                             TargetType targetType, String stageCode, LocalDate assessmentDate,
                             LocalDate expectedHarvestDate, Integer daysUntilHarvest,
                             String requestedProductId, boolean productVerified,
                             List<DefenseRow> rows, List<String> unavailableData) {
        public Assessment {
            rows = immutable(rows);
            unavailableData = immutable(unavailableData);
        }
    }

    private final PlumAgronomyEvidenceCatalog evidenceCatalog;

    public PlumDefenseKnowledgeService(
            @Autowired(required = false) PlumAgronomyEvidenceCatalog evidenceCatalog) {
        this.evidenceCatalog = evidenceCatalog;
    }

    public List<TargetSummary> targets() {
        return TARGETS.stream().map(target -> new TargetSummary(
                target.code(), target.name(), target.type(), target.aliases(),
                target.managementStages(), target.managementForbiddenStages(),
                target.chemicalForbiddenStages())).toList();
    }

    public List<String> supportedStages() {
        return ALL_STAGES;
    }

    public Assessment assess(AssessmentRequest request) {
        if (request == null) {
            return new Assessment(RULE_VERSION, AssessmentStatus.INVALID_REQUEST,
                    null, null, null, null, null, null, null, null,
                    null, false, List.of(), List.of("request"));
        }

        TargetSeed target = findTarget(request.target());
        if (target == null) {
            return new Assessment(RULE_VERSION, AssessmentStatus.UNKNOWN_TARGET,
                    request.target(), null, null, null, canonicalStage(request.stageCode()),
                    request.assessmentDate(), null, null, request.productId(), false,
                    List.of(), List.of("target"));
        }

        List<String> unavailable = new ArrayList<>();
        String stage = canonicalStage(request.stageCode());
        if (request.stageCode() != null && stage == null) addDataGap(unavailable, "stageCode");

        List<DefenseRow> rows = new ArrayList<>();
        for (MeasureSeed measure : target.measures()) {
            rows.add(generalRow(target, measure, request.stageCode(), stage));
        }

        LocalDate expectedHarvestDate = null;
        Integer daysUntilHarvest = null;
        boolean productVerified = false;
        if (request.productId() != null) {
            if (request.assessmentDate() == null) {
                addDataGap(unavailable, "assessmentDate");
            } else if (evidenceCatalog == null) {
                addDataGap(unavailable, "verifiedLabel");
            } else {
                try {
                    PlumAgronomyEvidenceCatalog.SprayInputs inputs = evidenceCatalog.spray(
                            request.farmId(), request.fieldId(), CROP_CODE,
                            request.productId(), request.assessmentDate());
                    LabelInput label = inputs == null ? null : inputs.label();
                    OrchardCertification certification = inputs == null ? null : inputs.certification();
                    expectedHarvestDate = inputs == null ? null : inputs.expectedHarvestDate();
                    if (isTrustedLabel(label)) {
                        productVerified = true;
                        if (expectedHarvestDate != null) {
                            long days = ChronoUnit.DAYS.between(request.assessmentDate(), expectedHarvestDate);
                            daysUntilHarvest = days > Integer.MAX_VALUE ? Integer.MAX_VALUE
                                    : days < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) days;
                        }
                        OrganicEvaluation organic = evaluateOrganic(certification, label);
                        GateStatus gate = chemicalGate(target, label, stage,
                                expectedHarvestDate, daysUntilHarvest, organic);
                        rows.add(chemicalRow(target, label, stage, daysUntilHarvest, organic, gate));
                    } else {
                        addDataGap(unavailable, "verifiedLabel");
                    }
                } catch (RuntimeException error) {
                    log.warn("Verified plum label lookup failed: {}", error.getClass().getSimpleName());
                    addDataGap(unavailable, "verifiedLabel");
                }
            }
        }

        return new Assessment(RULE_VERSION, AssessmentStatus.READY, request.target(),
                target.code(), target.name(), target.type(), stage, request.assessmentDate(),
                expectedHarvestDate, daysUntilHarvest, request.productId(), productVerified,
                rows, unavailable);
    }

    private DefenseRow generalRow(TargetSeed target, MeasureSeed measure, String requestedStage,
                                  String stage) {
        List<String> reasons = new ArrayList<>();
        GateStatus gate;
        if (stage == null) {
            gate = GateStatus.INSUFFICIENT_DATA;
            reasons.add(requestedStage == null
                    ? "缺少当前脆李物候期，无法执行阶段门槛判断。"
                    : "无法识别物候期“" + requestedStage + "”，不能执行阶段门槛判断。");
        } else if (target.managementForbiddenStages().contains(stage)) {
            gate = GateStatus.BLOCKED_BY_STAGE;
            reasons.add("当前物候期 " + stage + " 属于该措施的明确禁用阶段。");
        } else if (!target.managementStages().contains(stage)) {
            gate = GateStatus.NOT_APPLICABLE;
            reasons.add("当前物候期 " + stage + " 不在该措施的建议适用阶段内。");
        } else {
            gate = GateStatus.ELIGIBLE;
            reasons.add("当前物候期与一般栽培措施适用阶段一致。");
        }
        reasons.add("非化学措施不使用产品 PHI，也不代表任何药剂或有机投入品已获批准。");
        return new DefenseRow(
                "plum-defense-" + target.code() + "-" + measure.key(),
                target.code(), target.name(), target.type(), measure.kind(), measure.guidance(),
                target.managementStages(), target.managementForbiddenStages(),
                null, OrganicPermission.GENERAL_NONCHEMICAL_PRACTICE, null,
                gate, reasons, EvidenceScope.GENERAL_GUARDRAIL, SOURCE_REF,
                null, null, null, null, null);
    }

    private GateStatus chemicalGate(TargetSeed target, LabelInput label, String stage,
                                    LocalDate expectedHarvestDate, Integer daysUntilHarvest,
                                    OrganicEvaluation organic) {
        if (label.prohibitedByLabel()) return GateStatus.BLOCKED_BY_LABEL;
        if (stage == null) return GateStatus.INSUFFICIENT_DATA;
        if (target.chemicalForbiddenStages().contains(stage)) return GateStatus.BLOCKED_BY_STAGE;
        if (!target.managementStages().contains(stage)) return GateStatus.NOT_APPLICABLE;
        Integer phi = label.preHarvestIntervalDays();
        if (phi == null || phi < 0) return GateStatus.INSUFFICIENT_DATA;
        if (expectedHarvestDate == null || daysUntilHarvest == null) return GateStatus.INSUFFICIENT_DATA;
        if (daysUntilHarvest < phi) return GateStatus.BLOCKED_BY_PHI;
        if (organic.gateStatus() != null) return organic.gateStatus();
        return GateStatus.LABEL_TARGET_CONFIRMATION_REQUIRED;
    }

    private DefenseRow chemicalRow(TargetSeed target, LabelInput label, String stage,
                                   Integer daysUntilHarvest, OrganicEvaluation organic,
                                   GateStatus gate) {
        List<String> reasons = new ArrayList<>();
        String guidance = "仅在该登记标签明确覆盖脆李和当前防治对象、剂量及使用方法，"
                + "且果园认证限制复核通过后使用。";
        switch (gate) {
            case BLOCKED_BY_LABEL -> reasons.add("标签或禁用清单明确禁止该产品，不能作为推荐方案。");
            case BLOCKED_BY_STAGE -> reasons.add("当前物候期属于该措施的保守禁用阶段。");
            case NOT_APPLICABLE -> reasons.add("当前物候期不适用该病虫害化学防治窗口。");
            case BLOCKED_BY_PHI -> reasons.add("距已验证预计采收 " + daysUntilHarvest
                    + " 天，短于标签安全间隔期 " + label.preHarvestIntervalDays() + " 天。");
            case BLOCKED_BY_ORGANIC -> reasons.add(organic.reason());
            case INSUFFICIENT_DATA -> reasons.add(chemicalDataGapReason(
                    label, stage, daysUntilHarvest, organic));
            case LABEL_TARGET_CONFIRMATION_REQUIRED -> {
                reasons.add("标签 PHI、当前阶段和果园认证限制已通过结构化门槛。");
                reasons.add("目录未提供该产品对目标病虫的登记关联，必须人工核对标签防治对象后才能执行。");
            }
            default -> reasons.add("未满足化学方案放行条件。");
        }
        Provenance provenance = label.provenance();
        String sourceRef = provenance == null ? null : provenance.sourceReference();
        String version = provenance == null ? null : provenance.documentVersion();
        return new DefenseRow(
                "plum-defense-" + target.code() + "-verified-label",
                target.code(), target.name(), target.type(), MeasureKind.CHEMICAL_LABEL, guidance,
                target.managementStages(), target.chemicalForbiddenStages(),
                label.preHarvestIntervalDays(), organic.permission(), organic.allowed(),
                gate, reasons, EvidenceScope.VERIFIED_PRODUCT_LABEL, sourceRef,
                label.productId(), label.productName(), label.activeIngredient(), version, false);
    }

    private String chemicalDataGapReason(LabelInput label, String stage, Integer daysUntilHarvest,
                                         OrganicEvaluation organic) {
        if (stage == null) return "缺少或无法识别当前物候期，不能放行化学方案。";
        if (label.preHarvestIntervalDays() == null) return "已验证标签缺少安全间隔期，按缺证处理。";
        if (label.preHarvestIntervalDays() < 0) return "标签安全间隔期为负数，按无效证据处理。";
        if (daysUntilHarvest == null) return "缺少已验证的预计采收日期，无法核对安全间隔期。";
        return organic.reason() == null ? "关键安全证据不完整。" : organic.reason();
    }

    private OrganicEvaluation evaluateOrganic(OrchardCertification certification, LabelInput label) {
        if (certification == null) {
            return new OrganicEvaluation(OrganicPermission.UNVERIFIED, null,
                    GateStatus.INSUFFICIENT_DATA, "缺少果园认证记录，不能核验有机/绿色限制。");
        }
        if (certification.status() == CertificationStatus.UNKNOWN) {
            return new OrganicEvaluation(OrganicPermission.UNVERIFIED, null,
                    GateStatus.INSUFFICIENT_DATA, "果园认证状态未知，按缺证处理。");
        }
        if (certification.provenance() == null || !certification.provenance().usable()) {
            return new OrganicEvaluation(OrganicPermission.UNVERIFIED, null,
                    GateStatus.INSUFFICIENT_DATA, "果园认证/禁药清单来源未验证，不能放行。");
        }

        if (containsIgnoreCase(certification.prohibitedProductIds(), label.productId())
                || containsIgnoreCase(certification.prohibitedActiveIngredients(), label.activeIngredient())) {
            return new OrganicEvaluation(OrganicPermission.PROHIBITED_BY_VERIFIED_CERTIFICATION,
                    false, GateStatus.BLOCKED_BY_ORGANIC, "产品命中已验证的认证禁药清单。");
        }

        CertificationStatus status = certification.status();
        if (status == CertificationStatus.NONE) {
            return new OrganicEvaluation(OrganicPermission.NO_CERTIFICATION_RESTRICTION,
                    null, null, null);
        }
        if (status == CertificationStatus.ORGANIC
                || status == CertificationStatus.ORGANIC_IN_CONVERSION) {
            if (explicitlyAllowed(certification, label)) {
                return new OrganicEvaluation(OrganicPermission.APPROVED_BY_VERIFIED_CERTIFICATION,
                        true, null, null);
            }
            return new OrganicEvaluation(OrganicPermission.NOT_APPROVED_BY_VERIFIED_CERTIFICATION,
                    false, GateStatus.BLOCKED_BY_ORGANIC,
                    "有机/转换期果园未在产品或有效成分允许清单中明确放行该产品。");
        }
        if (status == CertificationStatus.GREEN_FOOD) {
            if (explicitlyAllowed(certification, label)) {
                return new OrganicEvaluation(OrganicPermission.APPROVED_BY_VERIFIED_CERTIFICATION,
                        true, null, null);
            }
            if (label.syntheticChemical() == null) {
                return new OrganicEvaluation(OrganicPermission.UNVERIFIED, null,
                        GateStatus.INSUFFICIENT_DATA,
                        "绿色食品果园缺少标签化学合成属性，不能核验限制。");
            }
            if (Boolean.TRUE.equals(label.syntheticChemical())) {
                return new OrganicEvaluation(OrganicPermission.NOT_APPROVED_BY_VERIFIED_CERTIFICATION,
                        false, GateStatus.BLOCKED_BY_ORGANIC,
                        "化学合成产品未出现在已验证的绿色食品允许清单中。");
            }
            return new OrganicEvaluation(OrganicPermission.GENERAL_NONCHEMICAL_PRACTICE,
                    null, null, null);
        }
        return new OrganicEvaluation(OrganicPermission.UNVERIFIED, null,
                GateStatus.INSUFFICIENT_DATA, "果园认证状态无法映射到明确的安全限制。");
    }

    private boolean explicitlyAllowed(OrchardCertification certification, LabelInput label) {
        return containsIgnoreCase(certification.explicitlyAllowedProductIds(), label.productId())
                || containsIgnoreCase(certification.explicitlyAllowedActiveIngredients(),
                label.activeIngredient());
    }

    private boolean isTrustedLabel(LabelInput label) {
        return label != null && label.provenance() != null && label.provenance().usable();
    }

    private TargetSeed findTarget(String requested) {
        String normalized = normalize(requested);
        if (normalized.isBlank()) return null;
        for (TargetSeed target : TARGETS) {
            if (targetAliases(target).stream().anyMatch(normalized::contains)) return target;
        }
        return null;
    }

    private List<String> targetAliases(TargetSeed target) {
        List<String> aliases = new ArrayList<>();
        aliases.add(normalize(target.code()));
        aliases.add(normalize(target.name()));
        target.aliases().stream().map(PlumDefenseKnowledgeService::normalize)
                .filter(alias -> !alias.isBlank()).forEach(aliases::add);
        return aliases;
    }

    private static String canonicalStage(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? null : STAGE_ALIASES.get(normalized);
    }

    private static Map<String, String> stageAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String stage : ALL_STAGES) aliases.put(normalize(stage), stage);
        aliases.put("休眠期", "dormancy");
        aliases.put("休眠", "dormancy");
        aliases.put("越冬", "dormancy");
        aliases.put("萌芽期", "germination");
        aliases.put("萌芽", "germination");
        aliases.put("萌动", "germination");
        aliases.put("花芽萌动", "germination");
        aliases.put("花蕾期", "budding");
        aliases.put("现蕾", "budding");
        aliases.put("露白", "budding");
        aliases.put("花期", "flowering");
        aliases.put("初花", "flowering");
        aliases.put("初花期", "flowering");
        aliases.put("盛花", "flowering");
        aliases.put("盛花期", "flowering");
        aliases.put("末花", "flowering");
        aliases.put("末花期", "flowering");
        aliases.put("开花", "flowering");
        aliases.put("展叶期", "leafing");
        aliases.put("展叶", "leafing");
        aliases.put("春梢", "leafing");
        aliases.put("坐果期", "fruiting");
        aliases.put("坐果", "fruiting");
        aliases.put("幼果", "fruiting");
        aliases.put("幼果期", "fruiting");
        aliases.put("谢花坐果", "fruiting");
        aliases.put("果实发育期", "fruit_development");
        aliases.put("果实发育", "fruit_development");
        aliases.put("果实膨大期", "fruit_development");
        aliases.put("幼果膨大", "fruit_development");
        aliases.put("硬核", "fruit_development");
        aliases.put("硬核期", "fruit_development");
        aliases.put("膨大", "fruit_development");
        aliases.put("成熟期", "ripening");
        aliases.put("转色", "ripening");
        aliases.put("转色期", "ripening");
        aliases.put("软化", "ripening");
        aliases.put("软化期", "ripening");
        aliases.put("成熟", "ripening");
        aliases.put("采收期", "harvest");
        aliases.put("采摘", "harvest");
        aliases.put("采摘期", "harvest");
        aliases.put("上市", "harvest");
        aliases.put("采后恢复期", "post_harvest");
        aliases.put("采后", "post_harvest");
        aliases.put("恢复期", "post_harvest");
        return Map.copyOf(aliases);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-—–/·（）()，,。:：;；]+", "");
    }

    private static boolean containsIgnoreCase(Set<String> values, String candidate) {
        if (values == null || candidate == null || candidate.isBlank()) return false;
        return values.stream().filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.trim().equalsIgnoreCase(candidate.trim()));
    }

    private static void addDataGap(List<String> unavailable, String field) {
        if (!unavailable.contains(field)) unavailable.add(field);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> stages(String... values) {
        return List.of(values);
    }

    private record MeasureSeed(String key, MeasureKind kind, String guidance) { }

    private record TargetSeed(String code, String name, TargetType type, List<String> aliases,
                              List<String> managementStages, List<String> managementForbiddenStages,
                              List<String> chemicalForbiddenStages, List<MeasureSeed> measures) {
        private TargetSeed {
            aliases = immutable(aliases);
            managementStages = immutable(managementStages);
            managementForbiddenStages = immutable(managementForbiddenStages);
            chemicalForbiddenStages = immutable(chemicalForbiddenStages);
            measures = immutable(measures);
        }
    }

    private record OrganicEvaluation(OrganicPermission permission, Boolean allowed,
                                     GateStatus gateStatus, String reason) { }

    private static final List<TargetSeed> TARGETS = List.of(
            new TargetSeed("plum_brown_rot", "褐腐病", TargetType.DISEASE,
                    stages("褐腐病", "褐腐", "brown rot", "monilinia"),
                    ALL_STAGES, List.of(), CHEMICAL_FORBIDDEN_STAGES, List.of(
                    new MeasureSeed("sanitation", MeasureKind.CULTURAL,
                            "清除病果、僵果和修剪病枝，改善通风透光；病残体带出果园处理。"),
                    new MeasureSeed("harvest-hygiene", MeasureKind.CULTURAL,
                            "采收、贮运时避免机械伤，及时剔除病果，减少侵染和扩散。"))),

            new TargetSeed("plum_bacterial_shot_hole", "细菌性穿孔病", TargetType.DISEASE,
                    stages("细菌性穿孔病", "穿孔病", "bacterial spot", "xanthomonas", "shot hole"),
                    ALL_STAGES, List.of(), CHEMICAL_FORBIDDEN_STAGES, List.of(
                    new MeasureSeed("airflow", MeasureKind.CULTURAL,
                            "合理修剪和排水，避免树冠密闭与根区积水，降低叶果长时间潮湿。"),
                    new MeasureSeed("wet-canopy", MeasureKind.CULTURAL,
                            "避免在枝叶潮湿时进行易造成伤口的操作，减少喷灌和雨水飞溅。"))),

            new TargetSeed("plum_gummosis", "流胶病", TargetType.DISEASE,
                    stages("流胶病", "流胶", "gummosis"),
                    ALL_STAGES, List.of(), CHEMICAL_FORBIDDEN_STAGES, List.of(
                    new MeasureSeed("wound-avoidance", MeasureKind.CULTURAL,
                            "减少机械伤和冻伤，修剪工具消毒，伤口保持清洁并促进愈合。"),
                    new MeasureSeed("drainage-scouting", MeasureKind.CULTURAL,
                            "改善排水和根区通气，避免长期积水；发现流胶先查伤口、虫害和根腐诱因。"))),

            new TargetSeed("plum_bag_fruit", "袋果病", TargetType.DISEASE,
                    stages("袋果病", "囊果病", "plum pockets", "taphrina"),
                    stages("germination", "budding", "flowering", "fruiting"),
                    List.of(),
                    CHEMICAL_FORBIDDEN_STAGES, List.of(
                    new MeasureSeed("early-removal", MeasureKind.CULTURAL,
                            "春季发现袋状、囊状畸形病果或病梢时尽早摘除并带出果园。"),
                    new MeasureSeed("spring-scouting", MeasureKind.MONITORING,
                            "萌芽至幼果期逐株检查芽、花和幼果，记录发病位置与数量。"))),

            new TargetSeed("plum_fruit_moth", "食心虫", TargetType.PEST,
                    stages("食心虫", "李小食心虫", "桃小食心虫", "梨小食心虫", "fruit moth", "grapholita"),
                    stages("fruiting", "fruit_development", "ripening"),
                    List.of(),
                    CHEMICAL_FORBIDDEN_STAGES, List.of(
                    new MeasureSeed("drop-removal", MeasureKind.CULTURAL,
                            "及时清理虫果和落果，减少幼虫继续发育和下一代虫源。"),
                    new MeasureSeed("monitoring", MeasureKind.MONITORING,
                            "在树冠和幼果期布设监测，结合诱捕与卵果调查确定防治时机。"))),

            new TargetSeed("plum_aphid", "蚜虫", TargetType.PEST,
                    stages("蚜虫", "桃蚜", "aphid"),
                    stages("germination", "budding", "leafing", "fruiting", "fruit_development"),
                    List.of(),
                    CHEMICAL_FORBIDDEN_STAGES, List.of(
                    new MeasureSeed("shoot-monitoring", MeasureKind.MONITORING,
                            "检查嫩梢、叶背和卷叶内的蚜虫及天敌数量，避免仅凭少量虫口喷药。"),
                    new MeasureSeed("natural-enemies", MeasureKind.BIOLOGICAL,
                            "人工抹除局部虫源，保护瓢虫、草蛉等天敌，优先处理点片发生。"))),

            new TargetSeed("plum_spider_mite", "红蜘蛛", TargetType.PEST,
                    stages("红蜘蛛", "叶螨", "spider mite", "tetranychus"),
                    stages("leafing", "fruit_development", "ripening", "post_harvest"),
                    List.of(),
                    CHEMICAL_FORBIDDEN_STAGES, List.of(
                    new MeasureSeed("leaf-monitoring", MeasureKind.MONITORING,
                            "翻看叶背，记录活动螨、卵和天敌数量；高温干旱后加密调查。"),
                    new MeasureSeed("dust-moisture", MeasureKind.CULTURAL,
                            "减少园内扬尘、保持合理水分和树势，避免破坏天敌平衡。"))));
}

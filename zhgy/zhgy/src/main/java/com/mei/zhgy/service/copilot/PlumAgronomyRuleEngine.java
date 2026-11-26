package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.agriculture.PhenologyResult;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.agriculture.PhenologyStage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic, fail-closed agronomy rules for the plum copilot.
 *
 * <p>The engine deliberately contains no pesticide catalog, organic standard, cultivar
 * coefficient, or harvest threshold. Those values are accepted only as caller-supplied
 * records with usable provenance. Missing or unverified restrictions produce a negative
 * decision instead of a permissive default.</p>
 */
@Service
public class PlumAgronomyRuleEngine {
    public static final String RULE_VERSION = "plum-agronomy/2026-11-26";
    private static final int NEAR_HARVEST_DAYS = 20;

    private final PhenologyService phenology = new PhenologyService();

    public enum SourceKind {
        PUBLIC_LABEL,
        AUTHORITATIVE_LABEL,
        REGULATORY_CATALOG,
        ORCHARD_RECORD,
        FIELD_OBSERVATION,
        EXPERT_OBSERVATION,
        LOCAL_CALIBRATION,
        MEASUREMENT,
        WEATHER_OBSERVATION,
        WEATHER_FORECAST,
        OTHER,
        UNKNOWN
    }

    public enum Severity {
        INFO,
        WARNING,
        BLOCKER,
        DATA_GAP
    }

    public enum Decision {
        ELIGIBLE,
        CAUTION,
        UNSUITABLE,
        INSUFFICIENT_DATA,
        BLOCKED
    }

    public enum SprayWindowSuitability {
        SUITABLE,
        CAUTION,
        UNSUITABLE,
        INSUFFICIENT_DATA
    }

    public enum CertificationStatus {
        NONE,
        ORGANIC,
        ORGANIC_IN_CONVERSION,
        GREEN_FOOD,
        UNKNOWN
    }

    public enum HarvestMaturityStatus {
        MEETS_PROVIDED_CRITERIA,
        BELOW_PROVIDED_CRITERIA,
        INSUFFICIENT_DATA
    }

    public enum HarvestWeatherStatus {
        FAVORABLE,
        CAUTION,
        RISK,
        INSUFFICIENT_DATA
    }

    public enum HarvestDecision {
        READY_FOR_MANUAL_REVIEW,
        CAUTION,
        WEATHER_RISK,
        WEATHER_DATA_UNAVAILABLE,
        WAIT_FOR_MATURITY,
        INSUFFICIENT_DATA
    }

    public enum CriterionResult {
        PASS,
        FAIL,
        MEASUREMENT_MISSING,
        NOT_CONFIGURED
    }

    public enum PhenologyCorrectionStatus {
        CONFIRMED,
        CORRECTED,
        EVIDENCE_ONLY,
        NO_TRUSTED_EVIDENCE,
        CONFLICTING_EVIDENCE,
        INVALID_REQUEST
    }

    /**
     * Provenance for a supplied fact. {@code usable()} is the gate used by every rule.
     * A public label is marked even when the caller used a broader public source kind.
     */
    public record Provenance(SourceKind sourceKind, String sourceReference, String documentVersion,
                             Instant retrievedAt, boolean publicSource, boolean verified) {
        public Provenance {
            sourceKind = sourceKind == null ? SourceKind.UNKNOWN : sourceKind;
            sourceReference = trimToNull(sourceReference);
            documentVersion = trimToNull(documentVersion);
            if (sourceKind == SourceKind.PUBLIC_LABEL) publicSource = true;
        }

        public boolean usable() {
            return verified && sourceReference != null;
        }

        public String marker() {
            String kind = sourceKind == SourceKind.PUBLIC_LABEL ? "LABEL" : sourceKind.name();
            if (!usable()) return "UNVERIFIED_" + kind;
            return (publicSource ? "PUBLIC_" : "VERIFIED_") + kind;
        }
    }

    /** A serializable view that makes public label provenance explicit to the agent/UI. */
    public record LabelProvenanceView(SourceKind sourceKind, String marker, boolean publicLabel,
                                      boolean verified, String sourceReference, String documentVersion,
                                      Instant retrievedAt) {
        public static LabelProvenanceView from(Provenance provenance) {
            if (provenance == null) {
                return new LabelProvenanceView(SourceKind.UNKNOWN, "MISSING", false, false,
                        null, null, null);
            }
            return new LabelProvenanceView(provenance.sourceKind(), provenance.marker(),
                    provenance.usable() && provenance.publicSource(), provenance.usable(),
                    provenance.sourceReference(), provenance.documentVersion(), provenance.retrievedAt());
        }
    }

    public record Finding(String code, Severity severity, String message) {
        public Finding {
            code = code == null ? "UNKNOWN" : code;
            severity = severity == null ? Severity.INFO : severity;
            message = message == null ? "" : message;
        }
    }

    public record LabelInput(String productId, String productName, String activeIngredient,
                             Integer preHarvestIntervalDays, Boolean syntheticChemical,
                             boolean prohibitedByLabel, Provenance provenance) {
        public LabelInput {
            productId = trimToNull(productId);
            productName = trimToNull(productName);
            activeIngredient = trimToNull(activeIngredient);
        }
    }

    public record OrchardCertification(CertificationStatus status, Provenance provenance,
                                       Set<String> explicitlyAllowedProductIds,
                                       Set<String> explicitlyAllowedActiveIngredients,
                                       Set<String> prohibitedProductIds,
                                       Set<String> prohibitedActiveIngredients) {
        public OrchardCertification {
            status = status == null ? CertificationStatus.UNKNOWN : status;
            explicitlyAllowedProductIds = normalizedSet(explicitlyAllowedProductIds);
            explicitlyAllowedActiveIngredients = normalizedSet(explicitlyAllowedActiveIngredients);
            prohibitedProductIds = normalizedSet(prohibitedProductIds);
            prohibitedActiveIngredients = normalizedSet(prohibitedActiveIngredients);
        }
    }

    public record SprayWeatherWindow(String id, LocalDateTime startsAt, LocalDateTime endsAt,
                                     Double airTemperatureC, Double relativeHumidityPct,
                                     Double windSpeedKph, Double precipitationMm,
                                     Double precipitationProbabilityPct, Provenance provenance) {
        public SprayWeatherWindow {
            id = trimToNull(id);
        }
    }

    public record SprayWindowAssessment(String id, LocalDateTime startsAt, LocalDateTime endsAt,
                                        Double airTemperatureC, Double relativeHumidityPct,
                                        Double windSpeedKph, Double precipitationMm,
                                        Double precipitationProbabilityPct,
                                        SprayWindowSuitability suitability, Integer score,
                                        List<String> reasons, List<String> unavailableData,
                                        String provenanceMarker) {
        public SprayWindowAssessment {
            reasons = immutableList(reasons);
            unavailableData = immutableList(unavailableData);
            provenanceMarker = provenanceMarker == null ? "MISSING" : provenanceMarker;
        }
    }

    public record SprayAssessmentRequest(LocalDate plannedSprayDate, LocalDate expectedHarvestDate,
                                         String currentPhenologyStageCode, LabelInput label,
                                         OrchardCertification certification,
                                         List<SprayWeatherWindow> weatherWindows) {
        public SprayAssessmentRequest {
            currentPhenologyStageCode = trimToNull(currentPhenologyStageCode);
            weatherWindows = immutableList(weatherWindows);
        }
    }

    public record SprayAssessment(String ruleVersion, Decision decision,
                                  boolean hardRestrictionsPassed, boolean nearHarvest,
                                  LocalDate plannedSprayDate, LocalDate expectedHarvestDate,
                                  Integer daysUntilHarvest, Integer phiDays,
                                  LabelProvenanceView labelProvenance,
                                  CertificationStatus certificationStatus,
                                  String certificationProvenanceMarker,
                                  boolean weatherSuitable,
                                  SprayWindowAssessment bestWindow,
                                  List<SprayWindowAssessment> windows,
                                  List<Finding> findings, List<String> unavailableData) {
        public SprayAssessment {
            windows = immutableList(windows);
            findings = immutableList(findings);
            unavailableData = immutableList(unavailableData);
        }
    }

    /** Assess one spray plan. A positive decision requires verified hard restrictions and a complete weather window. */
    public SprayAssessment assessSpray(SprayAssessmentRequest request) {
        List<Finding> findings = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (request == null) {
            addFinding(findings, "REQUEST_MISSING", Severity.DATA_GAP, "未提供施药评估输入。");
            addUnavailable(unavailable, "sprayAssessmentRequest");
            return new SprayAssessment(RULE_VERSION, Decision.INSUFFICIENT_DATA, false, false,
                    null, null, null, null, LabelProvenanceView.from(null), null, "MISSING",
                    false, null, List.of(), findings, unavailable);
        }

        LocalDate planned = request.plannedSprayDate();
        LocalDate harvest = request.expectedHarvestDate();
        if (planned == null) {
            addFinding(findings, "PLANNED_SPRAY_DATE_MISSING", Severity.DATA_GAP, "缺少计划施药日期，无法核对安全间隔期。");
            addUnavailable(unavailable, "plannedSprayDate");
        }
        if (harvest == null) {
            addFinding(findings, "EXPECTED_HARVEST_DATE_MISSING", Severity.DATA_GAP, "缺少预计采收日期，无法核对安全间隔期。");
            addUnavailable(unavailable, "expectedHarvestDate");
        }

        Integer daysUntilHarvest = null;
        if (planned != null && harvest != null) {
            long rawDays = ChronoUnit.DAYS.between(planned, harvest);
            if (rawDays < 0) {
                addFinding(findings, "HARVEST_BEFORE_SPRAY", Severity.BLOCKER, "预计采收日期早于计划施药日期。");
            } else {
                daysUntilHarvest = rawDays > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rawDays;
            }
        }

        LabelInput label = request.label();
        LabelProvenanceView labelProvenance = LabelProvenanceView.from(label == null ? null : label.provenance());
        Integer phiDays = label == null ? null : label.preHarvestIntervalDays();
        if (label == null) {
            addFinding(findings, "LABEL_MISSING", Severity.DATA_GAP, "缺少已登记标签信息，不能推荐施药。");
            addUnavailable(unavailable, "label");
        } else {
            if (!labelProvenance.verified()) {
                addFinding(findings, "LABEL_PROVENANCE_UNVERIFIED", Severity.DATA_GAP,
                        "标签来源未验证，不能用于安全间隔期判断。");
                addUnavailable(unavailable, "label.provenance");
            } else {
                addFinding(findings, "LABEL_PROVENANCE_VERIFIED", Severity.INFO,
                        "标签来源已验证：" + labelProvenance.marker() + "。");
                if (labelProvenance.publicLabel()) {
                    addFinding(findings, "PUBLIC_LABEL_PROVENANCE", Severity.INFO,
                            "该标签为可公开核验的登记标签。");
                }
            }
            if (phiDays == null) {
                addFinding(findings, "PHI_MISSING", Severity.DATA_GAP, "标签未提供安全间隔期，按缺证处理。");
                addUnavailable(unavailable, "label.preHarvestIntervalDays");
            } else if (phiDays < 0) {
                addFinding(findings, "PHI_INVALID", Severity.BLOCKER, "安全间隔期不能为负数。");
            } else if (daysUntilHarvest != null && daysUntilHarvest < phiDays) {
                addFinding(findings, "PHI_CONFLICT", Severity.BLOCKER,
                        "距预计采收仅 " + daysUntilHarvest + " 天，短于标签安全间隔期 " + phiDays + " 天。");
            }
            if (label.prohibitedByLabel()) {
                addFinding(findings, "PRODUCT_PROHIBITED_BY_LABEL", Severity.BLOCKER,
                        "标签或禁用清单明确禁止该产品在当前场景使用。");
            }
        }

        CertificationStatus certificationStatus = request.certification() == null
                ? CertificationStatus.UNKNOWN : request.certification().status();
        String certificationMarker = request.certification() == null
                ? "MISSING" : provenanceMarker(request.certification().provenance());
        applyCertification(request.certification(), label, findings, unavailable);

        List<SprayWindowAssessment> windows = new ArrayList<>();
        if (request.weatherWindows().isEmpty()) {
            addFinding(findings, "SPRAY_WEATHER_WINDOWS_MISSING", Severity.DATA_GAP,
                    "缺少施药气象窗口，无法评分。");
            addUnavailable(unavailable, "weatherWindows");
        } else {
            for (int index = 0; index < request.weatherWindows().size(); index++) {
                windows.add(assessSprayWindow(request.weatherWindows().get(index), index, unavailable));
            }
        }

        SprayWindowAssessment best = bestSprayWindow(windows);
        if (!windows.isEmpty() && (best == null || best.suitability() == SprayWindowSuitability.INSUFFICIENT_DATA)) {
            addFinding(findings, "SPRAY_WEATHER_INCOMPLETE", Severity.DATA_GAP,
                    "所有施药气象窗口都缺少关键字段，不能给出适宜结论。");
        }

        boolean nearHarvest = (daysUntilHarvest != null && daysUntilHarvest <= NEAR_HARVEST_DAYS)
                || isRipeningOrHarvest(request.currentPhenologyStageCode());
        if (nearHarvest) {
            addFinding(findings, "NEAR_HARVEST_PHI_CHECK_REQUIRED", Severity.INFO,
                    "当前处于临近采收窗口，安全间隔期已按硬约束核对。");
        }

        Decision decision = sprayDecision(findings, best);
        boolean hardRestrictionsPassed = !hasRestrictionProblem(findings);
        boolean weatherSuitable = best != null && best.suitability() == SprayWindowSuitability.SUITABLE;
        return new SprayAssessment(RULE_VERSION, decision, hardRestrictionsPassed, nearHarvest,
                planned, harvest, daysUntilHarvest, phiDays, labelProvenance,
                certificationStatus, certificationMarker, weatherSuitable, best, windows,
                findings, unavailable);
    }

    private void applyCertification(OrchardCertification certification, LabelInput label,
                                    List<Finding> findings, List<String> unavailable) {
        if (certification == null) {
            addFinding(findings, "CERTIFICATION_MISSING", Severity.DATA_GAP,
                    "缺少果园认证状态，不能核验有机/绿色限制。");
            addUnavailable(unavailable, "certification");
            return;
        }
        if (certification.status() == CertificationStatus.UNKNOWN) {
            addFinding(findings, "CERTIFICATION_UNKNOWN", Severity.DATA_GAP,
                    "果园认证状态未知，按缺证处理。");
            addUnavailable(unavailable, "certification.status");
            return;
        }
        if (certification.provenance() == null || !certification.provenance().usable()) {
            addFinding(findings, "CERTIFICATION_PROVENANCE_UNVERIFIED", Severity.DATA_GAP,
                    "果园认证/禁药清单来源未验证，不能放行施药建议。");
            addUnavailable(unavailable, "certification.provenance");
            return;
        }
        if (label == null) return;

        if (certification.status() == CertificationStatus.NONE) {
            addFinding(findings, "CERTIFICATION_NONE_VERIFIED", Severity.INFO,
                    "已核验当前果园无有机/绿色认证限制。");
        }
        if (containsIgnoreCase(certification.prohibitedProductIds(), label.productId())) {
            addFinding(findings, "CERTIFICATION_PRODUCT_PROHIBITED", Severity.BLOCKER,
                    "该产品在果园认证禁药清单中。");
        }
        if (containsIgnoreCase(certification.prohibitedActiveIngredients(), label.activeIngredient())) {
            addFinding(findings, "CERTIFICATION_ACTIVE_INGREDIENT_PROHIBITED", Severity.BLOCKER,
                    "该有效成分在果园认证禁药清单中。");
        }

        if (certification.status() == CertificationStatus.ORGANIC
                || certification.status() == CertificationStatus.ORGANIC_IN_CONVERSION) {
            if (label.productId() == null && label.activeIngredient() == null) {
                addFinding(findings, "CERTIFICATION_PRODUCT_ID_MISSING", Severity.DATA_GAP,
                        "有机认证核验需要产品 ID 或有效成分，但标签未提供。");
                addUnavailable(unavailable, "label.productId");
            } else if (!explicitlyAllowed(certification, label)) {
                addFinding(findings, "CERTIFICATION_PRODUCT_NOT_ALLOWED", Severity.BLOCKER,
                        "产品未出现在已验证的有机认证允许清单中，按禁用处理。");
            } else {
                addFinding(findings, "CERTIFICATION_PRODUCT_ALLOWED", Severity.INFO,
                        "产品已出现在已验证的有机认证允许清单中。");
            }
        } else if (certification.status() == CertificationStatus.GREEN_FOOD) {
            if (label.syntheticChemical() == null) {
                addFinding(findings, "CERTIFICATION_SYNTHETIC_FLAG_MISSING", Severity.DATA_GAP,
                        "绿色食品核验需要标签明确是否化学合成，但该字段缺失。");
                addUnavailable(unavailable, "label.syntheticChemical");
            } else if (label.syntheticChemical() && !explicitlyAllowed(certification, label)) {
                addFinding(findings, "CERTIFICATION_PRODUCT_NOT_ALLOWED", Severity.BLOCKER,
                        "化学合成产品未出现在已验证的绿色食品允许清单中，按禁用处理。");
            } else if (label.syntheticChemical()) {
                addFinding(findings, "CERTIFICATION_PRODUCT_ALLOWED", Severity.INFO,
                        "化学合成产品已出现在已验证的绿色食品允许清单中。");
            } else {
                addFinding(findings, "CERTIFICATION_NON_SYNTHETIC_ALLOWED", Severity.INFO,
                        "标签标记为非化学合成，且未命中认证禁药清单。");
            }
        }
    }

    private SprayWindowAssessment assessSprayWindow(SprayWeatherWindow window, int index,
                                                    List<String> globalUnavailable) {
        List<String> windowUnavailable = new ArrayList<>();
        if (window == null) {
            String field = "weatherWindows[" + index + "]";
            addUnavailable(windowUnavailable, field);
            addUnavailableAll(globalUnavailable, windowUnavailable);
            return new SprayWindowAssessment(field, null, null, null, null, null,
                    null, null, SprayWindowSuitability.INSUFFICIENT_DATA, null,
                    List.of("气象窗口为空。"), windowUnavailable, "MISSING");
        }

        String id = window.id() == null ? "index-" + index : window.id();
        String prefix = "weatherWindows[" + id + "]";
        Double temperature = finiteOrNull(window.airTemperatureC());
        Double humidity = finiteOrNull(window.relativeHumidityPct());
        Double wind = finiteOrNull(window.windSpeedKph());
        Double precipitation = finiteOrNull(window.precipitationMm());
        Double probability = finiteOrNull(window.precipitationProbabilityPct());
        List<String> criticalMissing = new ArrayList<>();

        if (temperature == null) criticalMissing.add(prefix + ".airTemperatureC");
        if (wind == null) criticalMissing.add(prefix + ".windSpeedKph");
        if (precipitation == null) addUnavailable(windowUnavailable, prefix + ".precipitationMm");
        if (probability == null) addUnavailable(windowUnavailable, prefix + ".precipitationProbabilityPct");
        if (precipitation == null && probability == null) {
            criticalMissing.add(prefix + ".precipitationMm");
            criticalMissing.add(prefix + ".precipitationProbabilityPct");
        }
        if (window.provenance() == null || !window.provenance().usable()) {
            criticalMissing.add(prefix + ".provenance");
        }
        if (humidity == null) addUnavailable(windowUnavailable, prefix + ".relativeHumidityPct");
        if (window.startsAt() == null) addUnavailable(windowUnavailable, prefix + ".startsAt");
        if (window.endsAt() == null) addUnavailable(windowUnavailable, prefix + ".endsAt");
        if (!criticalMissing.isEmpty()) {
            windowUnavailable.addAll(criticalMissing);
            addUnavailableAll(globalUnavailable, windowUnavailable);
            List<String> reasons = new ArrayList<>();
            reasons.add("关键气象数据缺失，不能判定作业窗口：" + String.join(", ", criticalMissing));
            return new SprayWindowAssessment(id, window.startsAt(), window.endsAt(), temperature, humidity,
                    wind, precipitation, probability, SprayWindowSuitability.INSUFFICIENT_DATA,
                    null, reasons, windowUnavailable, provenanceMarker(window.provenance()));
        }

        int score = 100;
        List<String> reasons = new ArrayList<>();
        if (temperature > 35D) {
            score -= 65;
            reasons.add("气温高于 35°C，存在药害/蒸发风险。");
        } else if (temperature > 30D) {
            score -= 35;
            reasons.add("气温高于 30°C，应避开正午高温。");
        } else if (temperature < 5D) {
            score -= 60;
            reasons.add("气温低于 5°C，不宜常规喷雾。");
        } else if (temperature < 10D) {
            score -= 20;
            reasons.add("气温低于 10°C，药效与吸收可能受影响。");
        }

        if (wind >= 20D) {
            score -= 70;
            reasons.add("风速达到 20 km/h 以上，飘移风险高。");
        } else if (wind >= 15D) {
            score -= 35;
            reasons.add("风速达到 15 km/h 以上，接近不宜喷雾阈值。");
        } else if (wind >= 10D) {
            score -= 15;
            reasons.add("风速偏高，需降低飘移风险。");
        }

        if (probability != null) {
            if (probability >= 70D) {
                score -= 70;
                reasons.add("降雨概率达到 70% 以上。");
            } else if (probability >= 40D) {
                score -= 30;
                reasons.add("降雨概率达到 40% 以上。");
            }
        }
        if (precipitation != null) {
            if (precipitation >= 5D) {
                score -= 70;
                reasons.add("预报降水量达到 5 mm 以上。");
            } else if (precipitation >= 1D) {
                score -= 30;
                reasons.add("预报有 1 mm 以上降水。");
            }
        }
        score = Math.max(0, Math.min(100, score));

        boolean severe = temperature > 35D || temperature < 5D || wind >= 20D
                || (probability != null && probability >= 70D)
                || (precipitation != null && precipitation >= 5D);
        SprayWindowSuitability suitability;
        if (severe) {
            suitability = SprayWindowSuitability.UNSUITABLE;
        } else if (score >= 75) {
            suitability = SprayWindowSuitability.SUITABLE;
        } else if (score >= 45) {
            suitability = SprayWindowSuitability.CAUTION;
        } else {
            suitability = SprayWindowSuitability.UNSUITABLE;
        }
        if (reasons.isEmpty()) reasons.add("气象条件处于可评估范围。");
        addUnavailableAll(globalUnavailable, windowUnavailable);
        return new SprayWindowAssessment(id, window.startsAt(), window.endsAt(), temperature, humidity,
                wind, precipitation, probability, suitability, score, reasons, windowUnavailable,
                provenanceMarker(window.provenance()));
    }

    /**
     * Assess harvest maturity from measured values and verified criteria, then report a
     * separate precipitation outlook. No phenology date or calendar threshold is used.
     */
    public HarvestAssessment assessHarvest(HarvestAssessmentRequest request) {
        List<Finding> findings = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (request == null) {
            addFinding(findings, "REQUEST_MISSING", Severity.DATA_GAP, "未提供采收评估输入。");
            addUnavailable(unavailable, "harvestAssessmentRequest");
            return new HarvestAssessment(RULE_VERSION, HarvestDecision.INSUFFICIENT_DATA,
                    HarvestMaturityStatus.INSUFFICIENT_DATA, HarvestWeatherStatus.INSUFFICIENT_DATA,
                    null, null, null, null, null, List.of(), null, List.of(), findings, unavailable);
        }

        LocalDate assessmentDate = request.assessmentDate();
        if (assessmentDate == null) {
            addFinding(findings, "ASSESSMENT_DATE_MISSING", Severity.DATA_GAP,
                    "缺少采收评估日期。");
            addUnavailable(unavailable, "assessmentDate");
        }

        HarvestMeasurements measurements = request.measurements();
        HarvestCriteria criteria = request.criteria();
        boolean criteriaTrusted = criteria != null && criteria.provenance() != null
                && criteria.provenance().usable();
        boolean measurementTrusted = measurements != null && measurements.provenance() != null
                && measurements.provenance().usable();

        if (criteria == null) {
            addFinding(findings, "HARVEST_CRITERIA_MISSING", Severity.DATA_GAP,
                    "缺少已验证的采收标准，不能用日期或经验阈值代替。");
            addUnavailable(unavailable, "criteria");
        } else if (!criteriaTrusted) {
            addFinding(findings, "HARVEST_CRITERIA_PROVENANCE_UNVERIFIED", Severity.DATA_GAP,
                    "采收标准来源未验证，不能用于判定成熟度。");
            addUnavailable(unavailable, "criteria.provenance");
        }

        if (measurements == null) {
            addFinding(findings, "HARVEST_MEASUREMENTS_MISSING", Severity.DATA_GAP,
                    "缺少果实实测数据，不能仅凭物候日期判断采收。");
            addUnavailable(unavailable, "measurements");
        } else if (!measurementTrusted) {
            addFinding(findings, "HARVEST_MEASUREMENT_PROVENANCE_UNVERIFIED", Severity.DATA_GAP,
                    "果实实测数据来源未验证。");
            addUnavailable(unavailable, "measurements.provenance");
        }

        LocalDate measuredOn = measurements == null ? null : measurements.measuredOn();
        if (measurements != null && measuredOn == null) {
            addFinding(findings, "HARVEST_MEASUREMENT_DATE_MISSING", Severity.DATA_GAP,
                    "缺少果实实测日期。");
            addUnavailable(unavailable, "measurements.measuredOn");
        } else if (assessmentDate != null && measuredOn != null && measuredOn.isAfter(assessmentDate)) {
            addFinding(findings, "HARVEST_MEASUREMENT_AFTER_ASSESSMENT_DATE", Severity.DATA_GAP,
                    "果实实测日期晚于采收评估日期，数据不能前瞻性使用。");
        }

        Double brix = measurementTrusted ? finiteOrNull(measurements.solubleSolidsBrix()) : null;
        Double acidity = measurementTrusted ? finiteOrNull(measurements.titratableAcidityPct()) : null;
        Double firmness = measurementTrusted ? finiteOrNull(measurements.firmnessKgPerCm2()) : null;
        Double color = measurementTrusted ? finiteOrNull(measurements.colorCoveragePct()) : null;
        Double minBrix = criteriaTrusted ? finiteOrNull(criteria.minSolubleSolidsBrix()) : null;
        Double maxAcidity = criteriaTrusted ? finiteOrNull(criteria.maxTitratableAcidityPct()) : null;
        Double minFirmness = criteriaTrusted ? finiteOrNull(criteria.minFirmnessKgPerCm2()) : null;
        Double minColor = criteriaTrusted ? finiteOrNull(criteria.minColorCoveragePct()) : null;

        List<CriterionEvaluation> evaluations = new ArrayList<>();
        evaluateCriterion(evaluations, findings, unavailable, "solubleSolidsBrix", "°Bx", ">=", true,
                minBrix, brix, criteriaTrusted, measurementTrusted,
                "criteria.minSolubleSolidsBrix", "measurements.solubleSolidsBrix");
        evaluateCriterion(evaluations, findings, unavailable, "titratableAcidityPct", "%", "<=", false,
                maxAcidity, acidity, criteriaTrusted, measurementTrusted,
                "criteria.maxTitratableAcidityPct", "measurements.titratableAcidityPct");
        evaluateCriterion(evaluations, findings, unavailable, "firmnessKgPerCm2", "kg/cm²", ">=", true,
                minFirmness, firmness, criteriaTrusted, measurementTrusted,
                "criteria.minFirmnessKgPerCm2", "measurements.firmnessKgPerCm2");
        evaluateCriterion(evaluations, findings, unavailable, "colorCoveragePct", "%", ">=", true,
                minColor, color, criteriaTrusted, measurementTrusted,
                "criteria.minColorCoveragePct", "measurements.colorCoveragePct");

        boolean sampleSizeProblem = false;
        if (criteriaTrusted && criteria.minimumSampleSize() != null) {
            if (!measurementTrusted || measurements.sampleSize() == null) {
                sampleSizeProblem = true;
                addFinding(findings, "HARVEST_SAMPLE_SIZE_MISSING", Severity.DATA_GAP,
                        "采收标准要求最小样本量，但实测记录缺少样本量。");
                addUnavailable(unavailable, "measurements.sampleSize");
            } else if (measurements.sampleSize() < criteria.minimumSampleSize()) {
                sampleSizeProblem = true;
                addFinding(findings, "HARVEST_SAMPLE_SIZE_BELOW_MINIMUM", Severity.DATA_GAP,
                        "实测样本量 " + measurements.sampleSize() + " 小于标准要求的 "
                                + criteria.minimumSampleSize() + "。");
            } else {
                addFinding(findings, "HARVEST_SAMPLE_SIZE_VERIFIED", Severity.INFO,
                        "实测样本量满足已验证采收标准。");
            }
        } else if (criteriaTrusted) {
            addUnavailable(unavailable, "criteria.minimumSampleSize");
        }

        long configured = evaluations.stream()
                .filter(value -> value.result() != CriterionResult.NOT_CONFIGURED)
                .count();
        long missingMeasurements = evaluations.stream()
                .filter(value -> value.result() == CriterionResult.MEASUREMENT_MISSING)
                .count();
        long failed = evaluations.stream()
                .filter(value -> value.result() == CriterionResult.FAIL)
                .count();

        boolean measurementContextInvalid = criteria == null || measurements == null
                || !criteriaTrusted || !measurementTrusted || measuredOn == null
                || (assessmentDate != null && measuredOn.isAfter(assessmentDate));
        HarvestMaturityStatus maturityStatus;
        if (measurementContextInvalid || configured == 0 || missingMeasurements > 0 || sampleSizeProblem) {
            maturityStatus = HarvestMaturityStatus.INSUFFICIENT_DATA;
        } else if (failed > 0) {
            maturityStatus = HarvestMaturityStatus.BELOW_PROVIDED_CRITERIA;
        } else {
            maturityStatus = HarvestMaturityStatus.MEETS_PROVIDED_CRITERIA;
        }

        List<HarvestWeatherAssessment> weatherWindows = new ArrayList<>();
        if (request.weatherWindows().isEmpty()) {
            addFinding(findings, "HARVEST_WEATHER_WINDOWS_MISSING", Severity.DATA_GAP,
                    "缺少采收天气窗口，不能判断近期是否适合采收作业。");
            addUnavailable(unavailable, "harvestWeatherWindows");
        } else {
            for (int index = 0; index < request.weatherWindows().size(); index++) {
                weatherWindows.add(assessHarvestWindow(request.weatherWindows().get(index), index, unavailable));
            }
        }

        HarvestWeatherAssessment bestWeather = bestHarvestWindow(weatherWindows);
        HarvestWeatherStatus weatherStatus = bestWeather == null
                ? HarvestWeatherStatus.INSUFFICIENT_DATA : bestWeather.status();
        if (!weatherWindows.isEmpty() && weatherStatus == HarvestWeatherStatus.INSUFFICIENT_DATA) {
            addFinding(findings, "HARVEST_WEATHER_INCOMPLETE", Severity.DATA_GAP,
                    "所有采收天气窗口都缺少关键降水字段。");
        }

        HarvestDecision decision = harvestDecision(maturityStatus, weatherStatus);
        return new HarvestAssessment(RULE_VERSION, decision, maturityStatus, weatherStatus,
                assessmentDate, measuredOn, measurements == null ? null : measurements.sampleSize(),
                measurements == null ? null : measurements.provenance(),
                criteria == null ? null : criteria.provenance(),
                evaluations, bestWeather, weatherWindows, findings, unavailable);
    }

    private HarvestWeatherAssessment assessHarvestWindow(HarvestWeatherWindow window, int index,
                                                         List<String> globalUnavailable) {
        List<String> windowUnavailable = new ArrayList<>();
        if (window == null) {
            String field = "harvestWeatherWindows[" + index + "]";
            addUnavailable(windowUnavailable, field);
            addUnavailableAll(globalUnavailable, windowUnavailable);
            return new HarvestWeatherAssessment(field, null, null, null, null,
                    HarvestWeatherStatus.INSUFFICIENT_DATA, null,
                    List.of("采收天气窗口为空。"), windowUnavailable, "MISSING");
        }

        String id = window.id() == null ? "index-" + index : window.id();
        String prefix = "harvestWeatherWindows[" + id + "]";
        Double precipitation = finiteOrNull(window.precipitationMm());
        Double probability = finiteOrNull(window.precipitationProbabilityPct());
        List<String> unavailable = new ArrayList<>();
        if (precipitation == null) addUnavailable(unavailable, prefix + ".precipitationMm");
        if (probability == null) addUnavailable(unavailable, prefix + ".precipitationProbabilityPct");
        if (window.provenance() == null || !window.provenance().usable()) {
            addUnavailable(unavailable, prefix + ".provenance");
        }
        if (window.startsAt() == null) addUnavailable(unavailable, prefix + ".startsAt");
        if (window.endsAt() == null) addUnavailable(unavailable, prefix + ".endsAt");
        addUnavailableAll(globalUnavailable, unavailable);

        if (window.provenance() == null || !window.provenance().usable()
                || (precipitation == null && probability == null)) {
            return new HarvestWeatherAssessment(id, window.startsAt(), window.endsAt(),
                    precipitation, probability, HarvestWeatherStatus.INSUFFICIENT_DATA, null,
                    List.of("缺少可信来源或降水数据，不能判断采收天气窗口。"), unavailable,
                    provenanceMarker(window.provenance()));
        }

        int risk = 0;
        List<String> reasons = new ArrayList<>();
        if (probability != null) {
            if (probability >= 70D) {
                risk = Math.max(risk, 90);
                reasons.add("降雨概率达到 70% 以上。");
            } else if (probability >= 40D) {
                risk = Math.max(risk, 65);
                reasons.add("降雨概率达到 40% 以上。");
            } else if (probability >= 20D) {
                risk = Math.max(risk, 35);
                reasons.add("存在一定降雨概率。");
            } else {
                risk = Math.max(risk, 10);
                reasons.add("降雨概率较低。");
            }
        }
        if (precipitation != null) {
            if (precipitation >= 5D) {
                risk = Math.max(risk, 95);
                reasons.add("预报降水量达到 5 mm 以上。");
            } else if (precipitation >= 1D) {
                risk = Math.max(risk, 65);
                reasons.add("预报有 1 mm 以上降水。");
            } else if (precipitation > 0D) {
                risk = Math.max(risk, 35);
                reasons.add("预报有少量降水。");
            } else {
                risk = Math.max(risk, 10);
                reasons.add("预报无有效降水。");
            }
        }

        boolean partial = precipitation == null || probability == null;
        HarvestWeatherStatus status;
        if (partial) {
            status = risk >= 60 ? HarvestWeatherStatus.RISK : HarvestWeatherStatus.CAUTION;
            reasons.add("降水字段不完整，按保守口径判定。");
        } else {
            status = risk >= 70 ? HarvestWeatherStatus.RISK
                    : risk >= 40 ? HarvestWeatherStatus.CAUTION
                    : HarvestWeatherStatus.FAVORABLE;
        }
        if (reasons.isEmpty()) reasons.add("降水条件处于可评估范围。");
        return new HarvestWeatherAssessment(id, window.startsAt(), window.endsAt(),
                precipitation, probability, status, risk, reasons, unavailable,
                provenanceMarker(window.provenance()));
    }

    private static void evaluateCriterion(List<CriterionEvaluation> evaluations,
                                           List<Finding> findings, List<String> unavailable,
                                           String code, String unit, String requirement, boolean minimum,
                                           Double threshold, Double measured,
                                           boolean criteriaTrusted, boolean measurementTrusted,
                                           String criteriaField, String measurementField) {
        threshold = finiteOrNull(threshold);
        measured = finiteOrNull(measured);
        if (!criteriaTrusted || threshold == null) {
            evaluations.add(new CriterionEvaluation(code, unit, requirement, null, measured,
                    CriterionResult.NOT_CONFIGURED));
            if (criteriaTrusted) addUnavailable(unavailable, criteriaField);
            return;
        }
        if (!measurementTrusted || measured == null) {
            evaluations.add(new CriterionEvaluation(code, unit, requirement, threshold, null,
                    CriterionResult.MEASUREMENT_MISSING));
            addUnavailable(unavailable, measurementField);
            if (measurementTrusted) {
                addFinding(findings, "HARVEST_MEASUREMENT_MISSING:" + code, Severity.DATA_GAP,
                        "已验证采收标准要求 " + code + "，但缺少对应实测值。");
            }
            return;
        }
        boolean pass = minimum ? measured >= threshold : measured <= threshold;
        evaluations.add(new CriterionEvaluation(code, unit, requirement, threshold, measured,
                pass ? CriterionResult.PASS : CriterionResult.FAIL));
    }

    private static SprayWindowAssessment bestSprayWindow(List<SprayWindowAssessment> windows) {
        if (windows.isEmpty()) return null;
        return windows.stream()
                .filter(window -> window.suitability() != SprayWindowSuitability.INSUFFICIENT_DATA)
                .min(Comparator
                        .comparingInt((SprayWindowAssessment window) -> sprayRank(window.suitability()))
                        .thenComparing(window -> window.score() == null ? Integer.MIN_VALUE : window.score(),
                                Comparator.reverseOrder())
                        .thenComparing(window -> window.startsAt() == null
                                ? LocalDateTime.MAX : window.startsAt()))
                .orElse(windows.get(0));
    }

    private static int sprayRank(SprayWindowSuitability suitability) {
        return switch (suitability) {
            case SUITABLE -> 0;
            case CAUTION -> 1;
            case UNSUITABLE -> 2;
            case INSUFFICIENT_DATA -> 3;
        };
    }

    private static Decision sprayDecision(List<Finding> findings, SprayWindowAssessment best) {
        if (findings.stream().anyMatch(finding -> finding.severity() == Severity.BLOCKER)) {
            return Decision.BLOCKED;
        }
        if (findings.stream().anyMatch(finding -> finding.severity() == Severity.DATA_GAP)) {
            return Decision.INSUFFICIENT_DATA;
        }
        if (best == null) return Decision.INSUFFICIENT_DATA;
        return switch (best.suitability()) {
            case SUITABLE -> Decision.ELIGIBLE;
            case CAUTION -> Decision.CAUTION;
            case UNSUITABLE -> Decision.UNSUITABLE;
            case INSUFFICIENT_DATA -> Decision.INSUFFICIENT_DATA;
        };
    }

    private static HarvestWeatherAssessment bestHarvestWindow(List<HarvestWeatherAssessment> windows) {
        if (windows.isEmpty()) return null;
        return windows.stream()
                .min(Comparator
                        .comparingInt((HarvestWeatherAssessment window) -> harvestRank(window.status()))
                        .thenComparing(window -> window.rainRiskScore() == null
                                ? Integer.MAX_VALUE : window.rainRiskScore())
                        .thenComparing(window -> window.startsAt() == null
                                ? LocalDateTime.MAX : window.startsAt()))
                .orElse(windows.get(0));
    }

    private static int harvestRank(HarvestWeatherStatus status) {
        return switch (status) {
            case FAVORABLE -> 0;
            case CAUTION -> 1;
            case RISK -> 2;
            case INSUFFICIENT_DATA -> 3;
        };
    }

    private static HarvestDecision harvestDecision(HarvestMaturityStatus maturity,
                                                   HarvestWeatherStatus weather) {
        if (maturity == null || maturity == HarvestMaturityStatus.INSUFFICIENT_DATA) {
            return HarvestDecision.INSUFFICIENT_DATA;
        }
        if (maturity == HarvestMaturityStatus.BELOW_PROVIDED_CRITERIA) {
            return HarvestDecision.WAIT_FOR_MATURITY;
        }
        if (weather == null || weather == HarvestWeatherStatus.INSUFFICIENT_DATA) {
            return HarvestDecision.WEATHER_DATA_UNAVAILABLE;
        }
        return switch (weather) {
            case FAVORABLE -> HarvestDecision.READY_FOR_MANUAL_REVIEW;
            case CAUTION -> HarvestDecision.CAUTION;
            case RISK -> HarvestDecision.WEATHER_RISK;
            case INSUFFICIENT_DATA -> HarvestDecision.WEATHER_DATA_UNAVAILABLE;
        };
    }

    /**
     * Correct a baseline phenology stage using only verified field/expert observations or
     * local calibration windows. Conflicting trusted evidence leaves the effective stage null
     * and requests review. Unverified or future evidence is ignored.
     */
    public PhenologyCorrection correctPhenology(PhenologyCorrectionRequest request) {
        List<Finding> findings = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (request == null) {
            addFinding(findings, "REQUEST_MISSING", Severity.DATA_GAP, "未提供物候校正输入。");
            addUnavailable(unavailable, "phenologyCorrectionRequest");
            return invalidPhenology(null, null, null, null, null, findings, unavailable);
        }

        LocalDate asOf = request.asOfDate();
        String cropCode = request.cropCode();
        String baselineRaw = request.baselineStageCode();
        String baselineSource;
        if (baselineRaw != null) {
            baselineSource = "PROVIDED_BASELINE";
        } else if (cropCode != null) {
            baselineSource = "SERVER_PHENOLOGY_RULE";
        } else {
            baselineSource = null;
        }
        if (asOf == null) {
            addFinding(findings, "PHENOLOGY_AS_OF_DATE_MISSING", Severity.DATA_GAP,
                    "缺少物候校正基准日期。");
            addUnavailable(unavailable, "asOfDate");
            return invalidPhenology(null, cropCode, canonicalStage(cropCode, baselineRaw), baselineSource,
                    request.baselineProvenance(), findings, unavailable);
        }

        if (baselineRaw == null && cropCode != null) {
            PhenologyResult result = phenology.calculate(cropCode, asOf, request.registeredStageCode());
            baselineRaw = result.currentCode();
        }
        String baseline = canonicalStage(cropCode, baselineRaw);
        String baselineMarker = request.baselineProvenance() != null
                ? request.baselineProvenance().marker()
                : baselineSource == null || baseline == null ? "MISSING" : baselineSource;
        if (baseline == null) {
            addUnavailable(unavailable, "baselineStageCode");
        }

        List<PhenologyObservation> trustedObservations = new ArrayList<>();
        for (int index = 0; index < request.observations().size(); index++) {
            PhenologyObservation observation = request.observations().get(index);
            String prefix = "observations[" + index + "]";
            if (observation == null) {
                addUnavailable(unavailable, prefix);
                continue;
            }
            String stage = canonicalStage(cropCode, observation.stageCode());
            if (stage == null || observation.observedOn() == null) {
                addUnavailable(unavailable, prefix + ".stageCode");
                addUnavailable(unavailable, prefix + ".observedOn");
                continue;
            }
            if (observation.observedOn().isAfter(asOf)) {
                addFinding(findings, "PHENOLOGY_OBSERVATION_AFTER_AS_OF_DATE", Severity.WARNING,
                        prefix + " 的观测日期晚于校正基准日期，已忽略。");
                continue;
            }
            if (!trustedPhenologyEvidence(observation.provenance())) {
                addFinding(findings, "PHENOLOGY_OBSERVATION_UNTRUSTED", Severity.WARNING,
                        prefix + " 的物候观测缺少可信现场/专家/校准来源，已忽略。");
                continue;
            }
            trustedObservations.add(new PhenologyObservation(stage, observation.observedOn(),
                    observation.provenance()));
        }

        LocalDate latestObservationDate = null;
        Set<String> latestObservationStages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Provenance latestObservationProvenance = null;
        for (PhenologyObservation observation : trustedObservations) {
            int comparison = latestObservationDate == null ? 1
                    : observation.observedOn().compareTo(latestObservationDate);
            if (comparison > 0) {
                latestObservationDate = observation.observedOn();
                latestObservationStages.clear();
                latestObservationProvenance = observation.provenance();
            }
            if (observation.observedOn().equals(latestObservationDate)) {
                latestObservationStages.add(observation.stageCode());
            }
        }

        Set<String> calibrationStages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Provenance calibrationProvenance = null;
        for (int index = 0; index < request.calibrationWindows().size(); index++) {
            PhenologyCalibrationWindow calibration = request.calibrationWindows().get(index);
            String prefix = "calibrationWindows[" + index + "]";
            if (calibration == null) {
                addUnavailable(unavailable, prefix);
                continue;
            }
            if (calibration.startsOn() == null || calibration.endsOn() == null) {
                addUnavailable(unavailable, prefix + ".startsOn");
                addUnavailable(unavailable, prefix + ".endsOn");
                continue;
            }
            if (calibration.startsOn().isAfter(calibration.endsOn())) {
                addFinding(findings, "PHENOLOGY_CALIBRATION_INVALID_RANGE", Severity.WARNING,
                        prefix + " 的起止日期颠倒，已忽略。");
                continue;
            }
            if (asOf.isBefore(calibration.startsOn()) || asOf.isAfter(calibration.endsOn())) {
                continue;
            }
            if (!trustedPhenologyEvidence(calibration.provenance())) {
                addFinding(findings, "PHENOLOGY_CALIBRATION_UNTRUSTED", Severity.WARNING,
                        prefix + " 的物候校准缺少可信来源，已忽略。");
                continue;
            }
            String stage = canonicalStage(cropCode, calibration.stageCode());
            if (stage == null) {
                addUnavailable(unavailable, prefix + ".stageCode");
                continue;
            }
            calibrationStages.add(stage);
            if (calibrationProvenance == null) calibrationProvenance = calibration.provenance();
        }

        boolean observationConflict = latestObservationStages.size() > 1;
        boolean calibrationConflict = calibrationStages.size() > 1;
        if (observationConflict || (latestObservationStages.isEmpty() && calibrationConflict)) {
            addFinding(findings, "PHENOLOGY_EVIDENCE_CONFLICT", Severity.BLOCKER,
                    "同期可信物候证据指向不同阶段，拒绝自动校正，需人工复核。");
            addUnavailable(unavailable, "disambiguatingPhenologyEvidence");
            return new PhenologyCorrection(RULE_VERSION, PhenologyCorrectionStatus.CONFLICTING_EVIDENCE,
                    asOf, cropCode, baseline, baselineSource, baselineMarker, null, null, null,
                    false, true, null, null, null, findings, unavailable);
        }

        String chosenStage = latestObservationStages.isEmpty()
                ? calibrationStages.stream().findFirst().orElse(null)
                : latestObservationStages.stream().findFirst().orElse(null);
        Provenance chosenProvenance = latestObservationStages.isEmpty()
                ? calibrationProvenance : latestObservationProvenance;
        LocalDate evidenceDate = latestObservationStages.isEmpty()
                ? (chosenStage == null ? null : asOf) : latestObservationDate;

        if (chosenStage != null && !latestObservationStages.isEmpty() && calibrationConflict) {
            addFinding(findings, "PHENOLOGY_CALIBRATION_CONFLICT_IGNORED", Severity.WARNING,
                    "校准窗口之间存在冲突；以最新可信现场/专家观测为准。");
        }
        if (chosenStage != null && !latestObservationStages.isEmpty() && calibrationStages.size() == 1
                && !sameStage(chosenStage, calibrationStages.iterator().next())) {
            addFinding(findings, "PHENOLOGY_CALIBRATION_SUPERSEDED", Severity.WARNING,
                    "最新可信观测与校准窗口不一致；以直接观测为准。");
        }
        if (chosenProvenance != null && chosenProvenance.sourceKind() == SourceKind.LOCAL_CALIBRATION
                && chosenStage != null) {
            addFinding(findings, "PHENOLOGY_CORRECTION_SOURCE_CALIBRATION", Severity.INFO,
                    "物候阶段由本地校准窗口校正，未使用虚构品种系数。");
        }

        if (chosenStage == null) {
            if (baseline == null) {
                addFinding(findings, "PHENOLOGY_BASELINE_AND_EVIDENCE_MISSING", Severity.DATA_GAP,
                        "缺少基线物候阶段，也没有可信校正证据。");
                addUnavailable(unavailable, "trustedPhenologyEvidence");
                return invalidPhenology(asOf, cropCode, null, baselineSource,
                        request.baselineProvenance(), findings, unavailable);
            }
            addFinding(findings, "TRUSTED_PHENOLOGY_EVIDENCE_MISSING", Severity.DATA_GAP,
                    "没有可信物候观测或校准证据，保留基线阶段，不推断品种系数。");
            addUnavailable(unavailable, "trustedPhenologyEvidence");
            return new PhenologyCorrection(RULE_VERSION, PhenologyCorrectionStatus.NO_TRUSTED_EVIDENCE,
                    asOf, cropCode, baseline, baselineSource, baselineMarker, null, null, baseline,
                    false, false, null, null, null, findings, unavailable);
        }

        boolean confirmed = baseline != null && sameStage(baseline, chosenStage);
        PhenologyCorrectionStatus status;
        if (baseline == null) {
            status = PhenologyCorrectionStatus.EVIDENCE_ONLY;
        } else if (confirmed) {
            status = PhenologyCorrectionStatus.CONFIRMED;
        } else {
            status = PhenologyCorrectionStatus.CORRECTED;
        }
        boolean changed = baseline == null || !confirmed;
        addFinding(findings, status == PhenologyCorrectionStatus.CORRECTED
                        ? "PHENOLOGY_CORRECTED_BY_TRUSTED_EVIDENCE"
                        : status == PhenologyCorrectionStatus.CONFIRMED
                        ? "PHENOLOGY_CONFIRMED_BY_TRUSTED_EVIDENCE"
                        : "PHENOLOGY_EVIDENCE_ONLY",
                Severity.INFO, status == PhenologyCorrectionStatus.CORRECTED
                        ? "基线阶段已按可信证据校正为 " + chosenStage + "。"
                        : status == PhenologyCorrectionStatus.CONFIRMED
                        ? "可信证据确认基线阶段为 " + chosenStage + "。"
                        : "仅有可信证据确定阶段为 " + chosenStage + "，无基线可比较。");

        return new PhenologyCorrection(RULE_VERSION, status, asOf, cropCode, baseline, baselineSource,
                baselineMarker, chosenStage, evidenceDate, chosenStage, changed, false,
                chosenProvenance == null ? null : chosenProvenance.sourceKind(),
                chosenProvenance == null ? null : chosenProvenance.marker(),
                chosenProvenance, findings, unavailable);
    }

    private static PhenologyCorrection invalidPhenology(LocalDate asOf, String cropCode,
                                                        String baseline, String baselineSource,
                                                        Provenance baselineProvenance,
                                                        List<Finding> findings,
                                                        List<String> unavailable) {
        return new PhenologyCorrection(RULE_VERSION, PhenologyCorrectionStatus.INVALID_REQUEST,
                asOf, cropCode, baseline, baselineSource,
                baselineProvenance == null ? "MISSING" : baselineProvenance.marker(),
                null, null, null, false, true, null, null, null, findings, unavailable);
    }

    private static boolean trustedPhenologyEvidence(Provenance provenance) {
        if (provenance == null || !provenance.usable()) return false;
        return switch (provenance.sourceKind()) {
            case FIELD_OBSERVATION, EXPERT_OBSERVATION, LOCAL_CALIBRATION, ORCHARD_RECORD -> true;
            default -> false;
        };
    }

    private String canonicalStage(String cropCode, String rawStage) {
        if (rawStage == null) return null;
        if (cropCode != null) {
            PhenologyStage stage = phenology.resolveStage(cropCode, rawStage);
            if (stage != null) return stage.code();
        }
        return rawStage.trim();
    }

    private static boolean explicitlyAllowed(OrchardCertification certification, LabelInput label) {
        return containsIgnoreCase(certification.explicitlyAllowedProductIds(), label.productId())
                || containsIgnoreCase(certification.explicitlyAllowedActiveIngredients(),
                label.activeIngredient());
    }

    private static boolean containsIgnoreCase(Set<String> values, String target) {
        if (values == null || target == null) return false;
        String normalized = target.trim();
        if (normalized.isEmpty()) return false;
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }

    private static boolean sameStage(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean isRipeningOrHarvest(String stage) {
        if (stage == null) return false;
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("ripening")
                || normalized.equals("harvest")
                || normalized.contains("转色")
                || normalized.contains("成熟")
                || normalized.contains("采收");
    }

    private static boolean hasRestrictionProblem(List<Finding> findings) {
        for (Finding finding : findings) {
            if (finding.severity() == Severity.BLOCKER) return true;
            if (finding.severity() == Severity.DATA_GAP && isRestrictionFinding(finding.code())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRestrictionFinding(String code) {
        if (code == null) return false;
        return code.startsWith("LABEL_")
                || code.startsWith("PHI_")
                || code.startsWith("PRODUCT_")
                || code.startsWith("CERTIFICATION_")
                || code.startsWith("PLANNED_SPRAY_DATE_")
                || code.startsWith("EXPECTED_HARVEST_DATE_");
    }

    private static String provenanceMarker(Provenance provenance) {
        return provenance == null ? "MISSING" : provenance.marker();
    }

    private static Double finiteOrNull(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Set<String> normalizedSet(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) normalized.add(trimmed);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static <T> List<T> immutableList(List<T> values) {
        if (values == null || values.isEmpty()) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static void addFinding(List<Finding> findings, String code, Severity severity, String message) {
        findings.add(new Finding(code, severity, message));
    }

    private static void addUnavailable(List<String> unavailable, String field) {
        if (field != null && !field.isBlank() && !unavailable.contains(field)) {
            unavailable.add(field);
        }
    }

    private static void addUnavailableAll(List<String> target, List<String> values) {
        if (values == null) return;
        for (String value : values) addUnavailable(target, value);
    }

    public record HarvestMeasurements(Double solubleSolidsBrix, Double titratableAcidityPct,
                                      Double firmnessKgPerCm2, Double colorCoveragePct,
                                      Integer sampleSize, LocalDate measuredOn,
                                      Provenance provenance) {}

    /**
     * Thresholds must come from a verified local calibration, cultivar standard, or other
     * authoritative source. The engine never fills absent thresholds from memory.
     */
    public record HarvestCriteria(Double minSolubleSolidsBrix, Double maxTitratableAcidityPct,
                                  Double minFirmnessKgPerCm2, Double minColorCoveragePct,
                                  Integer minimumSampleSize, Provenance provenance) {}

    public record CriterionEvaluation(String code, String unit, String requirement,
                                      Double threshold, Double measured, CriterionResult result) {
        public CriterionEvaluation {
            code = code == null ? "UNKNOWN" : code;
            unit = unit == null ? "" : unit;
            requirement = requirement == null ? "" : requirement;
            result = result == null ? CriterionResult.NOT_CONFIGURED : result;
        }
    }

    public record HarvestWeatherWindow(String id, LocalDateTime startsAt, LocalDateTime endsAt,
                                       Double precipitationMm, Double precipitationProbabilityPct,
                                       Provenance provenance) {
        public HarvestWeatherWindow {
            id = trimToNull(id);
        }
    }

    public record HarvestWeatherAssessment(String id, LocalDateTime startsAt, LocalDateTime endsAt,
                                           Double precipitationMm,
                                           Double precipitationProbabilityPct,
                                           HarvestWeatherStatus status, Integer rainRiskScore,
                                           List<String> reasons, List<String> unavailableData,
                                           String provenanceMarker) {
        public HarvestWeatherAssessment {
            status = status == null ? HarvestWeatherStatus.INSUFFICIENT_DATA : status;
            reasons = immutableList(reasons);
            unavailableData = immutableList(unavailableData);
            provenanceMarker = provenanceMarker == null ? "MISSING" : provenanceMarker;
        }
    }

    public record HarvestAssessmentRequest(LocalDate assessmentDate,
                                           HarvestMeasurements measurements,
                                           HarvestCriteria criteria,
                                           List<HarvestWeatherWindow> weatherWindows) {
        public HarvestAssessmentRequest {
            weatherWindows = immutableList(weatherWindows);
        }
    }

    public record HarvestAssessment(String ruleVersion, HarvestDecision decision,
                                    HarvestMaturityStatus maturityStatus,
                                    HarvestWeatherStatus weatherStatus,
                                    LocalDate assessmentDate, LocalDate measuredOn,
                                    Integer sampleSize, Provenance measurementProvenance,
                                    Provenance criteriaProvenance,
                                    List<CriterionEvaluation> criteria,
                                    HarvestWeatherAssessment bestWeatherWindow,
                                    List<HarvestWeatherAssessment> weatherWindows,
                                    List<Finding> findings, List<String> unavailableData) {
        public HarvestAssessment {
            criteria = immutableList(criteria);
            weatherWindows = immutableList(weatherWindows);
            findings = immutableList(findings);
            unavailableData = immutableList(unavailableData);
        }
    }

    public record PhenologyObservation(String stageCode, LocalDate observedOn,
                                       Provenance provenance) {
        public PhenologyObservation {
            stageCode = trimToNull(stageCode);
        }
    }

    public record PhenologyCalibrationWindow(String stageCode, LocalDate startsOn,
                                             LocalDate endsOn, Provenance provenance) {
        public PhenologyCalibrationWindow {
            stageCode = trimToNull(stageCode);
        }
    }

    public record PhenologyCorrectionRequest(String cropCode, LocalDate asOfDate,
                                             String registeredStageCode,
                                             String baselineStageCode,
                                             Provenance baselineProvenance,
                                             List<PhenologyObservation> observations,
                                             List<PhenologyCalibrationWindow> calibrationWindows) {
        public PhenologyCorrectionRequest {
            cropCode = trimToNull(cropCode);
            registeredStageCode = trimToNull(registeredStageCode);
            baselineStageCode = trimToNull(baselineStageCode);
            observations = immutableList(observations);
            calibrationWindows = immutableList(calibrationWindows);
        }
    }

    public record PhenologyCorrection(String ruleVersion, PhenologyCorrectionStatus status,
                                      LocalDate asOfDate, String cropCode,
                                      String baselineStageCode, String baselineSource,
                                      String baselineProvenanceMarker,
                                      String evidenceStageCode, LocalDate evidenceDate,
                                      String effectiveStageCode, boolean changed,
                                      boolean requiresReview,
                                      SourceKind appliedEvidenceKind,
                                      String appliedEvidenceMarker,
                                      Provenance appliedEvidence,
                                      List<Finding> findings, List<String> unavailableData) {
        public PhenologyCorrection {
            findings = immutableList(findings);
            unavailableData = immutableList(unavailableData);
        }
    }
}

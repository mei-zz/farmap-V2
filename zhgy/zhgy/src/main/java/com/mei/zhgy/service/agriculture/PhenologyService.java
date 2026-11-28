package com.mei.zhgy.service.agriculture;

import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic, per-crop phenology calendar. It never asks the model to infer stage order. */
@Service
public class PhenologyService {
    public static final double NOMINAL_ELEVATION_METERS = 550.0D;
    private static final int EARLY_VARIETY_OFFSET_DAYS = -12;
    private static final double ELEVATION_DAYS_PER_100_METERS = 8.0D / 3.0D;
    public static final int LATE_HIGH_ALTITUDE_MIN_OFFSET_DAYS = 15;
    public static final int LATE_HIGH_ALTITUDE_MAX_OFFSET_DAYS = 25;

    public static final String ASSUMPTION_ELEVATION_REFERENCE_550M = "ELEVATION_REFERENCE_550M";
    public static final String ASSUMPTION_GDD_RULE_ENGINE_AUTHORITATIVE =
            "CALIBRATED_GDD_RULE_ENGINE_AUTHORITATIVE";
    public static final String ASSUMPTION_GDD_THRESHOLD_NOT_CONFIGURED = "GDD_THRESHOLD_NOT_CONFIGURED";
    public static final String ASSUMPTION_GDD_OBSERVATION_MISSING = "GDD_OBSERVATION_MISSING";
    public static final String ASSUMPTION_GDD_OBSERVATION_INVALID = "GDD_OBSERVATION_INVALID";

    private static final List<PhenologyStage> PLUM_STAGES = List.of(
            stage("dormancy", "休眠期", 11, 21, 2, 20, 10, false,
                    "越冬", "休眠", "冬季休眠"),
            stage("germination", "萌芽期", 2, 21, 3, 4, 20, false,
                    "萌动", "萌动期", "花芽萌动", "萌芽", "发芽"),
            stage("budding", "花蕾期", 3, 5, 3, 14, 30, false,
                    "现蕾", "现蕾期", "露白", "露白期"),
            stage("flowering", "花期", 3, 15, 3, 28, 40, false,
                    "初花", "初花期", "盛花", "盛花期", "末花", "末花期", "开花", "开花期"),
            stage("leafing", "展叶期", 3, 29, 4, 8, 50, false,
                    "展叶", "春梢", "抽梢", "新梢"),
            stage("fruiting", "坐果期", 4, 9, 4, 25, 60, false,
                    "坐果", "幼果", "幼果期", "谢花坐果", "谢花期"),
            stage("fruit_development", "果实发育期", 4, 26, 6, 20, 70, false,
                    "果实膨大期", "膨大期", "膨果期", "膨果", "膨大", "硬核期", "硬核", "幼果膨大",
                    "果实膨大", "果实生长期", "果实增长期"),
            stage("ripening", "转色成熟期", 6, 21, 7, 10, 80, false,
                    "成熟期", "转色", "转色期", "软化", "成熟", "果实成熟期", "变色期"),
            stage("harvest", "采收期", 7, 11, 8, 10, 90, false,
                    "采摘", "上市", "收获", "收获期", "采收"),
            stage("post_harvest", "采后恢复期", 8, 11, 11, 20, 100, false,
                    "采后", "采后期", "恢复期", "采后管理期")
    );
    private static final Map<String, String> PLUM_ATLAS_ALIASES = buildPlumAtlasAliases();

    private final CropResolver cropResolver;
    private final List<PhenologyCalendar> calendars;

    public PhenologyService() { this(new CropResolver()); }
    public PhenologyService(CropResolver cropResolver) {
        this.cropResolver = cropResolver == null ? new CropResolver() : cropResolver;
        this.calendars = List.of(plum(), citrus(), rice(), riceLate());
    }

    public PhenologyResult calculate(String cropCode, LocalDate date, String registeredStage) {
        return calculate(cropCode, date, registeredStage, null, null, null).result();
    }

    /**
     * Calculates the plum baseline on an altitude-adjusted effective date. GDD is carried as an
     * observation only: no threshold is assumed here, and the calibrated GDD rule engine remains
     * authoritative for any GDD-based correction.
     */
    public PhenologyAssessment calculate(String cropCode, LocalDate date, String registeredStage,
                                         Double elevationMeters, String varietySubType,
                                         Double gddAccumulated) {
        PhenologyCalendar calendar = calendarForCrop(cropCode);
        boolean plum = calendar != null && "plum".equalsIgnoreCase(calendar.cropCode());
        ElevationAdjustment elevation = elevationAdjustment(plum, elevationMeters, varietySubType);
        int varietyDays = plum ? varietyOffset(elevation.varietyClass(), elevation.modelElevationMeters()) : 0;
        LocalDate effectiveDate = date == null ? null : date.minusDays(elevation.offsetDays() + varietyDays);
        PhenologyResult result = calendar == null || date == null
                ? emptyResult()
                : calculateForCalendar(calendar, effectiveDate, registeredStage);
        StageBasis stageBasis = result.registeredStage() != null
                ? StageBasis.REGISTERED_STAGE
                : result.dateDerivedStage() == null ? StageBasis.UNAVAILABLE
                : elevation.offsetDays() != 0 || varietyDays != 0 ? StageBasis.DATE_PROFILE_ADJUSTED : StageBasis.DATE_BASELINE;
        GddObservation gdd = gddObservation(gddAccumulated);
        return new PhenologyAssessment(result, date, effectiveDate, stageBasis, elevation,
                varietySubType, varietyDays, gdd, assumptions(gdd));
    }

    private PhenologyResult calculateForCalendar(PhenologyCalendar calendar, LocalDate date,
                                                 String registeredStage) {
        if (calendar == null || date == null) return emptyResult();
        MonthDay today = MonthDay.from(date);
        List<PhenologyStage> active = calendar.stages().stream().filter(stage -> stage.contains(today))
                .sorted(Comparator.comparingInt(PhenologyStage::priority)).toList();
        PhenologyStage dateStage = active.isEmpty() ? null : active.get(active.size() - 1);
        boolean cycleEnded = calendar.terminalAfterHarvest() && dateStage == null && afterTerminal(calendar, today);
        if (cycleEnded) dateStage = new PhenologyStage("post_cycle", "收获后/本茬已结束", List.of("冬闲", "收获后"), today, today, Integer.MAX_VALUE, true);

        PhenologyStage registered = findStage(calendar, registeredStage);
        PhenologyStage current = registered != null ? registered : dateStage;
        boolean mismatch = registered != null && dateStage != null && !registered.code().equals(dateStage.code());
        int currentIndex = indexOf(calendar.stages(), current);
        boolean cyclic = isCyclic(calendar);
        PhenologyStage previous = currentIndex < 0 ? null
                : currentIndex > 0 ? calendar.stages().get(currentIndex - 1)
                : cyclic ? calendar.stages().get(calendar.stages().size() - 1) : null;
        PhenologyStage next = current == null || current.code().equals("post_cycle") ? null
                : currentIndex + 1 < calendar.stages().size() ? calendar.stages().get(currentIndex + 1)
                : cyclic ? calendar.stages().get(0) : null;
        Long days = next == null ? null : daysUntil(date, next.from());
        return new PhenologyResult(dateStage, registered, current, previous, next, days, mismatch, cycleEnded);
    }

    public PhenologyCalendar calendarForCrop(String cropCode) {
        CropResolver.ResolvedCrop resolved = cropResolver.resolve(cropCode);
        if (resolved.ready() && resolved.phenologyCalendarId() != null) return calendar(resolved.phenologyCalendarId());
        return calendar(cropCode);
    }

    public String cycleType(String cropCode) {
        PhenologyCalendar calendar = calendarForCrop(cropCode);
        return calendar == null ? null : calendar.cycleType();
    }

    public boolean isCyclic(String cropCode) {
        PhenologyCalendar calendar = calendarForCrop(cropCode);
        return calendar != null && isCyclic(calendar);
    }

    public List<String> orderedStageNames(String cropCode) {
        PhenologyCalendar calendar = calendarForCrop(cropCode);
        return calendar == null ? List.of() : calendar.stages().stream().map(PhenologyStage::name).toList();
    }

    private boolean isCyclic(PhenologyCalendar calendar) {
        return "deciduous_annual_cycle".equalsIgnoreCase(calendar.cycleType())
                || "evergreen_annual_cycle".equalsIgnoreCase(calendar.cycleType());
    }

    public PhenologyCalendar calendar(String cropCode) {
        if (cropCode == null || cropCode.isBlank()) return null;
        return calendars.stream().filter(item -> item.cropCode().equalsIgnoreCase(cropCode.trim())).findFirst().orElse(null);
    }

    public List<String> stageAliases() {
        List<String> result = new ArrayList<>();
        calendars.forEach(calendar -> calendar.stages().forEach(stage -> {
            result.add(stage.code()); result.add(stage.name()); result.addAll(stage.aliases());
        }));
        return result;
    }

    /** Shared longest-name-first index used by the reference atlas for every plum stage alias. */
    public static Map<String, String> plumAtlasAliases() {
        return PLUM_ATLAS_ALIASES;
    }

    public PhenologyStage resolveStage(String cropCode, String rawStage) {
        PhenologyCalendar calendar = calendarForCrop(cropCode);
        return findStage(calendar, rawStage);
    }

    private PhenologyStage findStage(PhenologyCalendar calendar, String rawStage) {
        if (calendar == null || rawStage == null || rawStage.isBlank()) return null;
        String normalized = normalize(rawStage);
        return calendar.stages().stream().filter(stage -> normalize(stage.code()).equals(normalized)
                || normalize(stage.name()).equals(normalized)
                || stage.aliases().stream().map(PhenologyService::normalize).anyMatch(normalized::equals)).findFirst().orElse(null);
    }

    private boolean afterTerminal(PhenologyCalendar calendar, MonthDay today) {
        PhenologyStage terminal = calendar.stages().stream().filter(PhenologyStage::terminal).findFirst().orElse(null);
        return terminal != null && !terminal.contains(today) && today.isAfter(terminal.to());
    }

    private int indexOf(List<PhenologyStage> stages, PhenologyStage current) {
        if (current == null) return -1;
        for (int i = 0; i < stages.size(); i++) if (stages.get(i).code().equals(current.code())) return i;
        return -1;
    }

    private Long daysUntil(LocalDate date, MonthDay target) {
        if (date == null || target == null) return null;
        for (int year = date.getYear(); year <= date.getYear() + 8; year++) {
            try {
                LocalDate candidate = target.atYear(year);
                if (candidate.isAfter(date)) return ChronoUnit.DAYS.between(date, candidate);
            } catch (DateTimeException ignored) {
                // A Feb-29 target falls through to the next valid year.
            }
        }
        return null;
    }

    private static PhenologyResult emptyResult() {
        return new PhenologyResult(null, null, null, null, null, null, false, false);
    }

    private static ElevationAdjustment elevationAdjustment(boolean plum, Double observedElevation,
                                                           String varietySubType) {
        if (!plum) {
            return new ElevationAdjustment(observedElevation,
                    observedElevation == null ? NOMINAL_ELEVATION_METERS : observedElevation, 0,
                    ElevationBasis.NOT_APPLICABLE, classifyVariety(varietySubType));
        }
        VarietyClass variety = classifyVariety(varietySubType);
        if (observedElevation == null || !Double.isFinite(observedElevation) || observedElevation < 0 || observedElevation > 9000) {
            return new ElevationAdjustment(null, NOMINAL_ELEVATION_METERS, 0,
                    ElevationBasis.ASSUMED_NOMINAL_550M, variety);
        }
        if (observedElevation >= 500.0D && observedElevation <= 600.0D) {
            return new ElevationAdjustment(observedElevation, observedElevation, 0,
                    ElevationBasis.NOMINAL_500_600M, variety);
        }
        int days = (int) Math.round((observedElevation - NOMINAL_ELEVATION_METERS) / 100D * ELEVATION_DAYS_PER_100_METERS);
        return new ElevationAdjustment(observedElevation, observedElevation, days, ElevationBasis.ALTITUDE_ADJUSTED, variety);
    }

    private static int varietyOffset(VarietyClass variety, double elevation) {
        if (variety == VarietyClass.EARLY) return EARLY_VARIETY_OFFSET_DAYS;
        if (variety != VarietyClass.LATE) return 0;
        double highAltitudeFraction = Math.max(0D, Math.min(1D, (elevation - 800D) / 400D));
        return LATE_HIGH_ALTITUDE_MIN_OFFSET_DAYS + (int) Math.round(highAltitudeFraction
                * (LATE_HIGH_ALTITUDE_MAX_OFFSET_DAYS - LATE_HIGH_ALTITUDE_MIN_OFFSET_DAYS));
    }

    private static VarietyClass classifyVariety(String varietySubType) {
        String normalized = normalize(varietySubType);
        if (normalized.contains("极晚") || normalized.contains("晚") || normalized.contains("late")) {
            return VarietyClass.LATE;
        }
        if (normalized.contains("早") || normalized.contains("early")) return VarietyClass.EARLY;
        if (normalized.equals("青脆李") || normalized.contains("中熟") || normalized.contains("mid") || normalized.contains("medium")) {
            return VarietyClass.MID;
        }
        return VarietyClass.UNKNOWN;
    }

    private static GddObservation gddObservation(Double value) {
        if (value == null) return new GddObservation(null, null, GddStatus.NOT_PROVIDED, false);
        if (!Double.isFinite(value) || value < 0.0D) {
            return new GddObservation(Double.isFinite(value) ? value : null, null,
                    GddStatus.INVALID, false);
        }
        return new GddObservation(value, null, GddStatus.OBSERVED_THRESHOLD_NOT_CONFIGURED, false);
    }

    private static List<String> assumptions(GddObservation gdd) {
        Set<String> assumptions = new LinkedHashSet<>();
        assumptions.add(ASSUMPTION_ELEVATION_REFERENCE_550M);
        assumptions.add(ASSUMPTION_GDD_RULE_ENGINE_AUTHORITATIVE);
        assumptions.add(ASSUMPTION_GDD_THRESHOLD_NOT_CONFIGURED);
        if (gdd.status() == GddStatus.NOT_PROVIDED) assumptions.add(ASSUMPTION_GDD_OBSERVATION_MISSING);
        if (gdd.status() == GddStatus.INVALID) assumptions.add(ASSUMPTION_GDD_OBSERVATION_INVALID);
        return List.copyOf(assumptions);
    }

    private static Map<String, String> buildPlumAtlasAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (PhenologyStage stage : PLUM_STAGES) {
            aliases.putIfAbsent(stage.code(), stage.code());
            aliases.putIfAbsent(stage.name(), stage.code());
            for (String alias : stage.aliases()) aliases.putIfAbsent(alias, stage.code());
        }
        List<Map.Entry<String, String>> ordered = new ArrayList<>(aliases.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                .reversed());
        Map<String, String> result = new LinkedHashMap<>();
        ordered.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-—–/]+", "");
    }

    private static MonthDay md(int month, int day) { return MonthDay.of(month, day); }
    private static PhenologyStage stage(String code, String name, int fm, int fd, int tm, int td,
                                        int priority, boolean terminal, String... aliases) {
        return new PhenologyStage(code, name, List.of(aliases), md(fm, fd), md(tm, td), priority, terminal);
    }

    private PhenologyCalendar plum() {
        return new PhenologyCalendar("plum", "deciduous_annual_cycle", PLUM_STAGES, true, false);
    }

    public enum StageBasis {
        REGISTERED_STAGE,
        DATE_BASELINE,
        DATE_PROFILE_ADJUSTED,
        UNAVAILABLE
    }

    public enum ElevationBasis {
        ASSUMED_NOMINAL_550M,
        NOMINAL_500_600M,
        ALTITUDE_ADJUSTED,
        NOT_APPLICABLE
    }

    public enum VarietyClass {
        EARLY,
        MID,
        LATE,
        UNKNOWN
    }

    public record ElevationAdjustment(Double observedElevationMeters, double modelElevationMeters,
                                      int offsetDays, ElevationBasis basis,
                                      VarietyClass varietyClass) { }

    public enum GddStatus {
        NOT_PROVIDED,
        OBSERVED_THRESHOLD_NOT_CONFIGURED,
        INVALID
    }

    /** GDD is an observation only until an authoritative, calibrated threshold is supplied. */
    public record GddObservation(Double accumulated, Double threshold, GddStatus status,
                                 boolean applied) {
        public boolean observed() { return status == GddStatus.OBSERVED_THRESHOLD_NOT_CONFIGURED; }
        public boolean thresholdConfigured() { return threshold != null; }
    }

    public record PhenologyAssessment(PhenologyResult result, LocalDate requestedDate,
                                      LocalDate effectiveDate, StageBasis stageBasis,
                                      ElevationAdjustment elevationAdjustment, String varietySubType,
                                      int varietyOffsetDays, GddObservation gddObservation, List<String> assumptions) {
        public PhenologyAssessment {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }

        public StageBasis basis() { return stageBasis; }
        public int totalOffsetDays() { return elevationAdjustment.offsetDays() + varietyOffsetDays; }
        public String calendarExplanation() {
            if (elevationAdjustment.basis() == ElevationBasis.NOT_APPLICABLE) return "作物通用物候历";
            String basis = "中熟青脆李基准历（海拔500–600m）";
            if (elevationAdjustment.observedElevationMeters() == null) basis += "，缺少海拔时按550m";
            else if (elevationAdjustment.offsetDays() != 0) basis += " + 海拔"
                    + java.math.BigDecimal.valueOf(elevationAdjustment.observedElevationMeters()).stripTrailingZeros().toPlainString()
                    + "m" + offsetText(elevationAdjustment.offsetDays());
            if (varietyOffsetDays != 0) basis += " + " + (varietyOffsetDays < 0 ? "早熟" : "晚熟") + "品种" + offsetText(varietyOffsetDays);
            return basis;
        }

        private static String offsetText(int days) { return (days < 0 ? "提前" : "推迟") + Math.abs(days) + "天"; }
        public String currentCode() { return result.currentCode(); }
        public String currentName() { return result.currentName(); }
        public String dateDerivedCode() { return result.dateDerivedCode(); }
        public String dateDerivedName() { return result.dateDerivedName(); }
        public String previousCode() { return result.previousCode(); }
        public String nextCode() { return result.nextCode(); }
        public String nextName() { return result.nextName(); }
        public Long daysUntilNext() { return result.daysUntilNext(); }
        public boolean mismatch() { return result.mismatch(); }
        public boolean cycleEnded() { return result.cycleEnded(); }
    }

    private PhenologyCalendar citrus() {
        return new PhenologyCalendar("citrus", "evergreen_annual_cycle", List.of(
                stage("bud_differentiation", "花芽形态分化期", 11, 1, 2, 28, 10, false, "花芽分化", "越冬"),
                stage("germination", "春梢抽发期", 2, 21, 3, 10, 20, false, "春梢", "萌芽"),
                stage("budding", "现蕾期", 3, 11, 3, 31, 30, false, "现蕾", "露白"),
                stage("flowering", "花期", 3, 21, 4, 30, 40, false, "初花", "盛花", "保花"),
                stage("fruiting", "生理落果稳果期", 4, 21, 6, 10, 50, false, "生理落果", "保果", "稳果"),
                stage("fruit_development", "果实膨大期", 6, 1, 9, 30, 60, false, "膨果"),
                stage("ripening", "转色成熟期", 10, 1, 3, 31, 70, false, "转色", "成熟"),
                stage("harvest", "采收期", 11, 1, 3, 31, 80, false, "采摘")
        ), false, false);
    }

    private PhenologyCalendar rice() {
        return new PhenologyCalendar("rice", "annual_linear", List.of(
                stage("sowing", "播种育秧期", 2, 21, 3, 10, 10, false, "育秧", "播种", "出苗"),
                stage("transplanting", "移栽返青期", 3, 21, 4, 10, 20, false, "插秧", "移栽", "返青"),
                stage("tillering", "分蘖期", 4, 1, 5, 20, 30, false, "发棵", "分蘖"),
                stage("jointing_booting", "拔节孕穗期", 6, 1, 7, 10, 40, false, "拔节", "孕穗", "圆秆"),
                stage("heading_flowering", "抽穗扬花期", 7, 1, 7, 31, 50, false, "抽穗", "齐穗", "扬花"),
                stage("filling", "灌浆结实期", 7, 21, 8, 20, 60, false, "灌浆", "乳熟", "蜡熟"),
                stage("harvest", "成熟收获期", 7, 21, 8, 31, 5, true, "黄熟", "收割")
        ), false, true);
    }

    private PhenologyCalendar riceLate() {
        return new PhenologyCalendar("rice_late", "annual_linear", List.of(
                stage("sowing", "播种育秧期", 7, 1, 7, 15, 10, false, "育秧", "播种", "出苗"),
                stage("transplanting", "移栽返青期", 7, 16, 8, 10, 20, false, "插秧", "移栽", "返青"),
                stage("tillering", "分蘖期", 8, 1, 8, 31, 30, false, "发棵", "分蘖"),
                stage("jointing_booting", "拔节孕穗期", 8, 21, 9, 10, 40, false, "拔节", "孕穗", "圆秆"),
                stage("heading_flowering", "抽穗扬花期", 9, 1, 9, 20, 50, false, "抽穗", "齐穗", "扬花"),
                stage("filling", "灌浆结实期", 9, 1, 10, 20, 60, false, "灌浆", "乳熟", "蜡熟"),
                stage("harvest", "成熟收获期", 10, 10, 11, 20, 5, true, "黄熟", "收割")
        ), false, true);
    }
}

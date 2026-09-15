package com.mei.zhgy.service.agriculture;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic, per-crop phenology calendar. It never asks the model to infer stage order. */
@Service
public class PhenologyService {
    private final CropResolver cropResolver;
    private final List<PhenologyCalendar> calendars;

    public PhenologyService() { this(new CropResolver()); }
    public PhenologyService(CropResolver cropResolver) {
        this.cropResolver = cropResolver == null ? new CropResolver() : cropResolver;
        this.calendars = List.of(plum(), citrus(), rice(), riceLate());
    }

    public PhenologyResult calculate(String cropCode, LocalDate date, String registeredStage) {
        PhenologyCalendar calendar = calendarForCrop(cropCode);
        if (calendar == null || date == null) return new PhenologyResult(null, null, null, null, null, null, false, false);
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

    public PhenologyStage resolveStage(String cropCode, String rawStage) {
        PhenologyCalendar calendar = calendarForCrop(cropCode);
        return findStage(calendar, rawStage);
    }

    private PhenologyStage findStage(PhenologyCalendar calendar, String rawStage) {
        if (calendar == null || rawStage == null || rawStage.isBlank()) return null;
        String normalized = normalize(rawStage);
        return calendar.stages().stream().filter(stage -> normalize(stage.code()).equals(normalized)
                || normalize(stage.name()).equals(normalized)
                || stage.aliases().stream().map(this::normalize).anyMatch(normalized::equals)).findFirst().orElse(null);
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
        LocalDate candidate = target.atYear(date.getYear());
        if (!candidate.isAfter(date)) candidate = target.atYear(date.getYear() + 1);
        return ChronoUnit.DAYS.between(date, candidate);
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-—–/]+", ""); }
    private MonthDay md(int month, int day) { return MonthDay.of(month, day); }
    private PhenologyStage stage(String code, String name, int fm, int fd, int tm, int td, int priority, boolean terminal, String... aliases) {
        return new PhenologyStage(code, name, List.of(aliases), md(fm, fd), md(tm, td), priority, terminal);
    }

    private PhenologyCalendar plum() {
        return new PhenologyCalendar("plum", "deciduous_annual_cycle", List.of(
                stage("dormancy", "休眠期", 11, 21, 2, 20, 10, false, "越冬", "休眠"),
                stage("germination", "萌芽期", 2, 21, 3, 10, 20, false, "萌动", "花芽萌动", "萌芽"),
                stage("budding", "花蕾期", 3, 1, 3, 20, 30, false, "现蕾", "露白"),
                stage("flowering", "花期", 3, 1, 3, 31, 40, false, "初花", "盛花", "末花", "开花"),
                stage("leafing", "展叶期", 3, 21, 4, 10, 50, false, "展叶", "春梢"),
                stage("fruiting", "坐果期", 4, 1, 4, 30, 60, false, "幼果", "谢花坐果"),
                stage("fruit_development", "果实发育期", 4, 11, 6, 30, 70, false, "幼果膨大", "硬核期", "膨大"),
                stage("ripening", "成熟期", 6, 21, 8, 10, 80, false, "转色", "软化", "成熟"),
                stage("harvest", "采收期", 6, 21, 8, 31, 90, false, "采摘", "上市"),
                stage("post_harvest", "采后恢复期", 8, 1, 11, 20, 100, false, "采后", "恢复期")
        ), true, false);
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

package com.mei.zhgy.service.copilot;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Business read intents and inclusive dates used by conversation cards. */
public record CopilotOperationQuery(String view, LocalDate from, LocalDate to) {
    private static final String DATE = "(\\d{4}-\\d{2}-\\d{2})";
    private static final Pattern RANGE = Pattern.compile(DATE + "\\s*(?:到|至|~|～|—)\\s*" + DATE);
    private static final Pattern ONE_DATE = Pattern.compile(DATE);
    private static final Pattern ROLLING = Pattern.compile("(?:过去|最近|近)\\s*([1-9]\\d?|[一二两三四五六七八九十])\\s*(天|周|个?月)");
    private static final Pattern NAMED_MONTH = Pattern.compile("(?<![\\d个])(?:(\\d{4})年\\s*)?(1[0-2]|[1-9])\\s*月");
    private static final Pattern TASK_ID = Pattern.compile("(?i)(?<![\\w-])(task-[\\w-]{1,75})(?![\\w-])");
    private static final Pattern FIELD_CODE = Pattern.compile("(?i)(?<![A-Za-z0-9])(L[-－]\\d{1,4})(?!\\d)");

    public static String viewOf(String text) {
        text = normalize(text);
        if (contains(text, "制定", "创建", "新建", "生成", "取消", "开始执行", "标记完成", "帮我执行", "执行这个任务", "执行当前任务", "提交反馈")
                || (text.contains("安排") && !contains(text, "查看", "查询", "看看", "展示"))) return null;
        if (contains(text, "修改排期", "调整排期", "改一下排期", "改计划日期") && taskId(text) != null) return null;
        if (contains(text, "任务日历", "农事日历", "排期日历", "任务排期") || text.matches("^(?:打开|查看|看一下)?日历[？?。！!]?$")) return "calendar";
        if (taskId(text) != null && contains(text, "任务", "查看", "反馈", "进度", "记录")) return null;
        if (contains(text, "执行档案", "执行记录", "作业记录", "农事历史", "完成记录")) return "records";
        if (contains(text, "当前任务状态", "当前任务详情", "这个任务", "该任务", "任务状态", "任务进度") && !contains(text, "列表", "清单", "所有", "全部", "统计")) return null;
        if (contains(text, "任务") && contains(text, "查询", "有哪", "有多少", "有哪些", "查看", "看看", "展示", "列表", "清单", "统计", "看板", "台账", "所有", "全部", "本周", "下周", "本月")) return "tasks";
        if (contains(text, "农活", "活儿", "哪些活", "什么活", "活没做") && contains(text, "本周", "下周", "本月", "今天", "还有", "没做", "已经做")) return "tasks";
        return null;
    }

    public static String statusFilter(String text) {
        text = normalize(text);
        if (contains(text, "没做完", "还没完成", "未完成", "没完成", "还没做完")) return "active";
        if (contains(text, "待执行", "还没开始", "尚未开始", "待处理")) return "pending";
        if (contains(text, "执行中的", "进行中的", "正在执行的")) return "in-progress";
        if (contains(text, "已完成的", "已经做完", "做完的")) return "completed";
        return null;
    }

    public static boolean harvestPlan(String text) {
        text = normalize(text);
        return contains(text, "采收", "成熟度复核") && contains(text, "安排", "生成", "制定", "创建", "草稿", "草案")
                && !contains(text, "如何安排", "怎么安排", "不要安排", "别安排", "不要创建", "不创建", "无需创建", "不要制定", "不要生成",
                    "不要抓拍", "不抓拍", "不要分析", "别分析", "不用分析", "不要扫描", "先不要", "只解释", "仅解释");
    }

    public static boolean adviceView(String text) {
        text = normalize(text);
        return !text.contains("月") && contains(text, "农事建议", "管理建议")
                && contains(text, "查看", "查询", "展示", "打开", "已有") && !contains(text, "生成", "制定", "取消", "创建");
    }

    public static boolean farmWide(String text) {
        return contains(normalize(text), "全农场", "整个农场", "整个果园", "全园", "所有地块", "全部地块", "所有农事任务", "所有任务", "全部任务");
    }

    public static boolean mentionsField(String text, String fieldId, String fieldName) {
        String code = fieldCode(text);
        if (code != null) return code.equalsIgnoreCase(fieldCode(fieldName)) || code.equalsIgnoreCase(fieldId);
        return fieldId != null && !fieldId.isBlank() && text.contains(fieldId) || fieldName != null && fieldName.length() > 1 && text.contains(fieldName);
    }

    public static CopilotOperationQuery resolve(String view, String text, LocalDate today) {
        text = normalize(text);
        var range = RANGE.matcher(text);
        if (range.find()) {
            LocalDate from = parseDate(range.group(1));
            LocalDate to = parseDate(range.group(2));
            if (from.isAfter(to)) throw new IllegalArgumentException("开始日期不能晚于结束日期。");
            return new CopilotOperationQuery(view, from, to);
        }
        var single = ONE_DATE.matcher(text);
        if (single.find()) {
            LocalDate day = parseDate(single.group(1));
            return new CopilotOperationQuery(view, day, day);
        }
        var rolling = ROLLING.matcher(text);
        if (rolling.find()) {
            String raw = rolling.group(1).replace('两', '二');
            int amount = raw.matches("\\d+") ? Integer.parseInt(raw) : "一二三四五六七八九十".indexOf(raw) + 1;
            LocalDate start = switch (rolling.group(2)) {
                case "天" -> today.minusDays(amount - 1L);
                case "周" -> today.minusWeeks(amount).plusDays(1);
                default -> today.minusMonths(amount).plusDays(1);
            };
            return new CopilotOperationQuery(view, start, today);
        }
        if (contains(text, "本周", "这周", "上周", "下周")) {
            int offset = text.contains("上周") ? -1 : text.contains("下周") ? 1 : 0;
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()).plusWeeks(offset);
            return new CopilotOperationQuery(view, monday, monday.plusDays(6));
        }
        if (contains(text, "今天", "昨日", "昨天", "明天")) {
            LocalDate day = today.plusDays(text.contains("明天") ? 1 : contains(text, "昨天", "昨日") ? -1 : 0);
            return new CopilotOperationQuery(view, day, day);
        }
        var namedMonth = NAMED_MONTH.matcher(text);
        if (namedMonth.find()) {
            YearMonth month = YearMonth.of(namedMonth.group(1) == null ? today.getYear() : Integer.parseInt(namedMonth.group(1)), Integer.parseInt(namedMonth.group(2)));
            return new CopilotOperationQuery(view, month.atDay(1), month.atEndOfMonth());
        }
        if (contains(text, "本月", "这个月", "上月", "下月") || "calendar".equals(view)) {
            YearMonth month = YearMonth.from(today).plusMonths(text.contains("上月") ? -1 : text.contains("下月") ? 1 : 0);
            return new CopilotOperationQuery(view, month.atDay(1), month.atEndOfMonth());
        }
        return new CopilotOperationQuery(view, null, null);
    }

    public static String taskId(String text) {
        var matcher = TASK_ID.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String fieldCode(String text) {
        var matcher = FIELD_CODE.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1).replace('－', '-') : null;
    }

    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("请提供有效日期，例如 2026-11-22。"); }
    }

    private static boolean contains(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static String normalize(String text) {
        return (text == null ? "" : text.trim()).replace("这礼拜", "本周").replace("这星期", "本周").replace("这个星期", "本周")
                .replace("下礼拜", "下周").replace("下星期", "下周").replace("上礼拜", "上周").replace("上星期", "上周");
    }
    public static boolean farmDirectory(String text) {
        text = normalize(text);
        return contains(text, "有哪些农场", "几个农场", "多少个农场", "农场列表", "农场清单", "列出农场", "列出所有农场", "所有可访问的农场", "list farms", "which farms")
                && !contains(text, "删除", "新建", "创建", "解绑", "地块", "摄像头", "成熟度");
    }
}

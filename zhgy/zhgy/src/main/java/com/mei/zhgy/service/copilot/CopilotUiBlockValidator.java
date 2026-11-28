package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the declared UI contract. Only known text, references and measured numbers reach the browser. */
public final class CopilotUiBlockValidator {
    private final AgriculturalEvidenceLedger evidence;
    private final CopilotSecurityGuard guard;
    private final CopilotStableContext context;
    private final Long actor;

    public CopilotUiBlockValidator(AgriculturalEvidenceLedger evidence, CopilotSecurityGuard guard, CopilotStableContext context, Long actor) {
        this.evidence = evidence;
        this.guard = guard;
        this.context = context;
        this.actor = actor;
    }

    public ToolReply validate(CopilotToolArguments args) {
        String component = args.text("componentType");
        String title = args.text("title");
        String summary = args.text("summary");
        if (!plain(title, 80) || summary != null && !plain(summary, 400)) return invalid("title/summary");
        var aliases = args.strings("evidenceIds");
        boolean clarification = "ClarificationForm".equals(component);
        if ((!clarification || !aliases.isEmpty()) && !evidence.supports(aliases)) return invalid("evidenceIds");
        JsonNode props = args.get("props");
        try {
            Map<String, Object> verified = switch (component) {
                case "ComparisonMatrixCard" -> comparison(props, aliases);
                case "ActionChecklistCard" -> checklist(props);
                case "EChartsAnalyticsCard" -> chart(props, aliases);
                case "ClarificationForm" -> rows(props, true);
                case "RiskAlertCard" -> rows(props, false);
                default -> throw new Invalid("componentType");
            };
            var card = new LinkedHashMap<String, Object>();
            card.put("componentType", component);
            card.put("title", title);
            card.put("blockKey", "declared:" + component + ":" + java.util.UUID.nameUUIDFromBytes(
                    title.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (summary != null) card.put("summary", summary);
            card.put("status", clarification ? "needs-input" : "ready");
            card.put("data", verified);
            card.put("evidenceIds", aliases);
            return new ToolReply(Map.of("status", "READY", "uiBlockEmitted", true), card, false);
        } catch (Invalid invalid) {
            return invalid(invalid.field);
        }
    }

    private Map<String, Object> comparison(JsonNode props, List<String> aliases) {
        var options = array(props, "options", 2, 5);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode option : options) {
            var entry = copyText(option, Set.of("label", "conditions", "cost", "speed", "advantages", "disadvantages"),
                    Set.of("label"), "options");
            entry.put("id", "option-" + (rows.size() + 1));
            JsonNode days = option.get("phiDays");
            if (days != null && !days.isNull()) {
                if (!days.isIntegralNumber() || days.asLong(-1) < 0 || days.asLong(-1) > 365
                        || !evidence.provesMeasurement(aliases, "登记安全间隔期 (天)", String.valueOf(entry.get("label")), days.doubleValue())) {
                    throw new Invalid("options.phiDays");
                }
                entry.put("phiDays", days.intValue());
            }
            if (option.hasNonNull("draft")) entry.put("draftTask", draft(option.get("draft")));
            Map<String, Object> values = new LinkedHashMap<>();
            for (String field : List.of("conditions", "cost", "speed", "advantages", "disadvantages")) {
                if (entry.containsKey(field)) values.put(field, entry.remove(field));
            }
            if (entry.remove("phiDays") instanceof Integer interval) values.put("phi", interval + " 天");
            entry.put("values", values);
            rows.add(entry);
        }
        var attributes = List.of(Map.of("id", "conditions", "label", "适用条件"), Map.of("id", "cost", "label", "成本"),
                Map.of("id", "speed", "label", "见效速度"), Map.of("id", "phi", "label", "采收安全间隔期"),
                Map.of("id", "advantages", "label", "优点"), Map.of("id", "disadvantages", "label", "注意事项"));
        return Map.of("comparisonMatrix", Map.of("options", rows, "rows", attributes));
    }

    private Map<String, Object> checklist(JsonNode props) {
        var items = array(props, "items", 1, 8);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : items) {
            var entry = copyText(item, Set.of("title", "detail", "prerequisites", "priority"),
                    Set.of("title", "detail", "priority"), "items");
            if (!Set.of("URGENT", "ROUTINE", "PREVENTIVE").contains(entry.get("priority"))) throw new Invalid("items.priority");
            entry.put("id", "step-" + (rows.size() + 1));
            if (item.hasNonNull("draft")) entry.put("draftTask", draft(item.get("draft")));
            else if (item.hasNonNull("fieldId")) entry.put("draftTask", draft(item));
            rows.add(entry);
        }
        return Map.of("actionChecklist", Map.of("items", rows));
    }

    private Map<String, Object> draft(JsonNode input) {
        var output = copyText(input, Set.of("title", "detail", "fieldId", "scheduledFor"), Set.of("title", "detail"), "draft");
        String supplied = output.get("fieldId") instanceof String id ? id : null;
        var allowed = guard.authorize(context, null, supplied, true, actor);
        if (allowed.fieldId() == null) throw new Invalid("draft.fieldId");
        if (output.get("scheduledFor") instanceof String date) {
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new Invalid("draft.scheduledFor");
            try { java.time.LocalDate.parse(date); }
            catch (java.time.format.DateTimeParseException invalid) { throw new Invalid("draft.scheduledFor"); }
        }
        output.put("fieldId", allowed.fieldId());
        return output;
    }

    private Map<String, Object> chart(JsonNode props, List<String> aliases) {
        var series = array(props, "series", 1, 6);
        String chartKind = props.path("chartKind").asText("line");
        if (!Set.of("line", "dual-axis", "stacked-bar", "thermal").contains(chartKind)) throw new Invalid("chartKind");
        List<Map<String, Object>> verified = new ArrayList<>();
        for (JsonNode line : series) {
            var label = line.path("label").asText("");
            if (!plain(label, 80)) throw new Invalid("series.label");
            var points = array(line, "points", 1, 30);
            List<Map<String, Object>> values = new ArrayList<>();
            for (JsonNode point : points) {
                String key = point.path("label").asText("");
                JsonNode number = point.path("value");
                if (!plain(key, 80) || !number.isNumber() || !Double.isFinite(number.doubleValue())
                        || !evidence.provesMeasurement(aliases, label, key, number.doubleValue())) throw new Invalid("series.points");
                values.add(Map.of("label", key, "value", number.doubleValue()));
            }
            verified.add(chartSeries(chartKind, label, values));
        }
        return Map.of("series", verified, "chartKind", chartKind);
    }

    static Map<String, Object> chartSeries(String kind, String label, List<Map<String, Object>> values) {
        var series = new LinkedHashMap<String, Object>();
        series.put("label", label);
        series.put("points", values);
        if ("dual-axis".equals(kind)) {
            boolean rain = label.contains("降水") || label.contains("降雨");
            series.put("type", rain ? "bar" : "line");
            series.put("axis", rain ? "right" : "left");
            series.put("unit", rain ? "mm" : "°C");
        } else if ("stacked-bar".equals(kind)) {
            series.put("type", "bar");
            series.put("stack", "成熟度");
            series.put("unit", "%");
        } else {
            series.put("type", "line");
            if ("thermal".equals(kind)) series.put("unit", "℃·d");
        }
        return series;
    }

    private Map<String, Object> rows(JsonNode props, boolean clarification) {
        String key = clarification ? "fields" : "items";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : array(props, key, 1, 8)) {
            var row = copyText(item, clarification ? Set.of("label") : Set.of("label", "value"),
                    clarification ? Set.of("label") : Set.of("label", "value"), key);
            if (clarification) { row.put("id", "input-" + (rows.size() + 1)); row.put("required", true); }
            rows.add(row);
        }
        return Map.of(key, rows);
    }

    private Map<String, Object> copyText(JsonNode input, Set<String> names, Set<String> required, String field) {
        if (!input.isObject()) throw new Invalid(field);
        Map<String, Object> output = new LinkedHashMap<>();
        for (String key : names) {
            if (!input.hasNonNull(key) && !required.contains(key)) continue;
            var node = input.path(key);
            int limit = switch (key) {
                case "title" -> 120;
                case "detail" -> 900;
                case "fieldId" -> 128;
                case "scheduledFor" -> 10;
                default -> 300;
            };
            if (!node.isTextual() || !plain(node.textValue(), limit)) throw new Invalid(field + "." + key);
            output.put(key, node.textValue().trim());
        }
        return output;
    }

    private static JsonNode array(JsonNode owner, String key, int min, int max) {
        JsonNode entries = owner.path(key);
        if (!entries.isArray() || entries.size() < min || entries.size() > max) throw new Invalid(key);
        return entries;
    }

    private static boolean plain(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit
                && value.indexOf('<') < 0 && value.indexOf('>') < 0 && value.indexOf('\0') < 0;
    }

    private static ToolReply invalid(String field) {
        return CopilotToolArguments.invalid(field, null, List.of(), "组件参数或证据不符合当前展示契约，请核对后重试");
    }

    private static final class Invalid extends RuntimeException {
        final String field;
        Invalid(String field) { this.field = field; }
    }
}

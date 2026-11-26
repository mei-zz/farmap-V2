package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ReadTool;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;
import com.mei.zhgy.service.field.FieldAccessException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Separates the model's input shape from the authorized, normalized Java arguments. */
public final class CopilotToolArguments {
    public record Parameter(String name, String description, String type, boolean required, int min, int max, List<String> enumValues) {}

    private final JsonNode values;

    private CopilotToolArguments(JsonNode values) { this.values = values; }

    public static Parameter text(String name, String description, int limit) {
        return new Parameter(name, description, "string", false, 1, limit, List.of());
    }

    public static Parameter requiredText(String name, String description, int limit) {
        return new Parameter(name, description, "string", true, 1, limit, List.of());
    }

    public static Parameter integer(String name, String description, int min, int max) {
        return new Parameter(name, description, "integer", false, min, max, List.of());
    }

    public static Parameter bool(String name, String description) {
        return new Parameter(name, description, "boolean", false, 0, 0, List.of());
    }

    public static Parameter enumeration(String name, String description, boolean required, String... values) {
        return new Parameter(name, description, "string", required, 1, 100, List.of(values));
    }

    public static Parameter strings(String name, String description, int maxItems) {
        return new Parameter(name, description, "array", false, 0, maxItems, List.of());
    }

    public static Parameter object(String name, String description, boolean required) {
        return new Parameter(name, description, "object", required, 0, 0, List.of());
    }

    public static Parameter objects(String name, String description, int maxItems) {
        return new Parameter(name, description, "object-array", true, 1, maxItems, List.of());
    }

    public static ReadTool tool(String name, String description, Function<CopilotToolArguments, ToolReply> handler,
                                Parameter... parameters) {
        return tool(name, description, false, handler, parameters);
    }

    public static ReadTool tool(String name, String description, boolean multimodal, Function<CopilotToolArguments, ToolReply> handler,
                                Parameter... parameters) {
        return build(name, description, multimodal, (args, ignored) -> handler.apply(args), false, parameters);
    }

    public static ReadTool progressTool(String name, String description, BiFunction<CopilotToolArguments, Consumer<ToolReply>, ToolReply> handler,
                                        Parameter... parameters) {
        return build(name, description, true, handler, true, parameters);
    }

    private static ReadTool build(String name, String description, boolean multimodal,
                                  BiFunction<CopilotToolArguments, Consumer<ToolReply>, ToolReply> handler, boolean reportsProgress,
                                  Parameter... parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            Map<String, Object> primary = switch (parameter.type()) {
                case "integer" -> Map.of("type", "integer");
                case "boolean" -> Map.of("type", "boolean");
                case "array" -> Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", parameter.max());
                case "object" -> Map.of("type", "object");
                case "object-array" -> Map.of("type", "array", "items", Map.of("type", "object"), "minItems", 1, "maxItems", parameter.max());
                default -> Map.of("type", "string");
            };
            String descriptionWithValues = parameter.description();
            if (!parameter.enumValues().isEmpty()) descriptionWithValues += "。可选值：" + String.join(" / ", parameter.enumValues());
            List<Map<String, Object>> types = switch (parameter.type()) {
                case "integer", "boolean" -> parameter.required()
                        ? List.of(primary, Map.of("type", "string"))
                        : List.of(primary, Map.of("type", "string"), Map.of("type", "null"));
                default -> parameter.required() ? List.of(primary) : List.of(primary, Map.of("type", "null"));
            };
            properties.put(parameter.name(), Map.of("anyOf", types, "description", descriptionWithValues));
        }
        List<String> required = List.of(parameters).stream().filter(Parameter::required).map(Parameter::name).toList();
        Map<String, Object> schema = Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
        com.mei.zhgy.service.agent.pi.PiAgentRuntime.ProgressExecutor execution = (raw, progress) -> {
            if (raw == null || !raw.isObject()) return invalid(null, null, List.of(), "请提供 JSON 对象参数");
            var names = raw.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                if (!properties.containsKey(field)) return invalid(field, null, List.copyOf(properties.keySet()), "请删除未声明的参数");
            }
            ObjectNode normalized = JsonNodeFactory.instance.objectNode();
            for (Parameter parameter : parameters) {
                JsonNode value = raw.get(parameter.name());
                if (value == null || value.isNull() || value.isTextual() && value.asText().isBlank()) {
                    if (parameter.required()) return invalid(parameter.name(), null, parameter.enumValues(), "请填写必填参数");
                    continue;
                }
                JsonNode parsed = normalize(value, parameter);
                if (parsed == null) {
                    String hint = switch (parameter.type()) {
                        case "integer" -> "请提供 " + parameter.min() + " 至 " + parameter.max() + " 的整数";
                        case "boolean" -> "请提供 true 或 false";
                        default -> parameter.enumValues().isEmpty() ? "请提供不超过 " + parameter.max() + " 个字符的文本" : "请改用允许的标准值重试";
                    };
                    return invalid(parameter.name(), value, parameter.enumValues(), hint);
                }
                normalized.set(parameter.name(), parsed);
            }
            try {
                return handler.apply(new CopilotToolArguments(normalized), progress);
            } catch (FieldAccessException denied) {
                return ToolReply.error("SCOPE_UNAVAILABLE");
            } catch (InvalidArgument invalid) {
                return invalid(invalid.field, normalized.get(invalid.field), List.of(), invalid.getMessage());
            } catch (IllegalArgumentException invalid) {
                return invalid(null, null, List.of(), "请检查参数类型及范围后重试");
            }
        };
        return new ReadTool(name, description, schema, raw -> execution.execute(raw, ignored -> {}),
                multimodal, reportsProgress ? execution : null);
    }

    private static JsonNode normalize(JsonNode value, Parameter parameter) {
        if ("object".equals(parameter.type())) return value.isObject() ? value : null;
        if ("object-array".equals(parameter.type())) {
            if (!value.isArray() || value.isEmpty() || value.size() > parameter.max()) return null;
            for (JsonNode entry : value) if (!entry.isObject()) return null;
            return value;
        }
        if ("integer".equals(parameter.type())) {
            int number;
            try {
                if (value.isIntegralNumber() && value.canConvertToInt()) number = value.intValue();
                else if (value.isTextual() && value.asText().trim().matches("-?\\d{1,10}")) number = Integer.parseInt(value.asText().trim());
                else return null;
            } catch (NumberFormatException invalid) { return null; }
            return number >= parameter.min() && number <= parameter.max() ? JsonNodeFactory.instance.numberNode(number) : null;
        }
        if ("boolean".equals(parameter.type())) {
            if (value.isBoolean()) return value;
            String raw = value.isTextual() ? value.asText().trim() : "";
            if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) return JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(raw));
            return null;
        }
        if ("array".equals(parameter.type())) {
            if (!value.isArray() || value.size() > parameter.max()) return null;
            var items = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) {
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 2048) return null;
                items.add(item.asText().trim());
            }
            return items;
        }
        if (!value.isTextual()) return null;
        String raw = value.asText().trim();
        if (raw.length() > parameter.max()) return null;
        if (parameter.enumValues().isEmpty()) return JsonNodeFactory.instance.textNode(raw);
        return parameter.enumValues().stream().filter(raw::equalsIgnoreCase)
                .findFirst().map(JsonNodeFactory.instance::textNode).orElse(null);
    }

    public static ToolReply invalid(String field, JsonNode value, List<String> allowedValues, String hint) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", "INVALID_ARGUMENTS");
        if (field != null) detail.put("field", bounded(field));
        if (value != null && value.isValueNode() && !value.isNull()) detail.put("received", bounded(value.asText()));
        if (!allowedValues.isEmpty()) detail.put("allowedValues", allowedValues);
        detail.put("hint", hint);
        return new ToolReply(detail, null, true);
    }

    private static String bounded(String input) { return input.substring(0, Math.min(128, input.length())); }

    public JsonNode get(String name) { return values.get(name); }
    public String text(String name) { return values.has(name) ? values.get(name).asText() : null; }
    public Integer integer(String name) { return values.has(name) ? values.get(name).intValue() : null; }
    public boolean bool(String name, boolean defaultValue) { return values.has(name) ? values.get(name).booleanValue() : defaultValue; }
    public List<String> strings(String name) {
        if (!values.has(name)) return List.of();
        return java.util.stream.StreamSupport.stream(values.get(name).spliterator(), false).map(JsonNode::asText).toList();
    }

    public LocalDate date(String name) {
        String value = text(name);
        if (value == null) return null;
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try { return LocalDate.parse(value); }
            catch (DateTimeParseException ignored) { /* same safe correction for syntax and date ranges */ }
        }
        throw new InvalidArgument(name, "请提供有效的 YYYY-MM-DD 日期");
    }

    private static final class InvalidArgument extends IllegalArgumentException {
        final String field;
        InvalidArgument(String field, String message) { super(message); this.field = field; }
    }
}

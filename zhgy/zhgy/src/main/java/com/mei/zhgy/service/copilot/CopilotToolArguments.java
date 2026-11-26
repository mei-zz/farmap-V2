package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ReadTool;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;
import com.mei.zhgy.service.field.FieldAccessException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Model tool arguments are untrusted even when the provider validates the schema. */
public final class CopilotToolArguments {
    public record Parameter(String name, String description, String type, boolean required, int min, int max) {}

    private final JsonNode values;

    private CopilotToolArguments(JsonNode values) { this.values = values; }

    public static Parameter text(String name, String description, int limit) {
        return new Parameter(name, description, "string", false, 1, limit);
    }

    public static Parameter requiredText(String name, String description, int limit) {
        return new Parameter(name, description, "string", true, 1, limit);
    }

    public static Parameter integer(String name, String description, int min, int max) {
        return new Parameter(name, description, "integer", false, min, max);
    }

    public static ReadTool tool(String name, String description, Function<CopilotToolArguments, ToolReply> handler,
                                Parameter... parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            boolean textual = "string".equals(parameter.type());
            properties.put(parameter.name(), Map.of("type", parameter.type(), "description", parameter.description(),
                    textual ? "minLength" : "minimum", parameter.min(), textual ? "maxLength" : "maximum", parameter.max()));
        }
        List<String> required = List.of(parameters).stream().filter(Parameter::required).map(Parameter::name).toList();
        Map<String, Object> schema = Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
        return new ReadTool(name, description, schema, values -> {
            if (!values.isObject()) return ToolReply.error("INVALID_ARGUMENTS");
            var names = values.fieldNames();
            while (names.hasNext()) if (!properties.containsKey(names.next())) return ToolReply.error("INVALID_ARGUMENTS");
            for (Parameter parameter : parameters) {
                JsonNode value = values.get(parameter.name());
                if (value == null && !parameter.required()) continue;
                if (!valid(value, parameter)) return ToolReply.error("INVALID_ARGUMENTS");
            }
            try {
                return handler.apply(new CopilotToolArguments(values));
            } catch (FieldAccessException denied) {
                return ToolReply.error("SCOPE_UNAVAILABLE");
            } catch (IllegalArgumentException invalid) {
                return ToolReply.error("INVALID_ARGUMENTS");
            }
        });
    }

    private static boolean valid(JsonNode value, Parameter parameter) {
        if (value == null) return false;
        if ("string".equals(parameter.type())) {
            return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= parameter.max();
        }
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= parameter.min() && value.intValue() <= parameter.max();
    }

    public String text(String name) { return values.has(name) ? values.get(name).asText().trim() : null; }
    public Integer integer(String name) { return values.has(name) ? values.get(name).intValue() : null; }
    public LocalDate date(String name) {
        String value = text(name);
        if (value == null) return null;
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("ISO date required");
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid date", invalid); }
    }
}

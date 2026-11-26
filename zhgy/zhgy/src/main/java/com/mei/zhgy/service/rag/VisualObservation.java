package com.mei.zhgy.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Bounded visual facts proposed by the image model. Similarity is collected separately. */
public record VisualObservation(String subject, List<String> visibleSymptoms, Boolean imageClear,
                                String observedPhenologyStage, List<String> missingViews, String uncertainty) {
    private static final ObjectMapper JSON = new ObjectMapper();

    static VisualObservation parse(String content) {
        if (content == null || content.length() > 12_000) return null;
        String payload = content.trim();
        if (payload.startsWith("```") && payload.endsWith("```")) {
            int start = payload.indexOf('\n');
            if (start < 0) return null;
            payload = payload.substring(start + 1, payload.length() - 3);
        }
        try {
            JsonNode input = JSON.readTree(payload);
            if (input == null || !input.isObject() || !input.path("visibleSymptoms").isArray()
                    || !input.path("imageClear").isBoolean()) return null;
            return new VisualObservation(text(input.path("subject"), 120), strings(input.path("visibleSymptoms")),
                    input.path("imageClear").booleanValue(), text(input.path("observedPhenologyStage"), 64),
                    strings(input.path("missingViews")), text(input.path("uncertainty"), 300));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return null;
        }
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) for (JsonNode value : values) {
            String label = text(value, 180);
            if (label != null && !result.contains(label)) result.add(label);
            if (result.size() == 10) break;
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode value, int limit) {
        if (!value.isTextual() || value.textValue().isBlank()) return null;
        String text = value.textValue().trim();
        return text.substring(0, Math.min(limit, text.length()));
    }
}

package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Additive SSE metadata for the web Copilot. The text stream remains the
 * source of the answer body; this object only describes safe UI blocks and
 * verified evidence that are already available on the server.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotResponseMetadata {
    private int schemaVersion = 1;
    private String messageId;
    private CopilotIntent intent;
    private CopilotResolutionStatus resolutionStatus;
    private String normalizedQuery;
    private CopilotMediaNeed mediaNeed;
    private CopilotResponseMode responseType;
    private String content = "";
    private List<Card> cards = List.of();
    private List<SourceReference> sources = List.of();
    private List<SuggestedQuestion> suggestions = List.of();
    private List<AgentAction> actions = List.of();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Card {
        private String id;
        private String type;
        private String title;
        private String content;
        private List<String> sourceIds = List.of();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SourceReference {
        private String id;
        private String title;
        private String quote;
        private String sourceId;
        private String documentId;
        private String versionId;
        private String chunkId;
        private Double score;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SuggestedQuestion {
        private String id;
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentAction {
        private String id;
        private String type;
        private String label;
        private boolean enabled;
    }
}

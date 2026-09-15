package com.mei.zhgy.config;

import com.mei.zhgy.service.ai.AiTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Externalized policy for the plain-text answer model pool. */
@Component
@ConfigurationProperties(prefix = "farmap.ai.router.answer-pool")
public class AnswerPoolProperties {
    private static final Logger log = LoggerFactory.getLogger(AnswerPoolProperties.class);
    private static final List<String> DEFAULT_TASK_TYPES = List.of(
            AiTaskType.COPILOT.name(),
            AiTaskType.FIELD_SUMMARY.name(),
            AiTaskType.EVIDENCE_SYNTHESIS.name(),
            AiTaskType.GROUNDED_GENERATION.name(),
            AiTaskType.FUTURE_AGENT_TOOL_DECISION.name());

    private boolean enabled = true;
    private String selectionStrategy = "random";
    private List<String> applyTaskTypes = new ArrayList<>(DEFAULT_TASK_TYPES);
    private int samePoolFallbackN = 3;
    private boolean skipUnavailable = true;
    private List<String> models = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSelectionStrategy() {
        return selectionStrategy;
    }

    public void setSelectionStrategy(String selectionStrategy) {
        String normalized = selectionStrategy == null ? "" : selectionStrategy.trim().toLowerCase();
        this.selectionStrategy = "round_robin".equals(normalized) ? normalized : "random";
    }

    public List<String> getApplyTaskTypes() {
        return applyTaskTypes;
    }

    public void setApplyTaskTypes(List<String> applyTaskTypes) {
        if (applyTaskTypes == null) {
            this.applyTaskTypes = new ArrayList<>();
            return;
        }
        List<String> valid = new ArrayList<>();
        for (String value : applyTaskTypes) {
            if (value == null || value.isBlank()) continue;
            String normalized = value.trim().toUpperCase();
            try {
                AiTaskType.valueOf(normalized);
                if (!valid.contains(normalized)) valid.add(normalized);
            } catch (IllegalArgumentException ignored) {
                log.warn("Ignoring unknown answer-pool task type: {}", normalized);
            }
        }
        this.applyTaskTypes = valid;
    }

    public int getSamePoolFallbackN() {
        return samePoolFallbackN;
    }

    public void setSamePoolFallbackN(int samePoolFallbackN) {
        this.samePoolFallbackN = Math.max(0, Math.min(8, samePoolFallbackN));
    }

    public boolean isSkipUnavailable() {
        return skipUnavailable;
    }

    public void setSkipUnavailable(boolean skipUnavailable) {
        this.skipUnavailable = skipUnavailable;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        if (models == null) {
            this.models = new ArrayList<>();
            return;
        }
        this.models = models.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean appliesTo(AiTaskType taskType) {
        return taskType != null && applyTaskTypes.contains(taskType.name());
    }
}

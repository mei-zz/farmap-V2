package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FieldContextTool implements AgentTool {
    private final FieldFactsService fieldFactsService;
    public FieldContextTool() { this(null); }
    @Autowired
    public FieldContextTool(@Autowired(required = false) FieldFactsService fieldFactsService) { this.fieldFactsService = fieldFactsService; }
    @Override public String name() { return "field-context"; }
    @Override public String description() { return "读取结构化农场、地块、作物与物候上下文"; }
    @Override public boolean readOnly() { return true; }
    @Override public ToolResult execute(AgentToolRequest request) {
        AgentContext c = request == null ? null : request.getContext();
        Map<String, Object> out = new LinkedHashMap<>();
        if (c != null) { out.put("farmId", c.getFarmId()); out.put("farmName", c.getFarmName()); out.put("fieldId", c.getFieldId()); out.put("fieldName", c.getFieldName()); out.put("crop", c.getCrop()); out.put("cropLabel", c.getCropLabel()); out.put("variety", c.getVariety()); out.put("phenologyStage", c.getPhenologyStage()); out.put("phenologyStageLabel", c.getPhenologyStageLabel()); }
        FieldFacts facts = resolveFacts(request, c);
        if (facts != null) {
            out.put("fieldFactsStatus", facts.status()); out.put("serverDate", facts.currentDate()); out.put("crop", facts.cropCode()); out.put("cropLabel", facts.cropLabel());
            out.put("registeredPhenologyStage", facts.registeredGrowthStage()); out.put("registeredPhenologyStageLabel", facts.registeredGrowthStageLabel());
            out.put("dateDerivedPhenologyStage", facts.dateDerivedGrowthStage()); out.put("dateDerivedPhenologyStageLabel", facts.dateDerivedGrowthStageLabel());
            out.put("phenologyStage", facts.currentGrowthStage()); out.put("phenologyStageLabel", facts.currentGrowthStageLabel());
            out.put("previousPhenologyStage", facts.previousGrowthStage()); out.put("previousPhenologyStageLabel", facts.previousGrowthStageLabel());
            out.put("nextPhenologyStage", facts.nextGrowthStage()); out.put("nextPhenologyStageLabel", facts.nextGrowthStageLabel());
            out.put("daysUntilNextPhenologyStage", facts.daysUntilNext()); out.put("phenologyCycleType", facts.phenologyCycleType());
            out.put("phenologyStageMismatch", facts.stageMismatch()); out.put("phenologyCycleEnded", facts.cycleEnded());
            out.put("orderedPhenologyStages", facts.orderedGrowthStages()); out.put("factSources", facts.sources());
        } else if (c != null) {
            out.put("fieldFactsStatus", c.getFieldFactsStatus()); out.put("serverDate", c.getFieldFactsDate());
            out.put("previousPhenologyStage", c.getPreviousPhenologyStage()); out.put("previousPhenologyStageLabel", c.getPreviousPhenologyStageLabel());
            out.put("nextPhenologyStage", c.getNextPhenologyStage()); out.put("nextPhenologyStageLabel", c.getNextPhenologyStageLabel());
            out.put("daysUntilNextPhenologyStage", c.getDaysUntilNextPhenologyStage()); out.put("phenologyStageMismatch", c.isPhenologyStageMismatch());
            out.put("phenologyCycleEnded", c.isPhenologyCycleEnded()); out.put("orderedPhenologyStages", c.getOrderedPhenologyStages()); out.put("factSources", c.getFactSources());
        }
        return ToolResult.builder().output(out).metadata(Map.of("sourceMode", source(c, "field", "LOCAL"), "fieldFactsStatus", String.valueOf(out.getOrDefault("fieldFactsStatus", "UNAVAILABLE")))).build();
    }
    private FieldFacts resolveFacts(AgentToolRequest request, AgentContext context) {
        if (fieldFactsService == null || request == null || request.getOwnerUserId() == null || context == null
                || context.getFieldId() == null || context.getFieldId().isBlank()) return null;
        return fieldFactsService.forField(context.getFieldId(), request.getOwnerUserId());
    }
    private Object source(AgentContext c, String key, String fallback) { if (c == null || c.getSourceModes() == null) return fallback; Object value = c.getSourceModes().get(key); return value == null ? fallback : value; }
}

package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.agent.pi.PiAgentRuntime;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binds only authorized tool results and measurements to this turn's evidence ledger. */
final class CopilotPiToolEvidence {
    private CopilotPiToolEvidence() {}

    static PiAgentRuntime.ReadTool bind(PiAgentRuntime.ReadTool tool, AgriculturalEvidenceLedger ledger, Integer farmId) {
        var progressExecutor = tool.progressExecutor();
        return new PiAgentRuntime.ReadTool(tool.name(), tool.description(), tool.parameters(),
                args -> attachCitations(citeToolResult(tool.name(), tool.execute().apply(args), ledger, farmId), ledger),
                tool.multimodal(), progressExecutor == null ? null :
                        (args, progress) -> attachCitations(citeToolResult(tool.name(), progressExecutor.execute(args, progress), ledger, farmId), ledger));
    }

    private static ToolReply attachCitations(ToolReply reply, AgriculturalEvidenceLedger ledger) {
        if (reply.isError() || !reply.citations().isEmpty()) return reply;
        var references = new ArrayList<PiAgentRuntime.ToolCitation>();
        if (reply.data() instanceof CopilotKnowledgeBundle hits) {
            for (var hit : hits.items()) if (hit.get("evidenceId") instanceof String alias) {
                var entry = ledger.lookup(alias);
                if (entry == null) continue;
                references.add(new PiAgentRuntime.ToolCitation(Integer.parseInt(alias.substring(1)), alias, "knowledge",
                        entry.title(), entry.source(), mapText(hit, "growthStage"), mapText(hit, "content"), null,
                        mapText(hit, "documentName"), hit.get("score") instanceof Number number ? number.doubleValue() : null,
                        mapText(hit, "expertStatus")));
            }
        } else if (reply.data() instanceof Map<?, ?> data && data.get("evidenceIds") instanceof List<?> aliases) {
            for (Object alias : aliases) {
                var entry = ledger.lookup(String.valueOf(alias));
                if (entry == null) continue;
                String modality = switch (entry.modality()) {
                    case "read_current_weather" -> "weather";
                    case "lookup_reference_atlas" -> "atlas";
                    case "search_historical_cases" -> "historical_case";
                    case "inspect_camera_maturity", "analyze_uploaded_images" -> "camera";
                    default -> "field";
                };
                references.add(new PiAgentRuntime.ToolCitation(Integer.parseInt(entry.alias().substring(1)), entry.alias(), modality,
                        entry.title(), entry.source(), null, entry.summary(), null, entry.reference(), null, null));
            }
        }
        return references.isEmpty() ? reply : reply.withCitations(references);
    }

    private static ToolReply citeToolResult(String toolName, ToolReply reply, AgriculturalEvidenceLedger ledger, Integer activeFarm) {
        if (reply.isError()) return reply;
        var data = new LinkedHashMap<String, Object>();
        if (reply.data() instanceof Map<?, ?> values) values.forEach((key, value) -> {
            if (key instanceof String field) data.put(field, value);
        });
        else if (reply.data() instanceof com.mei.zhgy.vo.CurrentWeatherResponse weather && "READY".equals(weather.status())) {
            data.put("status", "READY"); data.put("weather", weather); data.put("farmId", weather.farmId());
        } else return reply;
        Integer farmId = data.get("farmId") instanceof Number value ? value.intValue() : activeFarm;
        String fieldId = data.get("fieldId") instanceof String value ? value : null;
        String status = String.valueOf(data.get("status"));
        AgriculturalEvidenceLedger.Entry entry = null;
        if ("READY".equals(status) && "search_historical_cases".equals(toolName) && data.get("items") instanceof List<?> cases) {
            List<String> aliases = new ArrayList<>();
            List<Map<String, Object>> entries = new ArrayList<>();
            var citations = new ArrayList<PiAgentRuntime.ToolCitation>();
            for (Object item : cases) if (item instanceof Map<?, ?> candidate && candidate.get("caseId") != null) {
                var copy = new LinkedHashMap<String, Object>();
                candidate.forEach((key, value) -> { if (key instanceof String name) copy.put(name, value); });
                String summary = copy.get("summary") instanceof String text ? text : safe(String.valueOf(copy.get("diseaseType")));
                var evidence = ledger.add(String.valueOf(copy.get("caseId")), "历史病例", summary,
                        "FarMap 历史病例", toolName, farmId, fieldId);
                if (evidence != null) {
                    copy.put("evidenceId", evidence.alias());
                    aliases.add(evidence.alias());
                    Double score = copy.get("similarityScore") instanceof Number value ? value.doubleValue() / 100D : null;
                    citations.add(toolCitation(evidence, "historical_case", mapText(copy, "growthStage"), null, null,
                            score, mapText(copy, "expertStatus")));
                }
                entries.add(copy);
            }
            data.put("items", entries);
            data.put("evidenceIds", aliases);
            return new ToolReply(data, reply.uiBlock(), citations, false);
        }
        switch (toolName) {
            case "read_field_context" -> {
                if ("READY".equals(data.get("fieldFactsStatus")) && fieldId != null) {
                    var summary = new java.util.StringJoiner(" · ");
                    addDraftFact(summary, data, "fieldName", "地块");
                    addDraftFact(summary, data, "currentGrowthStageLabel", "当前物候");
                    addDraftFact(summary, data, "registeredGrowthStageLabel", "登记物候");
                    addDraftFact(summary, data, "dateDerivedGrowthStageLabel", "日期推算");
                    addDraftFact(summary, data, "phenologyBasis", "推算依据");
                    addDraftFact(summary, data, "accumulatedTemperature", "观测积温");
                    entry = ledger.add(fieldId, "地块登记资料", summary.toString(), "FarMap 地块档案", toolName, farmId, fieldId);
                }
            }
            case "query_operations_tasks" -> {
                if ("READY".equals(status)) entry = ledger.add(String.valueOf(data.get("criteria")), "任务查询结果",
                        "授权范围内的任务与排期记录", "FarMap 作业台账", toolName, farmId, fieldId);
            }
            case "read_current_weather" -> {
                String observation = String.valueOf(data.getOrDefault("date", "current"));
                if (data.get("sprayAssessment") instanceof Map<?, ?> assessment && assessment.get("verifiedProductId") instanceof String product) {
                    observation += "/" + product;
                }
                if ("READY".equals(status)) entry = ledger.add(observation,
                        "农场天气", draftWeatherSummary(data), "FarMap 气象服务", toolName, farmId, fieldId);
            }
            case "inspect_camera_maturity", "analyze_uploaded_images" -> {
                if ("COMPLETED".equals(status) && data.get("runId") instanceof String runId) {
                    entry = ledger.add(runId, "地块视觉分析", String.valueOf(data.getOrDefault("summary", "")),
                            "FarMap 视觉报告", toolName, farmId, fieldId);
                }
                if ("READY".equals(status) && data.get("observations") instanceof List<?> observations) {
                    List<String> aliases = new ArrayList<>();
                    var citations = new ArrayList<PiAgentRuntime.ToolCitation>();
                    for (Object value : observations) if (value instanceof com.mei.zhgy.vo.DiagnosisEvidenceVO observation) {
                        var observed = ledger.add(observation.getId(), observation.getTitle(), observation.getSummary(),
                                observation.getSource() == null ? "FarMap 视觉观测" : observation.getSource().getName(),
                                toolName, farmId, fieldId);
                        if (observed != null) {
                            aliases.add(observed.alias());
                            var source = observation.getSource();
                            var preview = observation.getPreview();
                            var metadata = observation.getMetadata();
                            String stage = metadata != null && metadata.get("visualObservation") instanceof com.mei.zhgy.service.rag.VisualObservation fact
                                    ? fact.observedPhenologyStage() : null;
                            Double score = metadata != null && metadata.get("similarity") instanceof Number number ? number.doubleValue() : null;
                            citations.add(toolCitation(observed, "camera", stage, preview == null ? null : preview.getImageUrl(),
                                    source == null ? null : source.getDocument(), score, null));
                        }
                    }
                    if (!aliases.isEmpty()) data.put("evidenceIds", aliases);
                    return new ToolReply(data, reply.uiBlock(), citations, false);
                }
            }
            case "lookup_reference_atlas" -> {
                if ("READY".equals(status) && data.get("items") instanceof List<?> figures) {
                    List<String> aliases = new ArrayList<>();
                    var citations = new ArrayList<PiAgentRuntime.ToolCitation>();
                    for (Object value : figures) if (value instanceof com.mei.zhgy.service.reference.ReferenceImage figure) {
                        var reference = ledger.add(figure.refId(), figure.label(), "图鉴参照图像", "FarMap 图鉴", toolName, null, null);
                        if (reference != null) {
                            aliases.add(reference.alias());
                            citations.add(toolCitation(reference, "atlas", figure.stageCode(), figure.imageUrl(), null, null, null));
                        }
                    }
                    data.put("evidenceIds", aliases);
                    return new ToolReply(data, reply.uiBlock(), citations, false);
                }
            }
            case "read_previous_analysis" -> {
                if ("READY".equals(status)) entry = ledger.add(String.valueOf(data.keySet()), "历史分析记录",
                        "用户已授权的业务记录", "FarMap 业务台账", toolName, farmId, fieldId);
            }
            default -> { }
        }
        if (entry == null) return reply;
        if ("read_field_context".equals(toolName) && data.get("area") instanceof Number area
                && data.get("fieldName") instanceof String fieldName) {
            ledger.addMeasurement(entry, "地块登记面积", fieldName, area);
        }
        if ("read_field_context".equals(toolName) && data.get("accumulatedTemperatureHistory") instanceof List<?> observations) {
            for (var observation : observations) if (observation instanceof Map<?, ?> point
                    && point.get("label") instanceof String label && point.get("value") instanceof Number value) {
                ledger.addMeasurement(entry, "农场累计积温", label, value);
                if (data.get("thermalTarget") instanceof Number threshold) {
                    ledger.addMeasurement(entry, "下一物候阶段校准阈值", label, threshold);
                }
            }
        }
        if (data.get("maturitySeries") instanceof List<?> lines) {
            for (Object object : lines) if (object instanceof Map<?, ?> line && line.get("label") instanceof String seriesLabel
                    && line.get("points") instanceof List<?> points) {
                for (Object point : points) if (point instanceof Map<?, ?> item && item.get("label") instanceof String label
                        && item.get("value") instanceof Number number) ledger.addMeasurement(entry, seriesLabel, label, number);
            }
        }
        if ("read_current_weather".equals(toolName)) {
            if (data.get("weather") instanceof com.mei.zhgy.vo.CurrentWeatherResponse weather && weather.forecast() != null) {
                for (var day : weather.forecast()) recordForecast(ledger, entry, day);
            } else if (data.get("forecast") instanceof com.mei.zhgy.vo.WeatherForecastDay day) {
                recordForecast(ledger, entry, day);
            }
            if (data.get("sprayAssessment") instanceof Map<?, ?> assessment
                    && assessment.get("operationDecision") == PlumAgronomyRuleEngine.Decision.ELIGIBLE
                    && Boolean.TRUE.equals(assessment.get("useRestrictionsVerified"))
                    && assessment.get("verifiedProductId") instanceof String productId
                    && assessment.get("requestedDate") instanceof String plannedDate) {
                ledger.verifySpray(entry, productId, plannedDate);
            }
        }
        data.put("evidenceIds", List.of(entry.alias()));
        var chartSeries = ledger.series(entry);
        if (!chartSeries.isEmpty()) data.put("chartSeries", chartSeries);
        return new ToolReply(data, reply.uiBlock(), false);
    }

    private static PiAgentRuntime.ToolCitation toolCitation(AgriculturalEvidenceLedger.Entry entry, String modality, String stage,
                                                     String image, String document, Double score, String expertStatus) {
        return new PiAgentRuntime.ToolCitation(Integer.parseInt(entry.alias().substring(1)), entry.alias(), modality,
                entry.title(), entry.source(), stage, entry.summary(), image, document, score, expertStatus);
    }

    private static void addDraftFact(java.util.StringJoiner summary, Map<?, ?> data, String key, String label) {
        Object value = data.get(key);
        if (value != null && !value.toString().isBlank()) summary.add(label + " " + value);
    }

    private static String draftWeatherSummary(Map<String, Object> data) {
        var summary = new java.util.StringJoiner(" · ");
        if (data.get("weather") instanceof com.mei.zhgy.vo.CurrentWeatherResponse weather) {
            if (weather.observedAt() != null) summary.add(weather.observedAt());
            if (weather.condition() != null) summary.add(weather.condition());
            if (weather.rainfallForecast24h() != null) summary.add("未来24小时预估降水 " + weather.rainfallForecast24h() + " mm");
            if (weather.rainfallProbability24h() != null) summary.add("未来24小时降水概率 " + weather.rainfallProbability24h() + "%");
        }
        if (data.get("forecast") instanceof com.mei.zhgy.vo.WeatherForecastDay day) {
            if (day.date() != null) summary.add(day.date());
            if (day.condition() != null) summary.add(day.condition());
            if (day.precipitationMm() != null) summary.add("预计降水 " + day.precipitationMm() + " mm");
            if (day.precipitationProbability() != null) summary.add("降水概率 " + day.precipitationProbability() + "%");
        }
        if (data.get("sprayAssessment") instanceof Map<?, ?> assessment) {
            addDraftFact(summary, assessment, "operationDecision", "施药窗口校验");
            addDraftFact(summary, assessment, "phiDays", "登记安全间隔天数");
        }
        return summary.length() == 0 ? "气象服务观测或预报" : summary.toString();
    }

    private static void recordForecast(AgriculturalEvidenceLedger ledger, AgriculturalEvidenceLedger.Entry entry,
                                com.mei.zhgy.vo.WeatherForecastDay day) {
        if (day == null) return;
        ledger.addMeasurement(entry, "降水量 (mm)", day.date(), day.precipitationMm());
        ledger.addMeasurement(entry, "降水概率 (%)", day.date(), day.precipitationProbability());
        Double maximum = validNumber(day.temperatureMax()), minimum = validNumber(day.temperatureMin());
        ledger.addMeasurement(entry, "最高气温 (°C)", day.date(), maximum);
        ledger.addMeasurement(entry, "最低气温 (°C)", day.date(), minimum);
    }

    static Double validNumber(String value) {
        if (value == null) return null;
        try { return Double.parseDouble(value); } catch (NumberFormatException invalid) { return null; }
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String mapText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}

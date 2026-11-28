package com.mei.zhgy.service.copilot;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agent.AgentRun;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.copilot.CopilotSecurityGuard.ToolScope;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ReadTool;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.historical.HistoricalCaseSearchService;
import com.mei.zhgy.service.knowledge.AgriculturalStructuredKnowledgeService;
import com.mei.zhgy.service.knowledge.PlumDefenseKnowledgeService;
import com.mei.zhgy.service.maturity.FarmMaturityService;
import com.mei.zhgy.service.operations.OperationsTaskViewService;
import com.mei.zhgy.service.rag.VisualEvidenceRetriever;
import com.mei.zhgy.service.reference.ReferenceImageAttacher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.mei.zhgy.service.copilot.CopilotToolArguments.*;

/** Registers domain service adapters for an authenticated tool scope. */
@Service
public class AgriculturalToolRegistry {
    private final CopilotSecurityGuard guard;
    private final FarmFieldService fields;
    private final OperationsTaskViewService tasks;
    private final ReferenceImageAttacher atlas;
    private final HistoricalCaseSearchService history;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mei.zhgy.service.historical.ReviewedCaseTextSearch reviewedCases;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgriculturalStructuredKnowledgeService structuredAgronomy;
    private final VisualEvidenceRetriever visual;
    private final FarmMaturityService maturity;
    private final CropResolver crops;
    private final PhenologyService phenology;

    public AgriculturalToolRegistry(FarmFieldService fields, OperationsTaskViewService tasks, ReferenceImageAttacher atlas,
                                    HistoricalCaseSearchService history, VisualEvidenceRetriever visual,
                                    FarmMaturityService maturity, CropResolver crops, PhenologyService phenology) {
        this.fields = fields;
        this.guard = new CopilotSecurityGuard(fields);
        this.tasks = tasks;
        this.atlas = atlas;
        this.history = history;
        this.visual = visual;
        this.maturity = maturity;
        this.crops = crops;
        this.phenology = phenology;
    }

    record CoreServices(FarmFieldService fields, com.mei.zhgy.service.CurrentWeatherService weather,
                        AgriculturalWeatherAdvisor weatherAdvisor, CopilotLongTermMemoryService memories,
                        FarmMaturityService maturity, com.mei.zhgy.service.agent.AgentRuntime workflows) {}

    record CoreReads(java.util.function.Function<CopilotSecurityGuard.ToolScope, Map<String, Object>> fieldFacts,
                     java.util.function.BiFunction<CopilotSecurityGuard.ToolScope, CopilotToolArguments, ToolReply> knowledge,
                     java.util.function.BiFunction<Integer, Integer, List<Map<String, Object>>> monthly,
                     java.util.function.Function<CopilotStableContext, Map<String, Object>> orchardProfile,
                     java.util.function.Function<CopilotToolArguments, ToolReply> previousAnalysis,
                     String currentReport, AgriculturalEvidenceLedger evidence) {}

    static List<ReadTool> coreTools(CopilotSecurityGuard guard, CopilotStableContext active, Long actor,
                                    boolean harness, boolean drafting, boolean canRender, CoreServices services,
                                    CoreReads reads) {
        var farmFieldService = services.fields();
        var currentWeatherService = services.weather();
        var agriculturalWeather = services.weatherAdvisor();
        var memories = services.memories();
        var maturityService = services.maturity();
        var agentRuntime = services.workflows();
        var farm = integer("farmId", "农场编号，默认选中农场", 1, Integer.MAX_VALUE);
        var field = text("fieldId", "授权农场内的地块 ID 或完整名称，默认选中地块", 128);
        var runId = text("runId", "所查询的业务运行编号，默认当前报告或工作流", 128);
        List<ReadTool> tools = new ArrayList<>();
        tools.add(tool("read_field_context", "实时读取授权地块的事实、作物与物候。可指定同一农场内的其他地块。",
                args -> {
                    ToolScope scope = guard.authorize(active, args.integer("farmId"), args.text("fieldId"), true, actor);
                    Map<String, Object> facts = reads.fieldFacts().apply(scope);
                    if (canRender && harness && "plum".equals(facts.get("crop")) && "READY".equals(facts.get("fieldFactsStatus"))) {
                        var timeline = new LinkedHashMap<String, Object>();
                        timeline.put("fieldName", facts.get("fieldName"));
                        timeline.put("crop", facts.get("cropLabel"));
                        timeline.put("date", facts.get("currentDate"));
                        timeline.put("current", facts.get("currentGrowthStageLabel"));
                        timeline.put("previous", facts.get("previousGrowthStageLabel"));
                        timeline.put("next", facts.get("nextGrowthStageLabel"));
                        timeline.put("registered", facts.get("registeredGrowthStageLabel"));
                        timeline.put("seasonal", facts.get("dateDerivedGrowthStageLabel"));
                        timeline.put("stages", facts.get("orderedPhenologyStages"));
                        timeline.put("daysUntilNext", facts.get("daysUntilNextPhenologyStage"));
                        timeline.put("basis", facts.get("phenologyBasis"));
                        timeline.put("effectiveDate", facts.get("phenologyEffectiveDate"));
                        timeline.put("accumulatedTemperature", facts.get("accumulatedTemperature"));
                        timeline.put("thermalTarget", facts.get("thermalTarget"));
                        timeline.put("temperatureMeasuredOn", facts.get("temperatureMeasuredOn"));
                        timeline.put("fieldId", scope.fieldId());
                        timeline.put("expectedRegisteredStage", facts.get("expectedRegisteredStage"));
                        timeline.put("calibrationTarget", facts.get("calibrationTarget"));
                        timeline.put("review", Boolean.TRUE.equals(facts.get("stageMismatch")));
                        facts.put("uiBlockEmitted", true);
                        return new ToolReply(facts, Map.of("componentType", "PlumPhenologyTimeline", "title", "脆李物候时间线",
                                "blockKey", "phenology-" + scope.fieldId(), "data", Map.of("phenologyTimeline", timeline)), false);
                    }
                    return ToolReply.result(facts);
                }, farm, field));
        tools.add(tool(!harness ? "read_agricultural_knowledge" : "search_agricultural_knowledge",
                "用具体检索词查询农业知识，支持重新检索，可限定作物、地块、物候和类别。",
                args -> {
                    ToolScope scope = guard.authorize(active, args.integer("farmId"), args.text("fieldId"), true, actor);
                    return reads.knowledge().apply(scope, args);
                }, requiredText("query", "要查找的农业问题或关键词", 800), farm, field,
                text("crop", "标准作物名称或代码，默认从地块或问题确定", 64),
                text("growthStage", "已知目标物候阶段的名称或代码", 64), integer("topK", "最多返回几条证据", 1, 8),
                enumeration("category", "知识类别", false, "物候", "病虫害", "水肥", "修剪", "采收", "安全间隔期"),
                text("productId", "可选的具体登记药剂编号，用于查已核验的标签和安全间隔", 128)));
        if (farmFieldService != null) {
            tools.add(tool("list_farm_fields", "查询授权农场的地块目录和实际坐标，需要地图时设置 includeMap。",
                    args -> {
                        ToolScope scope = guard.authorize(active, args.integer("farmId"), null, false, actor);
                        if (scope.farmId() == null) return ToolReply.error("FARM_REQUIRED");
                        var authorizedFields = farmFieldService.list(scope.farmId(), actor);
                        List<Map<String, Object>> fields = authorizedFields.stream().limit(40)
                                .map(item -> data("fieldId", item.getFieldId(), "name", item.getName(), "crop", item.getCrop(),
                                        "area", item.getArea(), "latitude", item.getLatitude(), "longitude", item.getLongitude()))
                                .toList();
                        var locations = args.bool("includeMap", false) && canRender && harness ? authorizedFields.stream()
                                .filter(item -> item.getLatitude() != null && item.getLongitude() != null).limit(50)
                                .map(item -> data("id", item.getFieldId(), "label", item.getName(),
                                        "latitude", item.getLatitude(), "longitude", item.getLongitude())).toList() : List.of();
                        return new ToolReply(data("status", "READY", "fields", fields, "fieldCount", authorizedFields.size(),
                                "truncated", authorizedFields.size() > fields.size(), "uiBlockEmitted", !locations.isEmpty()),
                                locations.isEmpty() ? null : data("componentType", "FarmMapCard", "title", "授权农场地块位置",
                                        "blockKey", "farm-map-" + scope.farmId(), "data", data("locations", locations)), false);
                    }, farm, bool("includeMap", "是否附加地块位置图层")));
        }
        if (currentWeatherService != null) {
            tools.add(tool("read_current_weather", "实时查询农场天气和预报。includeSprayWindow 可增加未来72小时农事天气评估。",
                    args -> {
                        boolean includeWindows = args.bool("includeSprayWindow", false) && agriculturalWeather != null;
                        ToolScope scope = guard.authorize(active, args.integer("farmId"), args.text("fieldId"), includeWindows, actor);
                        if (scope.farmId() == null) return ToolReply.error("FARM_REQUIRED");
                        LocalDate date = args.date("date");
                        var weather = includeWindows ? currentWeatherService.get(scope.farmId(), actor, true)
                                : currentWeatherService.get(scope.farmId(), actor);
                        if (weather == null) return ToolReply.error("WEATHER_UNAVAILABLE");
                        if (includeWindows) {
                            String cropCode = null;
                            String phenologyStage = null;
                            if (scope.fieldId() != null && farmFieldService != null) {
                                var fieldRecord = farmFieldService.get(scope.fieldId(), actor);
                                var crop = new CropResolver().resolve(fieldRecord.getCrop());
                                cropCode = crop.ready() ? crop.code() : null;
                                phenologyStage = fieldRecord.getGrowthStage();
                            }
                            var assessment = agriculturalWeather.forecast(scope.farmId(), actor, date, args.date("expectedHarvestDate"),
                                    phenologyStage, scope.fieldId(), cropCode, args.text("productId"));
                            var report = new LinkedHashMap<String, Object>();
                            report.put("status", weather.status());
                            report.put("weather", weather);
                            report.put("farmId", scope.farmId());
                            report.put("fieldId", scope.fieldId());
                            if (date != null) report.put("date", date.toString());
                            report.put("sprayAssessment", assessment);
                            report.put("uiBlockEmitted", canRender);
                            return new ToolReply(report, !canRender ? null : Map.of("componentType", "SprayWeatherWindowCard", "title", "未来农事作业天气",
                                    "blockKey", "SprayWeatherWindowCard:farm:" + scope.farmId(), "data", assessment,
                                    "status", "READY".equals(assessment.get("status")) ? "ready" : "unavailable"), false);
                        }
                        if (date == null) return new ToolReply(weather, Map.of("weather", weather), false);
                        var day = weather.forecast() == null ? null : weather.forecast().stream()
                                .filter(item -> date.toString().equals(item.date())).findFirst().orElse(null);
                        if (day == null) return ToolReply.error("READY".equals(weather.status()) ? "DATE_OUTSIDE_FORECAST" : "WEATHER_UNAVAILABLE");
                        return new ToolReply(Map.of("status", "READY", "date", date.toString(), "forecast", day),
                                Map.of("weather", weather), false);
                    }, farm, field, text("date", "预报日期，YYYY-MM-DD；省略时返回当前观测和可用预报", 10),
                    CopilotToolArguments.bool("includeSprayWindow", "评估未来72小时天气，不代表标签或认证已核验"),
                    text("expectedHarvestDate", "用户已明确给出的预计采收日期，YYYY-MM-DD", 10),
                    text("productId", "用户明确指定的登记药剂编号", 128)));
        }
        if (reads.monthly() != null) {
            tools.add(tool("read_monthly_guidance", "查询授权农场指定月份的已登记农事指导。",
                    args -> {
                        ToolScope scope = guard.authorize(active, args.integer("farmId"), null, false, actor);
                        if (scope.farmId() == null) return ToolReply.error("FARM_REQUIRED");
                        Integer month = args.integer("month");
                        if (month == null) month = LocalDate.now(AgriculturalWeatherAdvisor.FARM_ZONE).getMonthValue();
                        return ToolReply.result(reads.monthly().apply(scope.farmId(), month));
                    }, farm, integer("month", "月份，默认当前月", 1, 12)));
        }
        if (memories != null) {
            tools.add(tool(!harness ? "read_user_preferences" : "read_orchard_memory",
                    "读取已明确保存的果园画像、农场约束与沟通偏好。",
                    args -> {
                        ToolScope scope = guard.authorize(active, args.integer("farmId"), args.text("fieldId"), true, actor);
                        var preferences = new LinkedHashMap<String, com.mei.zhgy.service.copilot.CopilotLongTermMemory>();
                        memories.retrieve(actor, context(scope), null).stream().filter(item -> !item.getType().isProfileFact())
                                .forEach(item -> preferences.putIfAbsent(item.getKey(), item));
                        if (scope.farmId() != null) memories.retrieve(actor, new CopilotStableContext(), null)
                                .forEach(item -> preferences.putIfAbsent(item.getKey(), item));
                        var values = preferences.values().stream().limit(20)
                                .map(item -> Map.of("scope", item.getScope().name(), "type", item.getType().name(),
                                        "key", item.getKey(), "value", item.getValue())).toList();
                        Map<String, Object> profile = new LinkedHashMap<>(reads.orchardProfile().apply(context(scope)));
                        if (scope.fieldId() != null) {
                            var record = farmFieldService.get(scope.fieldId(), actor);
                            profile.put("fieldId", record.getFieldId());
                            profile.put("name", record.getName());
                            profile.put("crop", record.getCrop());
                            profile.put("area", record.getArea());
                        }
                        return ToolReply.result(Map.of("orchardProfile", profile, "preferences", values));
                    }, farm, field));
        }
        if (harness) {
            tools.add(tool("read_previous_analysis", "读取已经保存的视觉报告、诊断和待审批工作流。",
                    reads.previousAnalysis(),
                    text("analysisRunId", "视觉分析编号", 128), text("diagnosisId", "诊断编号", 128),
                    text("workflowRunId", "审批工作流编号", 128)));
        } else if (maturityService != null) {
            String currentReport = reads.currentReport();
            tools.add(tool("read_previous_visual_analysis", "按报告 ID 查询已授权的视觉分析摘要，结论只代表当时所见。",
                    args -> {
                        String selected = args.text("runId") == null ? currentReport : args.text("runId");
                        if (selected == null || selected.isBlank()) return ToolReply.error("RUN_REQUIRED");
                        Map<String, Object> report = maturityService.run(selected, actor);
                        ToolScope scope = guard.authorize(active, null, null, false, actor);
                        if (scope.farmId() != null && !scope.farmId().toString().equals(String.valueOf(report.get("farmId")))) {
                            return ToolReply.error("SCOPE_UNAVAILABLE");
                        }
                        Map<String, Object> summary = new LinkedHashMap<>();
                        for (String key : List.of("runId", "farmId", "kind", "mode", "observedAt", "status", "summary")) {
                            Object value = report.get(key);
                            if (value != null) summary.put(key, truncate(String.valueOf(value), 600));
                        }
                        return ToolReply.result(summary);
                    }, runId));
        }
        if (!harness && agentRuntime != null) {
            tools.add(tool("read_workflow_status", "按运行编号查询已授权农事工作流及人工审批状态。",
                    args -> {
                        String selected = args.text("runId") == null ? active.getRunId() : args.text("runId");
                        if (selected == null || selected.isBlank()) return ToolReply.error("RUN_REQUIRED");
                        AgentRun run = agentRuntime.require(selected, actor);
                        ToolScope scope = guard.authorize(active, null, null, false, actor);
                        if (scope.farmId() != null && (run.getContext() == null || !scope.farmId().toString().equals(run.getContext().getFarmId()))) {
                            return ToolReply.error("SCOPE_UNAVAILABLE");
                        }
                        return ToolReply.result(Map.of("runId", run.getRunId(), "status", run.getStatus(),
                                "approvalState", run.getApprovalState()));
                    }, runId));
        }
        if (drafting) {
            if (harness) tools.add(AgriculturalTaskDraftTool.register(guard, active, actor, reads.evidence()));
            else tools.add(tool("propose_task_draft", "提供当前地块的待确认草稿参数。此工具只验证并返回候选。",
                    args -> {
                        LocalDate date = args.date("scheduledFor");
                        if (date != null && date.isBefore(LocalDate.now(AgriculturalWeatherAdvisor.FARM_ZONE))) return ToolReply.error("DATE_IN_PAST");
                        return ToolReply.result(new AgentRuntime.TaskDraft(args.text("title"), args.text("detail"), date == null ? null : date.toString()));
                    }, requiredText("title", "简洁的农事任务标题", 120), requiredText("detail", "拟执行操作与前置条件", 1200),
                    text("scheduledFor", "仅在用户指定时提供日期，YYYY-MM-DD", 10)));
        }
        return tools;
    }

    private static CopilotStableContext context(CopilotSecurityGuard.ToolScope scope) {
        return CopilotStableContext.builder().farmId(scope.farmId() == null ? null : scope.farmId().toString())
                .fieldId(scope.fieldId()).build();
    }

    private static String truncate(String value, int limit) {
        return value.substring(0, Math.min(limit, value.length()));
    }

    static List<ReadTool> workspaceTools(FarmFieldService fields, Long actor, CopilotPreferenceTool preferences) {
        var tools = new ArrayList<ReadTool>();
        if (fields != null) tools.add(tool("read_farm_directory", "查询当前用户有权访问的全部农场。优先用于名下农场、果园数量或目录。",
                args -> {
                    var farms = fields.accessibleFarms(actor);
                    return new ToolReply(Map.of("status", "READY", "farms", farms, "count", farms.size()),
                            Map.of("componentType", "FarmDirectoryCard", "title", "农场列表", "data", Map.of("farms", farms),
                                    "blockKey", "farm-directory"), false);
                }));
        tools.add(tool("navigate_workspace", "用户想打开工作台页面时提供入口。只允许下列目的地。",
                args -> {
                    String path = args.text("route");
                    String title = CopilotWorkspaceRoutes.title(path);
                    if (title == null) return ToolReply.error("NAVIGATION_UNAVAILABLE");
                    return new ToolReply(Map.of("status", "READY", "route", path, "destination", title),
                            Map.of("componentType", "WorkspaceNavigationCard", "title", title, "data", Map.of("route", path),
                                    "blockKey", "navigation:" + path.replace('/', '-')), false);
                }, enumeration("route", "农事任务、诊断、分析、知识、地图或总览", true,
                        CopilotWorkspaceRoutes.PATHS.toArray(String[]::new))));
        if (preferences != null) tools.add(tool("manage_user_preference",
                "查询、保存或忘掉长期偏好。写入和撤销只接受用户本轮明确授权，偏好内容及撤销目标由后端核对原话。",
                preferences::execute,
                enumeration("operation", "LIST 查询，SAVE 保存，FORGET 撤销", true, "LIST", "SAVE", "FORGET")));
        return List.copyOf(tools);
    }

    static ReadTool memoryProposal(java.util.function.Function<CopilotToolArguments, ToolReply> handler) {
        return tool("propose_memory_update", "将用户本轮明确披露的果园属性提议为长期记忆，须用户确认。", handler,
                enumeration("scope", "资料归属", true, "FARM", "FIELD"),
                enumeration("key", "资料类型", true, "plum_variety", "tree_age_years", "elevation_band",
                        "irrigation_facility", "quality_certification", "historical_high_risk_pests",
                        "variety", "tree_age", "elevation", "certification", "irrigation"),
                requiredText("value", "用户原话中已明确给出的值", 200),
                text("displayText", "可读说明，服务端使用固定模板", 200));
    }

    public List<ReadTool> tools(CopilotStableContext active, Long actor, String crop, String question, List<String> attachments, boolean presentationEnabled) {
        return tools(active, actor, crop, question, attachments, presentationEnabled, new AgriculturalEvidenceLedger());
    }

    public List<ReadTool> tools(CopilotStableContext active, Long actor, String crop, String question, List<String> attachments,
                                boolean presentationEnabled, AgriculturalEvidenceLedger evidence) {
        var latestObservation = new java.util.concurrent.atomic.AtomicReference<com.mei.zhgy.service.rag.VisualObservation>();
        var farmId = integer("farmId", "授权农场编号", 1, Integer.MAX_VALUE);
        var fieldId = text("fieldId", "当前授权农场内的地块 ID 或名称", 128);
        List<ReadTool> tools = new ArrayList<>();
        tools.add(tool("query_operations_tasks", "查询当前农场的任务、排期及执行反馈。",
                args -> {
                    var scope = guard.authorize(active, args.integer("farmId"), args.text("fieldId"), false, actor);
                    if (scope.farmId() == null) return ToolReply.error("FARM_REQUIRED");
                    LocalDate from = args.date("from"), to = args.date("to");
                    if (from != null && to != null && from.isAfter(to)) {
                        return invalid("to", args.get("to"), List.of(), "结束日期不得早于开始日期");
                    }
                    String view = args.text("view") == null ? "tasks" : args.text("view");
                    String status = args.text("status");
                    Map<String, Object> response = tasks.view(scope.farmId(), scope.fieldId(), args.text("from"), args.text("to"), view, status, actor);
                    var criteria = data("farmId", scope.farmId(), "fieldId", scope.fieldId(), "from", args.text("from"),
                            "to", args.text("to"), "taskView", view, "taskFilter", status);
                    var items = response.get("items") instanceof List<?> values ? values : List.of();
                    var summary = items.stream().limit(12).filter(Map.class::isInstance).map(Map.class::cast)
                            .map(item -> data("taskId", item.get("taskId"), "title", item.get("title"), "fieldName", item.get("fieldName"),
                                    "status", item.get("status"), "scheduledFor", item.get("scheduledFor"))).toList();
                    return new ToolReply(data("status", "READY", "items", summary, "counts", response.get("counts"),
                            "farmId", scope.farmId(), "fieldId", scope.fieldId(), "criteria", criteria,
                            "truncated", items.size() > summary.size() || Boolean.TRUE.equals(response.get("truncated")), "uiBlockEmitted", presentationEnabled),
                            presentationEnabled ? data("componentType", "OperationsQueryCard", "title", "农事任务", "data", criteria) : null, false);
                }, farmId, fieldId, text("from", "开始日期 YYYY-MM-DD", 10), text("to", "结束日期 YYYY-MM-DD", 10),
                enumeration("status", "任务状态", false, "active", "pending", "in-progress", "completed"),
                enumeration("view", "返回任务或历史记录", false, "tasks", "calendar", "records")));
        tools.add(tool("lookup_reference_atlas", "查询经过登记的病虫害及物候图鉴。",
                args -> {
                    if (active != null && active.getFieldId() != null && !crops.resolve(crop).ready()) {
                        return ToolReply.error("UNKNOWN_CROP");
                    }
                    String selectedCrop = args.text("crop") == null ? crop : args.text("crop");
                    var resolved = crops.resolve(selectedCrop);
                    if (!resolved.ready()) return ToolReply.error("CROP_REQUIRED");
                    String stage = args.text("growthStage");
                    if (stage != null) {
                        var resolvedStage = phenology.resolveStage(resolved.code(), stage);
                        if (resolvedStage == null) return ToolReply.error("UNKNOWN_GROWTH_STAGE");
                        stage = resolvedStage.code();
                    }
                    String key = args.text("diseaseOrPest");
                    var found = atlas.forChatResult(key == null ? stage == null ? question : args.text("growthStage") + " 物候期参考图" : key,
                            resolved.code(), stage);
                    var images = found.images().stream().limit(8).toList();
                    Map<String, Object> comparison = new LinkedHashMap<>();
                    comparison.put("siteImages", siteImages(attachments));
                    var observation = latestObservation.get();
                    if (observation != null) comparison.put("differentialFeatures", observation.visibleSymptoms().stream()
                            .map(symptom -> Map.of("label", symptom, "present", Boolean.TRUE.equals(observation.imageClear()))).toList());
                    if ("plum".equals(resolved.code()) && key != null) {
                        comparison.put("diagnosisLabel", key);
                        comparison.put("phi", Map.of("warning", "施用前需核对登记标签、适用靶标和采收安全间隔期。"));
                        if (structuredAgronomy != null) {
                            var advice = structuredAgronomy.assessPlumDefense(new PlumDefenseKnowledgeService.AssessmentRequest(
                                    key, null, LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), null, null, null));
                            advice.rows().stream().filter(row -> row.kind() != PlumDefenseKnowledgeService.MeasureKind.CHEMICAL_LABEL
                                    && row.gateStatus() == PlumDefenseKnowledgeService.GateStatus.ELIGIBLE).findFirst()
                                    .ifPresent(row -> comparison.put("recommendation", Map.of("title", "田间处置建议", "detail", row.guidance())));
                        }
                    }
                    return new ToolReply(data("status", found.status(), "count", images.size(), "items", images,
                            "uiBlockEmitted", presentationEnabled && !images.isEmpty()),
                            presentationEnabled && !images.isEmpty() ? data("componentType", "PestComparisonCard",
                                    "title", key == null ? "物候图鉴" : "病虫害对照图鉴",
                                    "data", data("atlasFigures", images, "pestComparison", comparison)) : null, false);
                }, text("diseaseOrPest", "病虫害名称", 80), text("growthStage", "物候阶段", 64), text("crop", "作物名称或标准代码", 64)));
        tools.add(tool("search_historical_cases", "按症状查找当前农场已由专家复核的病例。有现场图时同时支持相似图检索。",
                true, args -> {
                    var scope = guard.authorize(active, null, args.text("fieldId"), args.text("fieldId") != null, actor);
                    if (!attachments.isEmpty() && args.text("growthStage") == null) {
                        var visualMatches = history.search(attachments, args.integer("topK"));
                        if ("READY".equals(visualMatches.status()) || reviewedCases == null) return ToolReply.result(visualMatches.asMap());
                    }
                    if (reviewedCases == null) return ToolReply.error("HISTORICAL_TEXT_RETRIEVAL_UNAVAILABLE");
                    if (scope.farmId() == null) return ToolReply.error("FARM_REQUIRED");
                    Integer topK = args.integer("topK");
                    String stage = args.text("growthStage");
                    if (stage != null && crop != null) {
                        var knownCrop = crops.resolve(crop);
                        var canonical = knownCrop.ready() ? phenology.resolveStage(knownCrop.code(), stage) : null;
                        if (canonical != null) stage = canonical.code();
                    }
                    var items = reviewedCases.search(actor, scope.farmId(), scope.fieldId(), stage,
                            args.text("symptomQuery"), topK == null ? 3 : topK);
                    return ToolReply.result(data("status", items.isEmpty() ? "EMPTY" : "READY", "farmId", scope.farmId(),
                            "fieldId", scope.fieldId(), "source", "OWNER_EXPERT_REVIEW", "items", items));
                }, requiredText("symptomQuery", "症状或病虫名称", 800),
                fieldId, text("growthStage", "物候阶段提示", 64), integer("topK", "结果数", 1, 10)));
        tools.add(tool("analyze_uploaded_images", "分析本轮上传图片，返回结构化田间观测。",
                true, args -> {
                    var scope = guard.authorize(active, null, args.text("fieldId"), true, actor);
                    List<String> photos = args.strings("imageUrls");
                    if (photos.isEmpty()) photos = attachments;
                    if (photos.isEmpty()) return ToolReply.error("IMAGE_REQUIRED");
                    if (!attachments.containsAll(photos)) return ToolReply.error("SCOPE_UNAVAILABLE");
                    if ("DISEASE".equals(args.text("focus"))) {
                        var input = new DiagnosisAnalyzeRequest();
                        input.setFarmId(scope.farmId()); input.setFieldId(scope.fieldId());
                        input.setQuery(question); input.setImageUrls(photos);
                        if (scope.fieldId() != null) fields.applyToDiagnosisRequest(input, actor);
                        var observations = visual.retrieve(input).stream().limit(8).toList();
                        var visualFact = observations.stream().filter(item -> item.getMetadata() != null)
                                .map(item -> item.getMetadata().get("visualObservation"))
                                .filter(com.mei.zhgy.service.rag.VisualObservation.class::isInstance)
                                .map(com.mei.zhgy.service.rag.VisualObservation.class::cast).findFirst().orElse(null);
                        var matches = observations.stream().filter(item -> item.getMetadata() != null
                                        && "VISUAL_SIMILARITY".equals(item.getMetadata().get("retrievalType")))
                                .map(item -> data("assetId", item.getMetadata().get("assetId"),
                                        "similarity", item.getMetadata().get("similarity"))).toList();
                        var output = data("status", observations.isEmpty() ? "NO_VALID_OBSERVATIONS" : "READY", "observations", observations,
                                "farmId", scope.farmId(), "fieldId", scope.fieldId(), "clipMatches", matches);
                        if (visualFact != null) {
                            latestObservation.set(visualFact);
                            output.putAll(data("subject", visualFact.subject(), "visibleSymptoms", visualFact.visibleSymptoms(),
                                    "imageClear", visualFact.imageClear(), "observedPhenologyStage", visualFact.observedPhenologyStage(),
                                    "missingViews", visualFact.missingViews(), "uncertainty", visualFact.uncertainty()));
                        }
                        return ToolReply.result(output);
                    }
                    return inspection(scope, actor, List.of(), photos, args.text("focus"), "IMAGE", presentationEnabled, ignored -> {});
                }, strings("imageUrls", "本轮已授权的图片引用", 8),
                enumeration("focus", "关注的分析维度", true, "DISEASE", "MATURITY", "PHENOLOGY", "GROWTH"), fieldId));
        tools.add(progressTool("inspect_camera_maturity", "检查指定摄像头、地块或全农场当前可用画面，生成独立报告。",
                (args, progress) -> {
                    String mode = args.text("scope");
                    var scope = guard.authorize(active, null, args.text("fieldId"), "FIELD".equals(mode), actor);
                    var cameraIds = args.strings("cameraIds");
                    if ("CAMERA".equals(mode) && cameraIds.isEmpty()) return ToolReply.error("CAMERA_REQUIRED");
                    if ("FIELD".equals(mode) && scope.fieldId() == null) return ToolReply.error("FIELD_REQUIRED");
                    return inspection(scope, actor, cameraIds, List.of(), args.text("kind"), mode, presentationEnabled, progress);
                }, enumeration("scope", "检查范围", true, "CAMERA", "FIELD", "FARM"), strings("cameraIds", "选定设备 ID", 12),
                fieldId, enumeration("kind", "分析维度，默认成熟度", false, "MATURITY", "PHENOLOGY", "GROWTH")));
        if (presentationEnabled) {
            var validator = new CopilotUiBlockValidator(evidence, guard, active, actor);
            tools.add(tool("render_ui_block", "展示经校验的对比矩阵、作业清单、风险、可信数据图表或澄清表单。",
                    validator::validate,
                    enumeration("componentType", "组件类型", true, "ComparisonMatrixCard", "ActionChecklistCard", "RiskAlertCard",
                            "EChartsAnalyticsCard", "ClarificationForm"),
                    requiredText("title", "标题", 80), text("summary", "摘要", 400),
                    object("props", "对比 options:[{label,conditions,cost,speed,advantages,disadvantages,phiDays?,draft?}]，清单 items:[{title,detail,priority:URGENT|ROUTINE|PREVENTIVE,prerequisites?,draft?}]，draft 为 {fieldId,title,detail,scheduledFor?}；图表用 chartKind 和工具给出的 series:[{label,points:[{label,value}]}]；澄清 fields:[{label}]，风险 items:[{label,value}]。", true),
                    strings("evidenceIds", "本轮已取得的证据编号", 16)));
        }
        return tools;
    }

    static List<Map<String, Object>> siteImages(List<String> attachments) {
        return attachments.stream().limit(6).map(url -> Map.<String, Object>of("imageUrl", url, "label", "现场观测")).toList();
    }

    private ToolReply inspection(CopilotSecurityGuard.ToolScope scope, Long actor, List<String> cameras, List<String> photos,
                                 String kind, String mode, boolean presentationEnabled, Consumer<ToolReply> progress) {
        if (scope.farmId() == null) return ToolReply.error("FARM_REQUIRED");
        String dimension = kind == null ? "MATURITY" : kind;
        String key = "inspection-" + UUID.randomUUID();
        FarmMaturityService.ScanProgress[] latest = {new FarmMaturityService.ScanProgress(0, 0, 0, 0)};
        if (presentationEnabled) progress.accept(inspectionProgress(key, latest[0], "loading"));
        Map<String, Object> report;
        try {
            report = maturity.analyze(scope.farmId(), actor, cameras, scope.fieldId(), photos, dimension, mode, update -> {
                latest[0] = update;
                if (presentationEnabled) progress.accept(inspectionProgress(key, update, "loading"));
            });
        } catch (RuntimeException failed) {
            if (presentationEnabled) progress.accept(inspectionProgress(key, latest[0], "failed"));
            throw failed;
        }
        if (presentationEnabled) progress.accept(inspectionProgress(key, latest[0], "completed"));
        var block = data("runId", report.get("runId"), "farmId", scope.farmId(), "kind", dimension, "mode", mode);
        var maturitySeries = "MATURITY".equals(dimension) ? maturitySeries(report.get("parcels")) : List.<Map<String, Object>>of();
        return new ToolReply(data("status", report.get("status"), "runId", report.get("runId"), "summary", report.get("summary"),
                "farmId", scope.farmId(), "fieldId", scope.fieldId(), "recommendations", report.get("recommendations"),
                "maturitySeries", maturitySeries, "uiBlockEmitted", presentationEnabled),
                presentationEnabled ? data("componentType", "FarmMaturityCard", "title", "视觉分析", "data", block,
                        "maturitySeries", maturitySeries,
                        "status", "NO_VALID_OBSERVATIONS".equals(report.get("status")) ? "unavailable" : "ready") : null, false);
    }

    private List<Map<String, Object>> maturitySeries(Object source) {
        if (!(source instanceof List<?> parcels)) return List.of();
        List<String> stages = List.of("UNRIPE", "TURNING", "RIPE", "OVER_RIPE", "MIXED");
        List<String> labels = List.of("未成熟", "转色", "成熟", "过熟", "混合");
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        for (String stage : stages) groups.add(new ArrayList<>());
        for (Object value : parcels) {
            if (!(value instanceof Map<?, ?> parcel) || parcel.get("fieldId") == null
                    || !(parcel.get("name") instanceof String name) || !(parcel.get("observations") instanceof List<?> observations)) continue;
            int[] counts = new int[stages.size()];
            int total = 0;
            for (Object raw : observations) if (raw instanceof Map<?, ?> observation && "SUCCESS".equals(observation.get("status"))) {
                int index = stages.indexOf(String.valueOf(observation.get("stage")));
                if (index >= 0) { counts[index]++; total++; }
            }
            if (total == 0) continue;
            for (int index = 0; index < stages.size(); index++) {
                groups.get(index).add(Map.of("label", name, "value", 100D * counts[index] / total));
            }
            if (groups.get(0).size() == 20) break;
        }
        if (groups.get(0).size() < 2) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            result.add(CopilotUiBlockValidator.chartSeries("stacked-bar", labels.get(index), groups.get(index)));
        }
        return List.copyOf(result);
    }

    private ToolReply inspectionProgress(String key, FarmMaturityService.ScanProgress progress, String status) {
        return new ToolReply(null, data("componentType", "FarmInspectionProgress", "blockKey", key,
                "title", "农场巡检进度", "status", status, "data", data("total", progress.total(),
                        "processed", progress.processed(), "captured", progress.captured(), "valid", progress.valid())), false);
    }

    private static Map<String, Object> data(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) if (entries[i + 1] != null) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}

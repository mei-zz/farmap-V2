package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mei.zhgy.service.agent.AgentContext;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ReadTool;
import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.mei.zhgy.service.copilot.CopilotToolArguments.*;

/** Validates an in-memory model proposal. Only the application can submit it for human approval. */
public final class AgriculturalTaskDraftTool {
    private static final Set<String> TYPES = Set.of("HARVEST", "INSPECTION", "IRRIGATION", "FERTILIZATION", "PRUNING", "SPRAY", "OTHER");
    private static final Set<String> FIELDS = Set.of("fieldId", "title", "detail", "taskType", "scheduledFor", "precaution", "productId");

    public record Batch(List<AgentRuntime.ScopedTaskDraft> tasks) {
        public Batch { tasks = List.copyOf(tasks); }
    }

    public static ReadTool register(CopilotSecurityGuard guard, CopilotStableContext active, Long actor,
                                    AgriculturalEvidenceLedger ledger) {
        return tool("propose_task_draft", "验证一至八条待确认任务。每条任务都须关联已授权地块及本轮证据。此工具不会创建已执行的任务。",
                args -> propose(args, guard, active, actor, ledger),
                objects("tasks", "每项：fieldId、title、detail、taskType，可选 scheduledFor 和 precaution。施药另须 productId 与本轮同地块同日核验。taskType 使用 HARVEST、INSPECTION、IRRIGATION、FERTILIZATION、PRUNING、SPRAY 或 OTHER", 8),
                strings("evidenceIds", "仅引用本轮实际取得的 [E#] 证据编号，不含括号", 16));
    }

    private static ToolReply propose(CopilotToolArguments args, CopilotSecurityGuard guard, CopilotStableContext active,
                                     Long actor, AgriculturalEvidenceLedger ledger) {
        var ids = args.strings("evidenceIds");
        if (!ledger.supports(ids)) return invalid("evidenceIds", null, List.of(), "请先读取地块和已排任务，再填写本轮证据编号");
        var tasks = new ArrayList<AgentRuntime.ScopedTaskDraft>();
        var unique = new HashSet<List<String>>();
        for (JsonNode item : args.get("tasks")) {
            var names = item.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) return invalid("tasks", null, List.of(), "任务包含未声明字段");
            String title = textField(item, "title", 120);
            String detail = textField(item, "detail", 880);
            String type = textField(item, "taskType", 24);
            String field = textField(item, "fieldId", 128);
            String date = textField(item, "scheduledFor", 10);
            String precaution = textField(item, "precaution", 240);
            if (item.hasNonNull("fieldId") && field == null || item.hasNonNull("scheduledFor") && date == null
                    || item.hasNonNull("precaution") && precaution == null) {
                return invalid("tasks", null, List.of(), "请检查可选的地块、日期和注意事项字段");
            }
            String category = type == null ? "" : type.toUpperCase(Locale.ROOT);
            if (title == null || detail == null || !TYPES.contains(category)) {
                return invalid("tasks", null, TYPES.stream().sorted().toList(), "任务需要标题、详情和支持的任务类型");
            }
            var scope = guard.authorize(active, null, field, true, actor);
            if (scope.fieldId() == null) return invalid("tasks.fieldId", null, List.of(), "请指定任务对应的地块");
            if (date != null && !date.matches("\\d{4}-\\d{2}-\\d{2}")) return invalid("tasks.scheduledFor", item.get("scheduledFor"), List.of(), "请使用 YYYY-MM-DD");
            if (date != null) {
                try {
                    if (LocalDate.parse(date).isBefore(LocalDate.now(AgriculturalWeatherAdvisor.FARM_ZONE))) {
                        return ToolReply.error("DATE_IN_PAST");
                    }
                } catch (java.time.format.DateTimeParseException invalid) {
                    return invalid("tasks.scheduledFor", item.get("scheduledFor"), List.of(), "请输入有效的计划日期");
                }
            }
            var evidence = ledger.evidence(ids, scope.farmId(), scope.fieldId());
            if (evidence.isEmpty()) return ToolReply.error("EVIDENCE_SCOPE_UNAVAILABLE");
            boolean spray = "SPRAY".equals(category) || (title + detail + (precaution == null ? "" : precaution))
                    .matches("(?s).*(喷药|施用农药|喷施药剂|叶面施药|打药).*");
            if (spray) {
                String productId = textField(item, "productId", 128);
                if (!"SPRAY".equals(category) || !ledger.supportsSpray(ids, scope.farmId(), scope.fieldId(), productId, date)) {
                    return ToolReply.error("SPRAY_LABEL_PHI_CERTIFICATION_REQUIRED");
                }
                title = "复核施药安排";
                detail = "登记药剂编号：" + productId + "。按有效登记标签、认证条件、采收安全间隔及当天现场天气复核后处置。";
                precaution = null;
            }
            if (!unique.add(List.of(scope.fieldId(), title, date == null ? "" : date))) return ToolReply.error("DUPLICATE_DRAFT_TASK");
            var target = AgentContext.builder().farmId(scope.farmId().toString()).fieldId(scope.fieldId()).build();
            var draft = new AgentRuntime.TaskDraft(scope.fieldId(), title,
                    precaution == null ? detail : detail + "\n注意事项：" + precaution, date, evidence);
            tasks.add(new AgentRuntime.ScopedTaskDraft(target, draft, evidence));
        }
        return ToolReply.result(new Batch(tasks));
    }

    private static String textField(JsonNode value, String key, int max) {
        JsonNode node = value.path(key);
        if (!node.isTextual()) return null;
        String text = node.textValue().trim();
        return text.isEmpty() || text.length() > max ? null : text;
    }
}

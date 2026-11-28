package com.mei.zhgy.service.copilot;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-response projection for the ordered presentation protocol and transcript restoration. */
public final class CopilotPresentationEmitter {
    private static final int MAX_PARTS = 160;
    private final String runId;
    private final Map<String, Map<String, Object>> transcript = new LinkedHashMap<>();
    private final List<Map<String, Object>> blocks = new ArrayList<>();
    private final Map<String, Map<String, Object>> blocksByKey = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> citations = new LinkedHashMap<>();
    private static final java.util.regex.Pattern CITATION = java.util.regex.Pattern.compile("\\[(?:E)?([1-9][0-9]{0,3})\\]");
    private int sequence;
    private String textPartId;

    public CopilotPresentationEmitter(String runId) { this.runId = runId; }

    public record BlockChange(Map<String, Object> block, boolean created) {}

    List<Map<String, Object>> blocks() { return blocks; }

    public BlockChange upsertBlock(String type, String title, String summary, Map<String, Object> data, String status,
                                   List<Map<String, Object>> provenance, String blockKey) {
        String time = Instant.now().toString();
        Map<String, Object> previous = blockKey == null ? null : blocksByKey.get(blockKey);
        if (previous != null) {
            if (!type.equals(previous.get("componentType"))) throw new IllegalArgumentException("Cannot change a keyed block's component type");
            previous.put("title", title); previous.put("data", data); previous.put("status", status);
            if (summary != null) previous.put("summary", summary); else previous.remove("summary");
            previous.put("actions", actions(type, data));
            previous.put("provenance", provenance); previous.put("updatedAt", time);
            return new BlockChange(previous, false);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockId", "block-" + UUID.randomUUID());
        if (blockKey != null) block.put("blockKey", blockKey);
        block.put("runId", runId); block.put("componentType", type); block.put("schemaVersion", 1);
        block.put("title", title); if (summary != null) block.put("summary", summary);
        block.put("data", data); block.put("status", status);
        block.put("actions", actions(type, data));
        block.put("provenance", provenance); block.put("createdAt", time); block.put("updatedAt", time);
        blocks.add(block);
        if (blockKey != null) blocksByKey.put(blockKey, block);
        return new BlockChange(block, true);
    }

    private List<Map<String, Object>> actions(String type, Map<String, Object> data) {
        if ("MemoryProposalCard".equals(type) && data.get("memoryProposal") instanceof CopilotMemoryProposalService.ProposalView proposal) {
            return List.of(Map.of("type", "confirm-memory", "label", "确认保存", "proposalId", proposal.proposalId(), "decision", "confirm"),
                    Map.of("type", "confirm-memory", "label", "忽略", "proposalId", proposal.proposalId(), "decision", "ignore"));
        }
        if (!"FarmMapCard".equals(type) || !(data.get("locations") instanceof List<?> locations)) return List.of();
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Object item : locations) {
            if (item instanceof Map<?, ?> location && location.get("id") != null && location.get("label") != null) {
                actions.add(Map.of("type", "select-field", "label", String.valueOf(location.get("label")),
                        "value", String.valueOf(location.get("id"))));
            }
        }
        actions.add(Map.of("type", "open-detail", "label", "打开地图", "value", "/farm/map"));
        return actions;
    }

    public synchronized void afterLegacyEvent(SseEmitter stream, String event, Object payload) throws IOException {
        if (!(payload instanceof Map<?, ?> data)) return;
        switch (event) {
            case "presentation.summary" -> {
                textPartId = null;
                text(stream, data.get("text"));
                textPartId = null;
            }
            case "content.delta" -> text(stream, data.get("delta"));
            case "presentation.analysis" -> text(stream, data.get("text"));
            case "status" -> thinking(stream, "running", data.get("message"));
            case "done" -> thinking(stream, "done", null);
            case "agent.runtime" -> {
                if ("thinking_start".equals(data.get("type"))) thinking(stream, "running", null);
                if ("thinking_end".equals(data.get("type"))) thinking(stream, "done", null);
            }
            case "tool.started", "tool.updated", "tool.completed", "tool.failed" -> {
                if (!(data.get("toolName") instanceof String name)) return;
                String id = data.get("toolCallId") instanceof String callId ? callId : name;
                String status = switch (event) {
                    case "tool.completed" -> Boolean.TRUE.equals(data.get("isError")) ? "failed" : "done";
                    case "tool.failed" -> "failed";
                    default -> "running";
                };
                String callId = runId + "-tool-" + id;
                emit(stream, callId, Map.of("type", "tool_call", "callId", callId, "toolName", name, "label", safeToolLabel(name), "status", status));
            }
            case "ui.block.created", "ui.block.updated" -> {
                if (data.get("blockId") instanceof String blockId) {
                    String blockKey = data.get("blockKey") instanceof String key ? key : blockId;
                    var block = new LinkedHashMap<>(data);
                    emit(stream, "block:" + blockKey, Map.of("type", "ui_block", "blockKey", blockKey, "block", block,
                            "citations", List.copyOf(citations.values())));
                    stream.send(SseEmitter.event().name("ui.block.upserted")
                            .data(Map.of("blockKey", blockKey, "block", block), MediaType.APPLICATION_JSON));
                }
            }
            case "knowledge.evidence" -> rememberCitation(data);
            case "presentation.followups" -> {
                if (data.get("items") instanceof List<?> items && !items.isEmpty()) {
                    emit(stream, "followups", Map.of("type", "follow_ups", "items", items));
                }
            }
            default -> { }
        }
    }

    private void rememberCitation(Map<?, ?> data) {
        if (!(data.get("evidenceId") instanceof String alias) || !alias.matches("E[1-9][0-9]{0,3}")) return;
        if (citations.size() >= 96 && !citations.containsKey(alias)) return;
        var reference = new LinkedHashMap<String, Object>();
        reference.put("index", Integer.parseInt(alias.substring(1)));
        reference.put("evidenceId", alias);
        reference.put("title", data.get("title") instanceof String title && !title.isBlank() ? title : "当前证据");
        reference.put("sourceName", data.get("source") instanceof String source && !source.isBlank() ? source : "FarMap");
        reference.put("modality", data.get("modality") instanceof String modality
                && java.util.Set.of("knowledge", "camera", "historical_case", "weather", "field", "atlas").contains(modality)
                ? modality : "knowledge");
        if (data.get("summary") instanceof String snippet && !snippet.isBlank()) reference.put("snippet", snippet);
        for (String key : List.of("stageLabel", "imageUrl", "documentName", "expertStatus")) {
            if (data.get(key) instanceof String value && !value.isBlank()) reference.put(key, value);
        }
        if (data.get("score") instanceof Number score && Double.isFinite(score.doubleValue())) reference.put("score", score);
        citations.put(alias, reference);
    }

    private void thinking(SseEmitter stream, String status, Object hint) throws IOException {
        String id = runId + "-thinking";
        var part = new LinkedHashMap<String, Object>();
        part.put("type", "thinking");
        part.put("id", id);
        part.put("status", status);
        if (hint instanceof String message && !message.isBlank()) part.put("label", message);
        emit(stream, id, part);
    }

    private void text(SseEmitter stream, Object candidate) throws IOException {
        if (!(candidate instanceof String value) || value.isEmpty()) return;
        if (textPartId == null) textPartId = runId + "-part-" + ++sequence;
        var previous = transcript.get(textPartId);
        String combined = (previous == null ? "" : previous.get("markdown").toString()) + value;
        if (combined.length() > 16_000) combined = combined.substring(0, 16_000);
        var linked = new LinkedHashMap<String, Map<String, Object>>();
        var matches = CITATION.matcher(combined);
        while (matches.find()) {
            String alias = "E" + matches.group(1);
            if (citations.containsKey(alias)) linked.putIfAbsent(alias, citations.get(alias));
        }
        var refs = List.copyOf(linked.values());
        stream.send(SseEmitter.event().name("message.part")
                .data(Map.of("type", "text", "id", textPartId, "delta", value, "citations", refs), MediaType.APPLICATION_JSON));
        put(textPartId, Map.of("type", "text", "id", textPartId, "markdown", combined, "citations", refs));
    }

    private void emit(SseEmitter stream, String id, Map<String, Object> part) throws IOException {
        stream.send(SseEmitter.event().name("message.part").data(part, MediaType.APPLICATION_JSON));
        if (!transcript.containsKey(id)) textPartId = null;
        put(id, part);
    }

    private String safeToolLabel(String name) {
        return switch (name) {
            case "read_current_weather" -> "读取农场天气";
            case "read_field_context" -> "核对地块与物候";
            case "read_farm_directory" -> "读取农场列表";
            case "search_agricultural_knowledge", "read_agricultural_knowledge" -> "检索农业知识";
            case "analyze_uploaded_images" -> "分析上传图片";
            case "lookup_reference_atlas" -> "查找病虫害图鉴";
            case "search_historical_cases" -> "检索历史病例";
            case "propose_task_draft" -> "准备任务草稿";
            case "list_farm_fields" -> "读取农场地块";
            case "read_orchard_memory" -> "读取果园记忆";
            case "read_user_preferences" -> "读取用户偏好";
            case "read_previous_analysis" -> "读取历史分析";
            case "read_previous_visual_analysis" -> "读取历史视觉分析";
            case "read_workflow_status" -> "查询工作流状态";
            case "read_monthly_guidance" -> "读取月度农事指导";
            case "render_ui_block" -> "整理可交互结果";
            case "query_operations_tasks" -> "查询农事任务";
            case "inspect_camera_maturity" -> "巡检摄像头画面";
            case "propose_memory_update" -> "准备果园资料提案";
            default -> name;
        };
    }

    private void put(String id, Map<String, Object> part) {
        if (transcript.size() < MAX_PARTS || transcript.containsKey(id)) transcript.put(id, part);
    }

    public synchronized List<Map<String, Object>> snapshot() {
        return transcript.values().stream().map(part -> {
            if (!"thinking".equals(part.get("type"))) return part;
            Map<String, Object> saved = new LinkedHashMap<>(part);
            saved.put("status", "done");
            return saved;
        }).toList();
    }
}

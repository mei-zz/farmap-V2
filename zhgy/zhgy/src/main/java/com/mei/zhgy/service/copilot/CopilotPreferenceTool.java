package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.agent.pi.PiAgentRuntime.ToolReply;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A tool can request a mutation, but only the authenticated user's current message authorizes it. */
final class CopilotPreferenceTool {
    private static final String POLITE = "^(?:(?:你好|请|麻烦|能否|可以|帮我|替我|替我把|你能|请你|能不能|一下)[，、：:！!。]?)*";
    private static final Pattern SAVE = Pattern.compile(POLITE + "(?:记住|记一下|记下来|记下|保存(?:这条|这个|我的)?偏好|remember\\s|save\\s)");
    private static final Pattern FORGET = Pattern.compile(POLITE + "(?:忘掉|忘记|撤销|取消记忆|删除(?:这条|这个|我的)?(?:偏好|记忆)|forget\\s|remove\\s)");
    private final CopilotLongTermMemoryService memories;
    private final CopilotStableContext scope;
    private final String message;
    private final Long actor;
    private final String conversationId;
    private final String turnId;
    private String mutationOperation;
    private ToolReply mutationResult;

    CopilotPreferenceTool(CopilotLongTermMemoryService memories, CopilotStableContext scope, String message,
                          Long actor, String conversationId, String turnId) {
        this.memories = memories;
        this.scope = scope;
        this.message = message;
        this.actor = actor;
        this.conversationId = conversationId;
        this.turnId = turnId;
    }

    synchronized ToolReply execute(CopilotToolArguments args) {
        if (actor == null) return ToolReply.error("AUTHENTICATION_REQUIRED");
        String operation = args.text("operation");
        if ("LIST".equals(operation)) {
            var items = memories.retrieve(actor, scope, null).stream().map(CopilotMemoryView::from).map(CopilotPreferenceTool::row).toList();
            return reply("READY", items.isEmpty() ? "当前没有已保存的偏好" : "已保存的长期记忆", items, null);
        }
        if (conversationId == null || turnId == null) return ToolReply.error("MEMORY_CONFIRMATION_REQUIRED");
        String normalized = CopilotQueryNormalizer.forMatching(message).replaceAll("(?<=\\p{IsHan})\\s+|\\s+(?=\\p{IsHan})", "");
        if (!("SAVE".equals(operation) ? SAVE : FORGET).matcher(normalized).find()) {
            return ToolReply.error("MEMORY_CONFIRMATION_REQUIRED");
        }
        if (mutationResult != null) {
            return operation.equals(mutationOperation) ? mutationResult : ToolReply.error("MEMORY_MUTATION_ALREADY_APPLIED");
        }
        var decision = "SAVE".equals(operation) ? memories.decideWrite(message, scope, null, actor)
                : memories.decideForget(message, scope, null, actor);
        if (!decision.accepted()) {
            return new ToolReply(Map.of("status", decision.code(), "message",
                    decision.question() == null ? "该记忆请求未通过安全或权限校验。" : decision.question()), null, true);
        }
        var memory = "SAVE".equals(operation) ? memories.write(decision.candidate(), actor, conversationId, turnId)
                : memories.revoke(decision.target(), actor);
        mutationOperation = operation;
        mutationResult = reply("SAVE".equals(operation) ? "SAVED" : "REVOKED",
                "SAVE".equals(operation) ? "已保存长期偏好" : "已撤销长期偏好",
                List.of(row(CopilotMemoryView.from(memory))), memory.getMemoryId());
        return mutationResult;
    }

    static Map<String, Object> row(CopilotMemoryView memory) {
        String description = memory.getDisplayText() == null ? memory.getValue() : memory.getDisplayText();
        return Map.of("label", "偏好", "value", description == null ? "" : description);
    }

    private ToolReply reply(String status, String title, List<Map<String, Object>> items, String memoryId) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("items", items);
        if (memoryId != null) result.put("memoryId", memoryId);
        return new ToolReply(result, Map.of("componentType", "MemoryPreferenceCard", "title", title,
                "status", memoryId == null ? "ready" : "completed", "data", Map.of("items", items),
                "blockKey", memoryId == null ? "memory:preferences" : "memory:" + status + ":" + memoryId), false);
    }
}

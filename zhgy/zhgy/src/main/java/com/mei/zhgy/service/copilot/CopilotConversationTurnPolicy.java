package com.mei.zhgy.service.copilot;

import java.util.Set;

/** Keeps routing/status events out of user-visible history and model context. */
public final class CopilotConversationTurnPolicy {
    private static final Set<String> LEGACY_INTERNAL_MESSAGES = Set.of(
            "已识别为普通问答，正在读取当前页面上下文并调用快速回答模型。",
            "动作请求已解析；下一步仍需人工批准，未直接创建 Operations 任务。",
            "已识别为今日优先事项查询，正在读取农场诊断、天气与 Operations 数据。",
            "已识别为农场状态查询，正在汇总地块、天气、诊断和待处理任务。",
            "已识别为地块状态查询，正在读取地块、天气和近期农情。",
            "已识别为农业知识检索，正在查询当前知识库并返回来源。",
            "已识别为历史病例检索，将基于现场图片和专家确认索引返回相似案例。",
            "已识别为诊断查询，正在读取已保存 Diagnosis 与 Evidence。",
            "已准备 AI 调查入口；创建 Diagnosis 和 Agent Run 前仍需确认地块。",
            "请求已完成上下文解析，正在准备可验证的数据结果。"
    );

    private CopilotConversationTurnPolicy() { }

    public static boolean isVisibleConversationTurn(CopilotConversationTurn turn) {
        if (turn == null) return false;
        if ("SYSTEM_EVENT".equalsIgnoreCase(turn.getRole())) return false;
        return !("ASSISTANT".equalsIgnoreCase(turn.getRole())
                && LEGACY_INTERNAL_MESSAGES.contains(turn.getMessage()));
    }
}

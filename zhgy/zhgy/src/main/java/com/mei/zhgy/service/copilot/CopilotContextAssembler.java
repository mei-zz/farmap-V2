package com.mei.zhgy.service.copilot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Builds the bounded context handed to routing; it never expands into a full transcript. */
@Component
public class CopilotContextAssembler {
    private final int maxRecentTurns;
    private final int maxContextChars;
    private final CopilotLongTermMemoryService longTermMemory;

    @Autowired
    public CopilotContextAssembler(
            @Value("${FARMAP_COPILOT_MEMORY_MAX_RECENT_TURNS:10}") int maxRecentTurns,
            @Value("${FARMAP_COPILOT_MEMORY_MAX_CONTEXT_CHARS:4000}") int maxContextChars,
            CopilotLongTermMemoryService longTermMemory) {
        this.maxRecentTurns = Math.max(1, Math.min(maxRecentTurns, 10));
        this.maxContextChars = Math.max(1000, Math.min(maxContextChars, 4000));
        this.longTermMemory = longTermMemory;
    }

    public CopilotContextAssembler(int maxRecentTurns, int maxContextChars) {
        this.maxRecentTurns = Math.max(1, Math.min(maxRecentTurns, 10));
        this.maxContextChars = Math.max(1000, Math.min(maxContextChars, 4000));
        this.longTermMemory = null;
    }

    public AssembledContext assemble(CopilotConversationSnapshot snapshot, CopilotPageContext pageContext) {
        List<CopilotConversationTurn> selected = new ArrayList<>();
        int used = 0;
        List<CopilotConversationTurn> source = snapshot.getRecentTurns() == null ? List.of() : snapshot.getRecentTurns().stream()
                .filter(CopilotConversationTurnPolicy::isVisibleConversationTurn)
                .toList();
        int from = Math.max(0, source.size() - maxRecentTurns);
        for (int index = from; index < source.size(); index++) {
            CopilotConversationTurn turn = source.get(index);
            String bounded = bound(turn.getMessage(), Math.min(4000, maxContextChars));
            if (used + bounded.length() > maxContextChars && !selected.isEmpty()) break;
            CopilotConversationTurn copy = CopilotConversationTurn.builder().turnId(turn.getTurnId()).conversationId(turn.getConversationId())
                    .role(turn.getRole()).message(bounded).intent(turn.getIntent()).traceId(turn.getTraceId())
                    .clientMessageId(turn.getClientMessageId()).resultJson(turn.getResultJson())
                    .responseMetadata(turn.getResponseMetadata()).createdAt(turn.getCreatedAt()).build();
            selected.add(copy);
            used += bounded.length();
        }
        CopilotStableContext active = snapshot.getActiveContext() == null ? new CopilotStableContext() : snapshot.getActiveContext().copy();
        List<CopilotMemoryView> longTerm = longTermMemory == null ? List.of() : longTermMemory.retrieve(snapshot.getUserId(), active, pageContext).stream().map(CopilotMemoryView::from).toList();
        return new AssembledContext(active, selected, snapshot.getSummary(), snapshot.getPendingClarification(), pageContext, longTerm, maxRecentTurns, maxContextChars);
    }

    private String bound(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record AssembledContext(CopilotStableContext activeContext,
                                   List<CopilotConversationTurn> recentTurns,
                                   String conversationSummary,
                                   CopilotPendingClarification pendingClarification,
                                   CopilotPageContext currentPageContext,
                                   List<CopilotMemoryView> relevantLongTermMemories,
                                   int maxRecentTurns,
                                   int maxContextChars) {}
}

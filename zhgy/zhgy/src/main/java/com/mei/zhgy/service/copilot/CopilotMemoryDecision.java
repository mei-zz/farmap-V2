package com.mei.zhgy.service.copilot;

import java.util.List;

public record CopilotMemoryDecision(CopilotMemoryCandidate candidate,
                                    CopilotLongTermMemory target,
                                    List<CopilotLongTermMemory> memories,
                                    String question,
                                    String code) {
    public static CopilotMemoryDecision candidate(CopilotMemoryCandidate candidate) { return new CopilotMemoryDecision(candidate, null, List.of(), null, null); }
    public static CopilotMemoryDecision memories(List<CopilotLongTermMemory> memories) { return new CopilotMemoryDecision(null, null, memories, null, null); }
    public static CopilotMemoryDecision target(CopilotLongTermMemory target) { return new CopilotMemoryDecision(null, target, List.of(), null, null); }
    public static CopilotMemoryDecision question(String question) { return new CopilotMemoryDecision(null, null, List.of(), question, "CLARIFICATION_REQUIRED"); }
    public static CopilotMemoryDecision reject(String code) { return new CopilotMemoryDecision(null, null, List.of(), null, code); }
    public boolean accepted() { return code == null; }
}

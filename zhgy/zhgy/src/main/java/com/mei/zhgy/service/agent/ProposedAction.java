package com.mei.zhgy.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposedAction {
    private String id;
    private String type;
    private String title;
    private String detail;
    private String toolName;
    private boolean approvalRequired;
    private ApprovalState approvalState;
    @Builder.Default private Map<String, Object> payload = new LinkedHashMap<>();
}

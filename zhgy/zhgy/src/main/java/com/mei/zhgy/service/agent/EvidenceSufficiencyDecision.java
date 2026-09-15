package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Persisted decision record for the deterministic pre-write evidence gate. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceSufficiencyDecision {
    private EvidenceSufficiencyStatus status;
    private boolean allowsWrite;
    @Builder.Default private List<String> reasonCodes = new ArrayList<>();
    @Builder.Default private List<String> availableEvidenceIds = new ArrayList<>();
    @Builder.Default private List<String> missingRequiredModalities = new ArrayList<>();
    @Builder.Default private List<String> unavailableDependencies = new ArrayList<>();
    private String summary;
}

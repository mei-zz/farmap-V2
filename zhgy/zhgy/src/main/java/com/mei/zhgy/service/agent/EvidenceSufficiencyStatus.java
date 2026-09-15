package com.mei.zhgy.service.agent;

/** Deterministic evidence gate outcomes used before an agent can propose a write action. */
public enum EvidenceSufficiencyStatus {
    SUFFICIENT,
    DEGRADED,
    INSUFFICIENT
}

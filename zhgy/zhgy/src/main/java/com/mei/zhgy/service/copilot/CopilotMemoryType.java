package com.mei.zhgy.service.copilot;

public enum CopilotMemoryType {
    USER_PREFERENCE,
    FARM_PREFERENCE,
    FIELD_PREFERENCE,
    OPERATING_CONSTRAINT,
    COMMUNICATION_PREFERENCE,
    ORCHARD_PROFILE;

    public boolean isProfileFact() {
        return this == ORCHARD_PROFILE;
    }

    public boolean isSelfReported() {
        return this == ORCHARD_PROFILE;
    }

    /** User-level preference or operating constraint, never an orchard profile fact. */
    public boolean isUserScopedPreference() {
        return this == USER_PREFERENCE || this == OPERATING_CONSTRAINT;
    }
}

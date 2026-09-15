package com.mei.zhgy.service.copilot.intent;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Non-authoritative entities extracted by the intent model for later layers. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentSlots(String crop, String stage, String fieldId, String time,
                          String organ, String disease, String action) { }

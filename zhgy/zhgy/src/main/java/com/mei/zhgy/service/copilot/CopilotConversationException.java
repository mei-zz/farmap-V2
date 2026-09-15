package com.mei.zhgy.service.copilot;

import lombok.Getter;

@Getter
public class CopilotConversationException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public CopilotConversationException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }
}

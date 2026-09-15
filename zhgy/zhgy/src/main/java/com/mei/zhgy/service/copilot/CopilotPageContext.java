package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** Client page hints. They are never trusted until resolved by the server. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopilotPageContext extends CopilotStableContext {
    private String route;
}

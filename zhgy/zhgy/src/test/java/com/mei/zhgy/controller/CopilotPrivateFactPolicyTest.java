package com.mei.zhgy.controller;

import com.mei.zhgy.service.copilot.CopilotRouteService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CopilotPrivateFactPolicyTest {
    @Test
    void requiresFieldReferenceForOrdinaryFactNouns() throws Exception {
        CopilotController controller = new CopilotController(mock(CopilotRouteService.class));
        Method policy = CopilotController.class.getDeclaredMethod("asksPrivateFacts", String.class);
        policy.setAccessible(true);

        assertFalse((Boolean) policy.invoke(controller, "柑橘花期适宜温度是多少"));
        assertFalse((Boolean) policy.invoke(controller, "土壤pH多少算酸"));
        assertTrue((Boolean) policy.invoke(controller, "当前地块温度是多少"));
        assertTrue((Boolean) policy.invoke(controller, "我的产量为什么下降"));
        assertTrue((Boolean) policy.invoke(controller, "上周打过药吗"));
    }
}

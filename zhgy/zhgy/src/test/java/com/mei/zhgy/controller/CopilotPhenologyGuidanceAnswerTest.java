package com.mei.zhgy.controller;

import com.mei.zhgy.service.copilot.CopilotApplicationService;

import com.mei.zhgy.service.copilot.CopilotRouteService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CopilotPhenologyGuidanceAnswerTest {
    @Test
    void compoundPhenologyQuestionReachesModelInsteadOfReturningOnlyTheStageFact() throws Exception {
        CopilotController controller = new CopilotController(new CopilotApplicationService(mock(CopilotRouteService.class)));
        Method factsFromContext = CopilotApplicationService.class.getDeclaredMethod("factsFromContext", Map.class);
        factsFromContext.setAccessible(true);
        Object facts = factsFromContext.invoke(controller.application, Map.of(
                "currentDate", "2026年9月23日 星期三",
                "crop", "plum",
                "cropLabel", "脆李",
                "currentGrowthStage", "post_harvest",
                "currentGrowthStageLabel", "采后恢复期",
                "nextGrowthStage", "dormancy",
                "nextGrowthStageLabel", "休眠期",
                "daysUntilNextPhenologyStage", 59,
                "orderedPhenologyStages", List.of("开花期", "果实发育期", "成熟期", "采后恢复期", "休眠期"),
                "fieldFactsStatus", "READY"
        ));

        Method deterministicAnswer = CopilotApplicationService.class.getDeclaredMethod("deterministicPhenologyAnswer", String.class, facts.getClass());
        deterministicAnswer.setAccessible(true);
        Object compound = deterministicAnswer.invoke(controller.application, "下一个物候期是什么，有哪些农事指导", facts);
        Object naturalCompound = deterministicAnswer.invoke(controller.application, "下一个物候期是什么，接下来要做什么", facts);
        Object pureFact = deterministicAnswer.invoke(controller.application, "下一个物候期是什么", facts);

        assertNull(compound);
        assertNull(naturalCompound);
        assertTrue(String.valueOf(pureFact).contains("下一物候期是休眠期"));
    }
}

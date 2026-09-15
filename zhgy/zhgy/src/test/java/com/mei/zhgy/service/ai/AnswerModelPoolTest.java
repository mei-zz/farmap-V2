package com.mei.zhgy.service.ai;

import com.mei.zhgy.config.AnswerPoolProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerModelPoolTest {
    private final ModelAvailabilityRegistry availability =
            new ModelAvailabilityRegistry(new ModelCapabilityRegistry());

    @Test
    void randomUsesInjectedSourceAndIsApproximatelyUniform() {
        AnswerPoolProperties properties = enabled(List.of("a", "b", "c", "d"));
        AtomicInteger calls = new AtomicInteger();
        AnswerModelPool pool = new AnswerModelPool(properties, availability,
                () -> (calls.getAndIncrement() / 4) % 4);

        int[] selected = new int[4];
        for (int i = 0; i < 400; i++) {
            List<String> ordered = pool.orderedCandidates(AiTaskType.COPILOT);
            selected[List.of("a", "b", "c", "d").indexOf(ordered.get(0))]++;
        }
        for (int count : selected) assertEquals(100, count);
        assertEquals(4, pool.poolSize());
        assertEquals("random", pool.selectionStrategy());
    }

    @Test
    void roundRobinIsCircularAndOrdered() {
        AnswerPoolProperties properties = enabled(List.of("a", "b", "c"));
        properties.setSelectionStrategy("round_robin");
        AnswerModelPool pool = new AnswerModelPool(properties, availability, () -> 0);

        assertIterableEquals(List.of("a", "b", "c"), pool.orderedCandidates(AiTaskType.COPILOT));
        assertIterableEquals(List.of("b", "c", "a"), pool.orderedCandidates(AiTaskType.COPILOT));
        assertIterableEquals(List.of("c", "a", "b"), pool.orderedCandidates(AiTaskType.COPILOT));
        assertIterableEquals(List.of("a", "b", "c"), pool.orderedCandidates(AiTaskType.COPILOT));
    }

    @Test
    void disabledUnmatchedAndUnavailablePoolReturnsEmpty() {
        AnswerPoolProperties properties = enabled(List.of("a", "b"));
        AnswerModelPool pool = new AnswerModelPool(properties, availability, () -> 0);
        assertFalse(pool.orderedCandidates(AiTaskType.IMAGE_UNDERSTANDING).iterator().hasNext());

        properties.setEnabled(false);
        assertTrue(pool.orderedCandidates(AiTaskType.COPILOT).isEmpty());

        properties.setEnabled(true);
        properties.setModels(List.of("a"));
        availability.markUnavailable("a", "permission_denied");
        assertTrue(pool.orderedCandidates(AiTaskType.COPILOT).isEmpty());
    }

    @Test
    void propertiesNormalizeStrategyBoundsAndModelList() {
        AnswerPoolProperties properties = new AnswerPoolProperties();
        properties.setSelectionStrategy("something_else");
        properties.setSamePoolFallbackN(99);
        properties.setModels(List.of(" a ", "", "a", "b"));

        assertEquals("random", properties.getSelectionStrategy());
        assertEquals(8, properties.getSamePoolFallbackN());
        assertIterableEquals(List.of("a", "b"), properties.getModels());
    }

    private AnswerPoolProperties enabled(List<String> models) {
        AnswerPoolProperties properties = new AnswerPoolProperties();
        properties.setModels(models);
        properties.setEnabled(true);
        return properties;
    }
}

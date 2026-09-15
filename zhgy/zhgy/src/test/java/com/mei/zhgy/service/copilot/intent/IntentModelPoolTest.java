package com.mei.zhgy.service.copilot.intent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentModelPoolTest {
    @Test
    void weightedRoutingStaysNearConfiguredSeventyThirtySplit() {
        IntentModelProperties properties = properties();
        Random random = new Random(20260914L);
        IntentModelPool pool = new IntentModelPool(properties, random::nextDouble);
        int primary = 0;
        int flash = 0;
        for (int i = 0; i < 20_000; i++) {
            IntentModelPool.ModelChoice first = pool.primaryCandidates().get(0);
            if ("PRIMARY".equals(first.tier())) primary++; else if ("FLASH".equals(first.tier())) flash++;
        }
        double primaryRate = (double) primary / (primary + flash);
        assertTrue(primaryRate > 0.68D && primaryRate < 0.72D, "primary rate=" + primaryRate);
        assertTrue(flash > 0);
    }

    @Test
    void strongPoolReturnsAtMostOneRetryCandidate() {
        IntentModelProperties properties = properties();
        IntentModelPool pool = new IntentModelPool(properties, () -> 0.1D);
        List<IntentModelPool.ModelChoice> choices = pool.strongCandidates();
        assertEquals(2, choices.size());
        assertEquals("STRONG", choices.get(0).tier());
        assertTrue(choices.stream().map(IntentModelPool.ModelChoice::model).distinct().count() == 2);
    }

    @Test
    void primaryModeAlwaysStartsWithDedicatedModelAndOnlyUsesFlashAsFallback() {
        IntentModelProperties properties = properties();
        properties.setRoutingMode("primary");
        IntentModelPool pool = new IntentModelPool(properties, () -> 0.99D);

        List<IntentModelPool.ModelChoice> choices = pool.primaryCandidates();

        assertEquals("PRIMARY", choices.get(0).tier());
        assertEquals("primary-test", choices.get(0).model());
        assertEquals("FLASH", choices.get(1).tier());
    }

    private IntentModelProperties properties() {
        IntentModelProperties properties = new IntentModelProperties();
        properties.setPrimaryModel("primary-test");
        properties.setFlashPool(new ArrayList<>(List.of("flash-a", "flash-b", "flash-c")));
        properties.setStrongPool(new ArrayList<>(List.of("strong-a", "strong-b")));
        properties.setRoutingMode("weighted");
        properties.setPrimaryWeight(0.70D);
        return properties;
    }
}

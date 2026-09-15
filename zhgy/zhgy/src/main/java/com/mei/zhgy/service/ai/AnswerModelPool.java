package com.mei.zhgy.service.ai;

import com.mei.zhgy.config.AnswerPoolProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/** Ordered model candidates for plain-text answer tasks. */
@Component
public class AnswerModelPool {
    private final AnswerPoolProperties properties;
    private final ModelAvailabilityRegistry availabilityRegistry;
    private final IntSupplier randomSource;
    private final AtomicInteger roundRobinCursor = new AtomicInteger();

    @Autowired
    public AnswerModelPool(AnswerPoolProperties properties, ModelAvailabilityRegistry availabilityRegistry) {
        this(properties, availabilityRegistry, () -> ThreadLocalRandom.current().nextInt());
    }

    public AnswerModelPool(AnswerPoolProperties properties,
                           ModelAvailabilityRegistry availabilityRegistry,
                           IntSupplier randomSource) {
        this.properties = properties;
        this.availabilityRegistry = availabilityRegistry;
        this.randomSource = randomSource == null ? () -> ThreadLocalRandom.current().nextInt() : randomSource;
    }

    /**
     * Returns a fresh ordered list. The registry exposes a read-only status
     * query, so models already marked unavailable are omitted when enabled.
     */
    public List<String> orderedCandidates(AiTaskType taskType) {
        if (!properties.isEnabled() || !properties.appliesTo(taskType)) return Collections.emptyList();
        List<String> eligible = new ArrayList<>();
        for (String model : properties.getModels()) {
            if (model == null || model.isBlank()) continue;
            if (properties.isSkipUnavailable()
                    && availabilityRegistry != null
                    && "unavailable".equalsIgnoreCase(availabilityRegistry.status(model))) {
                continue;
            }
            eligible.add(model);
        }
        if (eligible.isEmpty()) return Collections.emptyList();

        if ("round_robin".equals(properties.getSelectionStrategy())) {
            int first = Math.floorMod(roundRobinCursor.getAndIncrement(), eligible.size());
            List<String> ordered = new ArrayList<>(eligible.size());
            for (int offset = 0; offset < eligible.size(); offset++) {
                ordered.add(eligible.get((first + offset) % eligible.size()));
            }
            return List.copyOf(ordered);
        }

        int first = Math.floorMod(randomSource.getAsInt(), eligible.size());
        String selected = eligible.remove(first);
        // Fisher-Yates uses the injected source and does not allocate a new
        // random generator for each request.
        for (int index = eligible.size() - 1; index > 0; index--) {
            int swap = Math.floorMod(randomSource.getAsInt(), index + 1);
            Collections.swap(eligible, index, swap);
        }
        eligible.add(0, selected);
        return List.copyOf(eligible);
    }

    public int poolSize() {
        return properties.getModels().size();
    }

    public String selectionStrategy() {
        return properties.getSelectionStrategy();
    }

    public int samePoolFallbackN() {
        return properties.getSamePoolFallbackN();
    }
}

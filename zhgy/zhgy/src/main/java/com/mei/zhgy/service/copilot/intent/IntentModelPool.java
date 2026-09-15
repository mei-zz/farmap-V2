package com.mei.zhgy.service.copilot.intent;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Weighted primary routing plus bounded same-tier retry selection. */
@Component
public class IntentModelPool {
    private final IntentModelProperties properties;
    private final DoubleSupplier random;

    @Autowired
    public IntentModelPool(IntentModelProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    public IntentModelPool(IntentModelProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random == null ? () -> ThreadLocalRandom.current().nextDouble() : random;
    }

    public List<ModelChoice> primaryCandidates() {
        List<ModelChoice> choices = new ArrayList<>();
        boolean hasPrimary = properties != null && properties.getPrimaryModel() != null && !properties.getPrimaryModel().isBlank();
        List<String> flash = properties == null ? List.of() : properties.getFlashPool();
        if (!hasPrimary && flash.isEmpty()) return choices;
        boolean primaryFirst = "primary".equalsIgnoreCase(properties.getRoutingMode())
                || (hasPrimary && !flash.isEmpty() && sample() < properties.getPrimaryWeight());
        if (primaryFirst && hasPrimary) {
            choices.add(new ModelChoice(properties.getPrimaryModel(), "PRIMARY", true));
            if (!flash.isEmpty()) choices.add(new ModelChoice(flash.get(index(flash.size())), "FLASH", false));
        } else if (!flash.isEmpty()) {
            int first = index(flash.size());
            choices.add(new ModelChoice(flash.get(first), "FLASH", false));
            if (flash.size() > 1) choices.add(new ModelChoice(flash.get(nextIndex(flash.size(), first)), "FLASH", false));
            else if (hasPrimary) choices.add(new ModelChoice(properties.getPrimaryModel(), "PRIMARY", true));
        } else {
            choices.add(new ModelChoice(properties.getPrimaryModel(), "PRIMARY", true));
        }
        return distinct(choices);
    }

    public List<ModelChoice> strongCandidates() {
        List<String> strong = properties == null ? List.of() : properties.getStrongPool();
        if (strong.isEmpty()) return List.of();
        int first = index(strong.size());
        List<ModelChoice> choices = new ArrayList<>();
        choices.add(new ModelChoice(strong.get(first), "STRONG", false));
        if (strong.size() > 1) choices.add(new ModelChoice(strong.get(nextIndex(strong.size(), first)), "STRONG", false));
        return distinct(choices);
    }

    /** Deterministic helper used by weighted-routing tests. */
    public String tierForSample(double sample) {
        if (properties == null) return "NONE";
        if ("primary".equalsIgnoreCase(properties.getRoutingMode())) return "PRIMARY";
        return sample >= 0D && sample < properties.getPrimaryWeight() ? "PRIMARY" : "FLASH";
    }

    private int index(int size) { return Math.min(size - 1, Math.max(0, (int) Math.floor(sample() * size))); }
    private int nextIndex(int size, int current) { return (current + 1 + index(size - 1)) % size; }
    private double sample() { double value = random.getAsDouble(); return Double.isFinite(value) ? Math.max(0D, Math.min(0.999999999D, value)) : 0D; }

    private List<ModelChoice> distinct(List<ModelChoice> choices) {
        return choices.stream().filter(choice -> choice.model() != null && !choice.model().isBlank()).distinct().toList();
    }

    public record ModelChoice(String model, String tier, boolean specialized) { }
}

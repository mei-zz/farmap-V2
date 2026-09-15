package com.mei.zhgy.service.reference;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Resolves user disease wording without introducing a second crop/router dependency. */
public class DiseaseCatalog {
    private final List<DiseaseDefinition> diseases = List.of(
            new DiseaseDefinition("bacterial_spot", List.of("细菌性穿孔病", "细菌性穿孔", "穿孔病", "穿孔")),
            new DiseaseDefinition("brown_rot", List.of("褐腐病", "褐腐")),
            new DiseaseDefinition("pocket_disease", List.of("李袋果病", "袋果病", "李袋果", "袋果")),
            new DiseaseDefinition("pox_virus", List.of("李痘病毒", "李痘", "痘病毒")),
            new DiseaseDefinition("rust", List.of("锈病", "锈"))
    );

    public String resolve(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) return null;
        return diseases.stream()
                .flatMap(disease -> disease.aliases().stream()
                        .map(alias -> new AliasMatch(disease.key(), normalize(alias))))
                .filter(match -> !match.alias().isBlank() && normalized.contains(match.alias()))
                .max(Comparator.comparingInt(match -> match.alias().length()))
                .map(AliasMatch::key)
                .orElse(null);
    }

    public String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-—–/·（）()，,。:：;；]+", "")
                .replace('ｗ', 'w').replace('Ｗ', 'w');
    }

    private record DiseaseDefinition(String key, List<String> aliases) { }

    private record AliasMatch(String key, String alias) { }
}

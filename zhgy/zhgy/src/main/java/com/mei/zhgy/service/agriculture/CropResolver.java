package com.mei.zhgy.service.agriculture;

import com.mei.zhgy.service.copilot.CopilotQueryNormalizer;
import org.springframework.stereotype.Service;

import java.util.List;

/** Converts user/legacy crop text to one server-owned code, or UNKNOWN. */
@Service
public class CropResolver {
    private final CropCatalog catalog;

    public CropResolver() { this(new CropCatalog()); }
    public CropResolver(CropCatalog catalog) { this.catalog = catalog == null ? new CropCatalog() : catalog; }

    /** Returns the server-owned crop directory for public fallback summaries. */
    public List<CropDefinition> supportedCrops() { return catalog.all(); }

    public ResolvedCrop resolve(String raw) {
        String normalized = normalize(raw);
        if (normalized.isBlank()) return new ResolvedCrop(null, null, raw, null, "MISSING");
        return catalog.all().stream()
                .filter(crop -> crop.aliases().stream().map(this::normalize).anyMatch(normalized::equals))
                .findFirst()
                .or(() -> catalog.all().stream()
                        .flatMap(crop -> crop.aliases().stream()
                                .map(alias -> new AliasMatch(crop, normalize(alias))))
                        .filter(match -> !match.alias().isBlank() && normalized.contains(match.alias()))
                        .max(java.util.Comparator.comparingInt(match -> match.alias().length()))
                        .map(AliasMatch::crop))
                .map(crop -> new ResolvedCrop(crop.code(), crop.name(), raw, crop.phenologyCalendarId(), "READY"))
                .orElse(new ResolvedCrop(null, null, raw, null, "UNKNOWN_CROP"));
    }

    public boolean containsKnownAlias(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String normalized = normalize(raw);
        return catalog.all().stream().anyMatch(crop -> crop.aliases().stream().map(this::normalize).anyMatch(normalized::contains));
    }

    public String normalize(String value) {
        return CopilotQueryNormalizer.compactForMatching(value);
    }

    private record AliasMatch(CropDefinition crop, String alias) { }

    public record ResolvedCrop(String code, String name, String raw, String phenologyCalendarId, String status) {
        public boolean ready() { return "READY".equals(status); }
        public boolean unknown() { return "UNKNOWN_CROP".equals(status); }
    }
}

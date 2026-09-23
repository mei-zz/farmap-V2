package com.mei.zhgy.service.copilot;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalizes user text for deterministic matching without changing the text
 * that is shown to the user or sent to the answer model.
 *
 * The replacement table is intentionally small and domain-owned. It covers
 * common IME/voice-input slips that are unambiguous in FarMap, while unknown
 * words remain unknown instead of being guessed as another crop or disease.
 */
public final class CopilotQueryNormalizer {
    private CopilotQueryNormalizer() { }

    public static String forMatching(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                // Keep word boundaries for English routing phrases and
                // compact punctuation runs to one separator.
                .replaceAll("[\\s_\\-—–/·（）()，,。:：;；！？!?、]+", " ")
                .trim();
        return normalized
                // Common voice/IME slips for terms already present in the
                // server-owned crop and phenology vocabulary.
                .replace("翠李", "脆李")
                .replace("脆里", "脆李")
                .replace("果實", "果实")
                .replace("果使", "果实")
                // Common voice/IME homophone slip for “膨大期”. Keep this
                // as a domain-owned alias so the existing phenology router
                // and reference-image resolver share the same correction.
                .replace("棚大期", "膨大期")
                .replace("膨大其", "膨大期")
                .replace("膨果其", "膨果期");
    }

    /** Compact form for Chinese crop/disease/stage aliases. */
    public static String compactForMatching(String value) {
        return forMatching(value).replace(" ", "");
    }
}

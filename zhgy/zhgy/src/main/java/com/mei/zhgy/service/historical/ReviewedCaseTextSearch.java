package com.mei.zhgy.service.historical;

import com.mei.zhgy.service.copilot.CopilotQueryNormalizer;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.expert.ExpertReviewV2Service;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Text recall over cases whose actual outcomes have been reviewed. */
@Service
public class ReviewedCaseTextSearch {
    private final HistoricalCaseCandidateService candidates;
    private final DiagnosisPersistenceService diagnoses;
    private final ExpertReviewV2Service reviews;

    public ReviewedCaseTextSearch(HistoricalCaseCandidateService candidates, DiagnosisPersistenceService diagnoses,
                                   ExpertReviewV2Service reviews) {
        this.candidates = candidates;
        this.diagnoses = diagnoses;
        this.reviews = reviews;
    }

    public List<Map<String, Object>> search(Long actor, Integer farmId, String fieldId, String stage, String term, int limit) {
        if (actor == null || farmId == null) return List.of();
        Set<String> query = terms(term);
        if (query.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (var candidate : candidates.list(actor, "ELIGIBLE", 64)) {
            String diagnosisId = string(candidate.get("diagnosisId"));
            if (diagnosisId.isEmpty()) continue;
            var diagnosis = diagnoses.findRecord(diagnosisId, actor).orElse(null);
            if (diagnosis == null || !farmId.equals(diagnosis.getFarmId())
                    || fieldId != null && !fieldId.equals(diagnosis.getFieldId())) continue;
            if (stage != null) {
                var metadata = diagnosis.getPayload() == null ? null : diagnosis.getPayload().getMetadata();
                if (metadata == null || !stage.equalsIgnoreCase(string(metadata.get("observedGrowthStage")))) continue;
            }
            String summary = bounded(diagnosis.getSummary(), 640);
            long score = terms(summary).stream().filter(query::contains).count();
            if (score > 0) hits.add(new Hit(candidate, summary, score));
        }
        return hits.stream().sorted(Comparator.comparingLong(Hit::score).reversed())
                .limit(Math.max(1, Math.min(10, limit))).map(hit -> project(actor, hit)).toList();
    }

    private Map<String, Object> project(Long actor, Hit hit) {
        var candidate = hit.candidate();
        String diagnosisId = string(candidate.get("diagnosisId"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", string(candidate.get("candidateId")));
        result.put("diagnosisId", diagnosisId);
        result.put("summary", hit.summary());
        result.put("learningValue", candidate.get("learningValue"));
        result.put("source", "FarMap 专家复核的作业结果");
        // Historical expert endorsement is tied to the recorded recommendation and outcome.
        if ("SUCCESS_CASE".equals(candidate.get("learningValue"))) {
            var review = reviews.get(string(candidate.get("reviewId")), actor);
            if (review != null && "APPROPRIATE".equals(review.get("recommendationJudgment"))
                    && "SUCCESSFUL".equals(review.get("outcomeJudgment"))) {
                String recommendation = string(review.get("recommendationId"));
                diagnoses.findRecommendation(diagnosisId, recommendation, actor).ifPresent(rec ->
                        result.put("historicalRecommendation", bounded(rec.getDetail(), 900)));
            }
        }
        return result;
    }

    private static Set<String> terms(String input) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : CopilotQueryNormalizer.forMatching(input).split("\\s+")) {
            if (word.isBlank()) continue;
            if (word.codePoints().allMatch(c -> c < 128)) words.add(word);
            else {
                int[] letters = word.codePoints().toArray();
                for (int i = 1; i < letters.length; i++) words.add(new String(letters, i - 1, 2));
            }
        }
        return words;
    }

    private static String bounded(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private record Hit(Map<String, Object> candidate, String summary, long score) {}
}

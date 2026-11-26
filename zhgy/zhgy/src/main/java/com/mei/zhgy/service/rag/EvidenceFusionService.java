package com.mei.zhgy.service.rag;

import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Calibrates scores in their source scale before applying modality and source weights. */
@Service
public class EvidenceFusionService {
    @Value("${farmap.ai.retrieval.weights.camera:1.0}")
    private double cameraWeight = 1.0;
    @Value("${farmap.ai.retrieval.weights.weather:0.95}")
    private double weatherWeight = 0.95;
    @Value("${farmap.ai.retrieval.weights.knowledge:1.0}")
    private double knowledgeWeight = 1.0;
    @Value("${farmap.ai.retrieval.weights.historical-case:1.1}")
    private double historicalWeight = 1.1;
    @Value("${farmap.ai.retrieval.weights.soil:1.0}")
    private double soilWeight = 1.0;
    @Value("${farmap.ai.retrieval.weights.spatial:0.9}")
    private double spatialWeight = 0.9;
    @Value("${farmap.ai.retrieval.weights.accumulated-temperature:0.92}")
    private double accumulatedTemperatureWeight = 0.92;
    @Value("${farmap.ai.retrieval.weights.satellite:0.85}")
    private double satelliteWeight = 0.85;
    @Value("${farmap.ai.retrieval.cosine-minimum:0.45}")
    private double cosineMinimum = 0.45;
    private static final ZoneId FARM_ZONE = ZoneId.of("Asia/Shanghai");

    private record Rating(DiagnosisEvidenceVO evidence, double normalized, double reliability, double weighted, String calibration) {}

    public List<DiagnosisEvidenceVO> fuse(List<DiagnosisEvidenceVO> evidence) {
        if (evidence == null) return List.of();
        Instant now = Instant.now();
        List<Rating> ratings = evidence.stream().filter(java.util.Objects::nonNull).map(item -> rate(item, now))
                .sorted(Comparator.comparingDouble(Rating::weighted).reversed()).toList();
        List<DiagnosisEvidenceVO> ranked = new ArrayList<>(ratings.size());
        for (int index = 0; index < ratings.size(); index++) {
            Rating rating = ratings.get(index);
            DiagnosisEvidenceVO item = rating.evidence();
            Map<String, Object> metadata = item.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(item.getMetadata());
            metadata.put("retrievalRank", index + 1);
            metadata.put("normalizedScore", rating.normalized());
            metadata.put("scoreCalibration", rating.calibration());
            metadata.put("fusedScore", rating.weighted());
            metadata.put("sourceReliability", rating.reliability());
            item.setMetadata(metadata);
            ranked.add(item);
        }
        return ranked;
    }

    private Rating rate(DiagnosisEvidenceVO item, Instant now) {
        String modality = string(item.getModality());
        Map<String, Object> metadata = item.getMetadata() == null ? Map.of() : item.getMetadata();
        double normalized;
        String calibration;
        Double raw = finite(item.getScore());
        Double rerank = finite(metadata.get("rerankScore"));
        if (rerank == null && item.getSource() != null) rerank = finite(item.getSource().getRerankScore());
        Double rrf = finite(metadata.get("rrfScore"));
        Double rrfMax = finite(metadata.get("rrfUpperBound"));
        if ("knowledge".equals(modality) && rerank != null && rerank >= 0D && rerank <= 1D) {
            normalized = rerank;
            calibration = "rerank-probability";
        } else if ("knowledge".equals(modality) && rrf != null && rrf > 0D && rrfMax != null && rrfMax > 0D) {
            normalized = 0.45D + 0.50D * clamp(rrf / rrfMax);
            calibration = "rrf-theoretical-maximum";
        } else if (isEnvironment(modality)) {
            normalized = environmentScore(item, metadata, now);
            calibration = "environment-freshness-completeness";
        } else if ("VISUAL_SIMILARITY".equals(metadata.get("retrievalType")) || "local-clip-milvus".equals(metadata.get("sourceMode"))) {
            Double similarity = finite(metadata.get("similarity"));
            if (similarity == null) similarity = finite(metadata.get("rankingScore"));
            if (similarity == null) similarity = raw;
            double threshold = Math.min(0.99D, clamp(cosineMinimum));
            normalized = similarity == null || similarity < threshold ? 0D
                    : 0.45D + 0.50D * clamp((similarity - threshold) / (1D - threshold));
            calibration = "vector-threshold-min-max";
        } else {
            normalized = raw == null ? 0D : clamp(raw);
            calibration = "bounded-native";
        }
        double reliability = sourceReliability(item);
        return new Rating(item, normalized, reliability, normalized * modalityWeight(modality) * reliability, calibration);
    }

    private double environmentScore(DiagnosisEvidenceVO item, Map<String, Object> metadata, Instant now) {
        var source = item.getSource();
        String timestamp = source == null ? "" : string(source.getTimestamp());
        if (timestamp.isBlank()) timestamp = string(metadata.get("dataDate"));
        Instant observed = parseTime(timestamp);
        double freshness = observed == null || observed.isAfter(now) ? 0D
                : Math.max(0D, 1D - Duration.between(observed, now).toHours() / 168D);
        String[] expected = switch (string(item.getModality())) {
            case "weather" -> new String[]{"rainfallForecast", "rainfall14d"};
            case "soil" -> new String[]{"scope", "sensor"};
            case "spatial" -> new String[]{"latitude", "longitude", "geometry"};
            case "accumulated_temperature" -> new String[]{"date", "temperature", "accumulatedTemp"};
            default -> new String[]{"ndvi", "captureTime"};
        };
        int available = 0;
        for (String key : expected) {
            Object value = metadata.get(key);
            if (value != null && !value.toString().isBlank() && !"null".equalsIgnoreCase(value.toString())) available++;
        }
        return 0.75D + 0.075D * (freshness + (double) available / expected.length);
    }

    private static boolean isEnvironment(String modality) {
        return switch (modality) {
            case "weather", "soil", "spatial", "accumulated_temperature", "satellite" -> true;
            default -> false;
        };
    }

    private static Instant parseTime(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); } catch (java.time.DateTimeException ignored) { }
        try { return LocalDateTime.parse(value.replace(' ', 'T')).atZone(FARM_ZONE).toInstant(); }
        catch (java.time.DateTimeException ignored) { }
        try { return LocalDate.parse(value).atStartOfDay(FARM_ZONE).toInstant(); }
        catch (java.time.DateTimeException ignored) { return null; }
    }

    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static Double finite(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : null;
    }
    private static double clamp(double value) { return Math.max(0D, Math.min(1D, value)); }

    private double modalityWeight(String modality) {
        if ("camera".equals(modality)) return cameraWeight;
        if ("weather".equals(modality)) return weatherWeight;
        if ("knowledge".equals(modality)) return knowledgeWeight;
        if ("historical_case".equals(modality)) return historicalWeight;
        if ("soil".equals(modality)) return soilWeight;
        if ("spatial".equals(modality)) return spatialWeight;
        if ("accumulated_temperature".equals(modality)) return accumulatedTemperatureWeight;
        if ("satellite".equals(modality)) return satelliteWeight;
        return 1.0;
    }

    private double sourceReliability(DiagnosisEvidenceVO item) {
        String expertStatus = item.getMetadata() == null ? "" : string(item.getMetadata().get("expertStatus")).toLowerCase(Locale.ROOT);
        if ("expert_confirmed".equals(expertStatus) || "expert-confirmed".equals(expertStatus)) return 1.1;
        if ("expert_reviewed".equals(expertStatus) || "reviewed".equals(expertStatus)) return 1.05;
        if ("ai_only".equals(expertStatus)) return 0.95;
        return 1.0;
    }
}

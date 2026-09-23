package com.mei.zhgy.service.reference;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only deterministic access to the already-built reference image atlas. */
@Component
@Slf4j
public class ReferenceImageStore {
    private static final String PLUM = "plum";
    private static final String PHENOLOGY = "PHENOLOGY";
    private static final String DISEASE = "DISEASE";
    private static final List<String> STAGE_CODES = List.of(
            "germination", "budding", "flowering", "fruiting", "fruit_development", "ripening", "dormancy");
    private static final List<String> VIGOR_ORDER = List.of("strong", "mid", "weak");

    private final MilvusServiceClient milvus;
    private final ReferenceImageProperties properties;
    private final String cosPublicBaseUrl;
    private final String cosBucket;
    private final String cosRegion;
    private volatile boolean collectionLoadAttempted;
    private volatile boolean collectionLoaded;
    private volatile long lastCollectionLoadAttemptAt;
    private static final long COLLECTION_LOAD_RETRY_MS = 30_000L;

    @Autowired
    public ReferenceImageStore(@Autowired(required = false) MilvusServiceClient milvus,
                               ReferenceImageProperties properties,
                               @Value("${tencent-cos.public-base-url:}") String cosPublicBaseUrl,
                               @Value("${tencent-cos.bucket:}") String cosBucket,
                               @Value("${tencent-cos.region:}") String cosRegion) {
        this.milvus = milvus;
        this.properties = properties;
        this.cosPublicBaseUrl = cosPublicBaseUrl;
        this.cosBucket = cosBucket;
        this.cosRegion = cosRegion;
    }

    public List<ReferenceImage> byStage(String crop, String stageCode) {
        return byStageResult(crop, stageCode).images();
    }

    public ReferenceImageLookup byStageResult(String crop, String stageCode) {
        if (!PLUM.equalsIgnoreCase(safe(crop))) return ReferenceImageLookup.unavailable("invalid-crop");
        String requestedStage = safe(stageCode).toLowerCase(Locale.ROOT);
        String resolvedStage = fallbackStage(requestedStage);
        if (!STAGE_CODES.contains(resolvedStage)) return ReferenceImageLookup.noMatch("invalid-stage", 0, 0);
        String match = requestedStage.equals(resolvedStage) ? "direct" : "fallback";
        return query(PHENOLOGY, resolvedStage, null, properties == null ? 0 : properties.getPerStageLimit(), match);
    }

    public List<ReferenceImage> byDisease(String crop, String diseaseKey) {
        return byDiseaseResult(crop, diseaseKey).images();
    }

    public ReferenceImageLookup byDiseaseResult(String crop, String diseaseKey) {
        if (!PLUM.equalsIgnoreCase(safe(crop)) || safe(diseaseKey).isBlank()) return ReferenceImageLookup.unavailable("invalid-disease-context");
        return query(DISEASE, null, safe(diseaseKey), properties == null ? 0 : properties.getPerDiseaseLimit(), "direct");
    }

    private ReferenceImageLookup query(String refType, String stageCode, String diseaseKey,
                                       int limit, String match) {
        if (limit <= 0 || milvus == null || properties == null || !properties.isEnabled()) {
            return ReferenceImageLookup.unavailable(match);
        }
        try {
            if (!ensureCollectionLoaded()) return ReferenceImageLookup.unavailable(match);
            StringBuilder expression = new StringBuilder("crop == \"plum\" && ref_type == \"")
                    .append(escape(refType)).append("\"");
            if (stageCode != null) expression.append(" && stage_code == \"").append(escape(stageCode)).append("\"");
            if (diseaseKey != null) expression.append(" && disease_key == \"").append(escape(diseaseKey)).append("\"");
            R<io.milvus.grpc.QueryResults> result = milvus.query(QueryParam.newBuilder()
                    .withCollectionName(properties.getCollection())
                    .withExpr(expression.toString())
                    .addOutField("ref_id")
                    .addOutField("ref_type")
                    .addOutField("stage_code")
                    .addOutField("disease_key")
                    .addOutField("label_cn")
                    .addOutField("image_url")
                    .addOutField("vigor")
                    .withLimit(64L)
                    .build());
            if (result == null || result.getStatus() == null || result.getStatus() != R.Status.Success.getCode()
                    || result.getData() == null) return ReferenceImageLookup.unavailable(match);
            List<QueryResultsWrapper.RowRecord> rawRows = new QueryResultsWrapper(result.getData()).getRowRecords();
            List<ReferenceImage> rows = rawRows.stream()
                    .map(row -> toReferenceImage(row, match))
                    .filter(row -> row != null && !row.imageUrl().isBlank() && allowedImageUrl(row.imageUrl()))
                    .sorted(Comparator.comparing(ReferenceImage::refId, Comparator.nullsLast(String::compareTo)))
                    .toList();
            List<ReferenceImage> output = roundRobin(rows, limit);
            return output.isEmpty()
                    ? ReferenceImageLookup.noMatch(match, rawRows.size(), rows.size())
                    : ReferenceImageLookup.ready(output, rawRows.size(), rows.size(), match);
        } catch (Exception error) {
            log.warn("参考图谱读取失败，按空列表降级: {}", error.getClass().getSimpleName());
            return ReferenceImageLookup.unavailable(match);
        }
    }

    private synchronized boolean ensureCollectionLoaded() {
        if (collectionLoaded) return true;
        long now = System.currentTimeMillis();
        if (collectionLoadAttempted && now - lastCollectionLoadAttemptAt < COLLECTION_LOAD_RETRY_MS) return false;
        collectionLoadAttempted = true;
        lastCollectionLoadAttemptAt = now;
        try {
            String collection = properties == null ? null : properties.getCollection();
            if (collection == null || collection.isBlank()) return false;
            R<Boolean> has = milvus.hasCollection(HasCollectionParam.newBuilder().withCollectionName(collection).build());
            if (has == null || has.getStatus() == null || has.getStatus() != R.Status.Success.getCode()
                    || !Boolean.TRUE.equals(has.getData())) return false;
            R<io.milvus.param.RpcStatus> loaded = milvus.loadCollection(
                    LoadCollectionParam.newBuilder().withCollectionName(collection).build());
            if (loaded == null || loaded.getStatus() == null || loaded.getStatus() != R.Status.Success.getCode()) return false;
            collectionLoaded = true;
            return true;
        } catch (Exception error) {
            collectionLoaded = false;
            log.warn("参考图谱集合加载失败，按空列表降级: {}", error.getClass().getSimpleName());
            return false;
        }
    }

    private ReferenceImage toReferenceImage(QueryResultsWrapper.RowRecord row, String match) {
        try {
            String imageUrl = text(row.get("image_url"));
            if (imageUrl.isBlank()) return null;
            return new ReferenceImage(text(row.get("ref_id")), text(row.get("ref_type")), text(row.get("stage_code")),
                    text(row.get("disease_key")), text(row.get("label_cn")), imageUrl, text(row.get("vigor")), match);
        } catch (Exception error) {
            return null;
        }
    }

    private List<ReferenceImage> roundRobin(List<ReferenceImage> rows, int limit) {
        Map<String, Deque<ReferenceImage>> grouped = new LinkedHashMap<>();
        VIGOR_ORDER.forEach(vigor -> grouped.put(vigor, new ArrayDeque<>()));
        rows.forEach(row -> {
            String vigor = row.vigor() == null ? "" : row.vigor().trim().toLowerCase(Locale.ROOT);
            grouped.computeIfAbsent(vigor, ignored -> new ArrayDeque<>()).addLast(row);
        });
        List<ReferenceImage> output = new ArrayList<>();
        while (output.size() < limit) {
            boolean added = false;
            for (Deque<ReferenceImage> queue : grouped.values()) {
                ReferenceImage row = queue.pollFirst();
                if (row == null) continue;
                output.add(row);
                added = true;
                if (output.size() >= limit) break;
            }
            if (!added) break;
        }
        return List.copyOf(output);
    }

    private boolean allowedImageUrl(String imageUrl) {
        try {
            URI reference = URI.create(imageUrl.trim());
            URI base = baseUri();
            return base != null && reference.getHost() != null && base.getHost() != null
                    && reference.getHost().equalsIgnoreCase(base.getHost())
                    && ("http".equalsIgnoreCase(reference.getScheme()) || "https".equalsIgnoreCase(reference.getScheme()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private URI baseUri() {
        try {
            if (cosPublicBaseUrl != null && !cosPublicBaseUrl.isBlank()) return URI.create(cosPublicBaseUrl.trim());
            if (cosBucket == null || cosBucket.isBlank() || cosRegion == null || cosRegion.isBlank()) return null;
            return URI.create("https://" + cosBucket.trim() + ".cos." + cosRegion.trim() + ".myqcloud.com");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String fallbackStage(String stageCode) {
        return switch (stageCode) {
            case "leafing" -> "fruiting";
            case "harvest" -> "ripening";
            default -> stageCode;
        };
    }

    private String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private String safe(String value) { return value == null ? "" : value.trim(); }
}

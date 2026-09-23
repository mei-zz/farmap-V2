package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.ai.EmbeddingProvider;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.IndexDescription;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/** Isolated Milvus generation for production ADMIN_CURATED Qwen text vectors. */
@Slf4j
@Component
public class QwenKnowledgeVectorStore {
    public static final String COLLECTION = "farmap_knowledge_chunks_qwen3_v1";
    public static final String MODEL = "qwen3.7-text-embedding-flash";
    public static final String ID_FIELD = "chunk_id";
    public static final String VECTOR_FIELD = "vector";
    public static final String METRIC = "COSINE";
    public static final String INDEX = "IVF_FLAT";
    public static final int NLIST = 128;
    private static final int ID_MAX_LENGTH = 160;

    private final DataSource dataSource;
    private final MilvusServiceClient client;
    private final KnowledgeEmbeddingProvider embeddingProvider;
    private final KnowledgeReranker reranker;
    private final int dimension;
    private final int nlist;
    private final int candidateTopN;
    private final int finalTopK;
    private final double minScore;
    private final String fusionMode;
    private final double rrfK;
    private final double denseWeight;
    private final double bm25Weight;
    private final int denseCandidateN;
    private final int lexicalCandidateN;
    private final Bm25IndexCache bm25IndexCache;
    private final AtomicLong bm25FallbackRebuilds = new AtomicLong();
    private volatile boolean initialized;

    @Autowired
    public QwenKnowledgeVectorStore(
            @Autowired(required = false) DataSource dataSource,
            @Autowired(required = false) MilvusServiceClient client,
            KnowledgeEmbeddingProvider embeddingProvider,
            KnowledgeReranker reranker,
            @Value("${farmap.knowledge.embedding.dimension:1024}") int dimension,
            @Value("${farmap.ai.retrieval.knowledge.milvus-nlist:128}") int nlist,
            @Value("${farmap.knowledge.rerank.candidate-top-n:20}") int candidateTopN,
            @Value("${farmap.knowledge.rerank.final-top-k:5}") int finalTopK,
            @Value("${farmap.knowledge.min-score:0.45}") double minScore,
            @Value("${farmap.knowledge.fusion-mode:rrf}") String fusionMode,
            @Value("${farmap.knowledge.rrf-k:60}") double rrfK,
            @Value("${farmap.knowledge.dense-weight:1.0}") double denseWeight,
            @Value("${farmap.knowledge.bm25-weight:1.0}") double bm25Weight,
            @Value("${farmap.knowledge.dense-candidate-n:30}") int denseCandidateN,
            @Value("${farmap.knowledge.lexical-candidate-n:30}") int lexicalCandidateN,
            @Value("${farmap.knowledge.bm25-cache-capacity:8}") int bm25CacheCapacity) {
        this.dataSource = dataSource;
        this.client = client;
        this.embeddingProvider = embeddingProvider;
        this.reranker = reranker;
        this.dimension = dimension;
        this.nlist = nlist;
        this.candidateTopN = Math.max(1, Math.min(candidateTopN, 100));
        this.finalTopK = Math.max(1, Math.min(finalTopK, 20));
        this.minScore = minScore;
        this.fusionMode = "weighted".equalsIgnoreCase(fusionMode) ? "weighted" : "rrf";
        this.rrfK = clampPositive(rrfK, 60D);
        this.denseWeight = Math.max(0D, finiteOrDefault(denseWeight, 1D));
        this.bm25Weight = Math.max(0D, finiteOrDefault(bm25Weight, 1D));
        this.denseCandidateN = clamp(denseCandidateN, 1, 100, 30);
        this.lexicalCandidateN = clamp(lexicalCandidateN, 1, 100, 30);
        this.bm25IndexCache = new Bm25IndexCache(clamp(bm25CacheCapacity, 1, 64, 8));
    }

    /** Compatibility constructor retained for existing tests and callers. */
    public QwenKnowledgeVectorStore(DataSource dataSource, MilvusServiceClient client,
                                    KnowledgeEmbeddingProvider embeddingProvider, KnowledgeReranker reranker,
                                    int dimension, int nlist, int candidateTopN, int finalTopK, double minScore) {
        this(dataSource, client, embeddingProvider, reranker, dimension, nlist, candidateTopN, finalTopK,
                minScore, "rrf", 60D, 1D, 1D, 30, 30, 8);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return Math.max(min, Math.min(max, value <= 0 ? fallback : value));
    }

    private static double finiteOrDefault(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clampPositive(double value, double fallback) {
        return Math.max(1D, Math.min(100000D, finiteOrDefault(value, fallback)));
    }

    public boolean isProductionEnabled() {
        return embeddingProvider != null && "BAILIAN".equalsIgnoreCase(embeddingProvider.provider());
    }

    public boolean isConfigured() { return isProductionEnabled() && client != null && embeddingProvider.configured(); }

    public Map<String, Object> initialize(boolean execute) {
        Map<String, Object> report = baseReport(execute);
        if (!isProductionEnabled()) {
            report.put("status", "UNAVAILABLE");
            report.put("reason", "PROVIDER_NOT_BAILIAN");
            return report;
        }
        if (client == null) {
            report.put("status", "UNAVAILABLE");
            report.put("reason", "MILVUS_CLIENT_UNAVAILABLE");
            return report;
        }
        if (embeddingProvider.dimension() != dimension) {
            report.put("status", "MIGRATION_REQUIRED");
            report.put("reason", "EMBEDDING_DIMENSION_CONFIGURATION_MISMATCH");
            return report;
        }
        try {
            R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            require(has, "hasCollection");
            boolean exists = Boolean.TRUE.equals(has.getData());
            report.put("collectionExists", exists);
            boolean created = false;
            if (!exists) {
                if (!execute) { report.put("status", "PLAN"); return report; }
                createCollection();
                created = true;
                report.put("collectionExists", true);
                report.put("created", true);
            }
            validateSchema();
            if (created) createIndex();
            validateIndex();
            if (execute) loadCollection();
            report.put("rowCount", rowCount());
            report.put("status", "READY");
            initialized = execute;
            return report;
        } catch (SchemaMismatchException error) {
            initialized = false;
            report.put("status", "MIGRATION_REQUIRED");
            report.put("reason", error.getMessage());
            return report;
        } catch (Exception error) {
            initialized = false;
            report.put("status", "UNAVAILABLE");
            report.put("reason", "MILVUS_INITIALIZATION_FAILED");
            report.put("error", error.getMessage());
            return report;
        }
    }

    public void insert(List<EmbeddingRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        Map<String, Object> state = initialize(true);
        if (!"READY".equals(state.get("status"))) throw KnowledgeBackendException.persistence("Qwen 知识向量集合不可用，写入已拒绝", null);
        List<String> ids = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>(), documentIds = new ArrayList<>(), versionIds = new ArrayList<>();
        List<Long> indexes = new ArrayList<>();
        List<String> hashes = new ArrayList<>(), classifications = new ArrayList<>(), models = new ArrayList<>();
        for (EmbeddingRow row : rows) {
            if (row == null || row.chunkId == null || row.vector == null || row.vector.size() != dimension
                    || !MODEL.equals(row.embeddingModel)) {
                throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "Qwen embedding dimension/model mismatch");
            }
            ids.add(row.chunkId); vectors.add(row.vector); sourceIds.add(row.sourceId); documentIds.add(row.documentId);
            versionIds.add(row.versionId); indexes.add((long) row.chunkIndex); hashes.add(row.contentHash);
            classifications.add(row.classification); models.add(row.embeddingModel);
        }
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(ID_FIELD, ids));
        fields.add(new InsertParam.Field(VECTOR_FIELD, vectors));
        fields.add(new InsertParam.Field("source_id", sourceIds));
        fields.add(new InsertParam.Field("document_id", documentIds));
        fields.add(new InsertParam.Field("version_id", versionIds));
        fields.add(new InsertParam.Field("chunk_index", indexes));
        fields.add(new InsertParam.Field("content_hash", hashes));
        fields.add(new InsertParam.Field("classification", classifications));
        fields.add(new InsertParam.Field("embedding_model", models));
        R<MutationResult> result = client.insert(InsertParam.newBuilder().withCollectionName(COLLECTION).withFields(fields).build());
        if (result == null || result.getStatus() != R.Status.Success.getCode()) throw KnowledgeBackendException.persistence("Qwen 向量写入 Milvus 失败", null);
    }

    public SearchResult searchResult(String query, Long actorId, int requestedTopK) {
        return searchResult(query, actorId, requestedTopK, true, null);
    }

    public SearchResult searchResult(String query, Long actorId, int requestedTopK, boolean useReranker) {
        return searchResult(query, actorId, requestedTopK, useReranker, null);
    }

    /** Crop-aware P0 search. Untagged legacy vectors are excluded when a crop is known. */
    public SearchResult searchResult(String query, Long actorId, int requestedTopK, boolean useReranker, String cropCode) {
        return searchResult(query, actorId, requestedTopK, useReranker, cropCode, true);
    }

    private SearchResult searchResult(String query, Long actorId, int requestedTopK, boolean useReranker,
                                      String cropCode, boolean allowUntaggedRelaxation) {
        if (!isProductionEnabled() || query == null || query.isBlank() || client == null) {
            return new SearchResult(client == null ? "UNAVAILABLE" : "READY_EMPTY", Collections.emptyList(), "UNAVAILABLE", "UNAVAILABLE");
        }
        Map<String, Object> state = initialize(false);
        if (!"READY".equals(state.get("status"))) return new SearchResult("UNAVAILABLE", Collections.emptyList(), "UNAVAILABLE", "UNAVAILABLE");
        try {
            if (!initialized) { require(client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build()), "loadCollection"); initialized = true; }
            EmbeddingProvider.EmbeddingResult queryEmbedding = embeddingProvider.embedText(query);
            validateVector(queryEmbedding.getVector());
            SearchParam param = SearchParam.newBuilder().withCollectionName(COLLECTION).withMetricType(MetricType.COSINE)
                    .withOutFields(Collections.singletonList(ID_FIELD)).withTopK(denseCandidateN)
                    .withVectors(Collections.singletonList(queryEmbedding.getVector())).withVectorFieldName(VECTOR_FIELD)
                    .withParams("{\"nprobe\": 10}").build();
            R<SearchResults> response = client.search(param);
            if (response == null || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
                return new SearchResult("UNAVAILABLE", Collections.emptyList(), embeddingProvider.provider(), "UNAVAILABLE");
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            List<KnowledgeVectorHit> vectorCandidates = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : scores) {
                Object id = score.get(ID_FIELD);
                if (id == null || score.getScore() < minScore) continue;
                KnowledgeVectorHit hit = findChunk(String.valueOf(id), actorId);
                if (hit != null && (cropCode == null || matchesCropTag(hit.classification, cropCode, hit.content))) {
                    hit.vectorScore = score.getScore(); vectorCandidates.add(hit);
                }
            }
            vectorCandidates.sort(Comparator.comparingDouble((KnowledgeVectorHit value) -> value.vectorScore).reversed()
                    .thenComparing(value -> value.chunkId, Comparator.nullsFirst(String::compareTo)));
            List<KnowledgeVectorHit> lexicalCandidates = List.of();
            if (dataSource != null) {
                try {
                    lexicalCandidates = bm25Candidates(query, actorId, cropCode);
                } catch (Exception error) {
                    log.warn("BM25 knowledge recall unavailable: {}", error.getClass().getSimpleName());
                }
            }
            boolean denseHit = !vectorCandidates.isEmpty();
            boolean lexicalHit = !lexicalCandidates.isEmpty();
            List<KnowledgeVectorHit> candidates;
            try {
                candidates = fuseCandidates(vectorCandidates, lexicalCandidates);
            } catch (Exception error) {
                log.warn("Knowledge fusion unavailable; falling back to dense: {}", error.getClass().getSimpleName());
                candidates = new ArrayList<>(vectorCandidates);
                applyHybridScores(candidates, false);
                candidates.sort(Comparator.comparingDouble((KnowledgeVectorHit value) -> value.hybridScore).reversed()
                        .thenComparing(value -> value.chunkId, Comparator.nullsFirst(String::compareTo)));
                candidates = capCandidates(candidates, candidateTopN);
            }
            if (candidates.isEmpty()) {
                if (cropCode != null && allowUntaggedRelaxation) {
                    // The production corpus currently contains legacy public
                    // chunks whose crop metadata is absent. A strict crop
                    // filter can therefore remove every candidate before the
                    // normal output-stage relaxation below is reached. Retry
                    // once without a crop filter and keep only untagged rows;
                    // never use a row explicitly tagged for another crop.
                    SearchResult relaxed = searchResult(query, actorId, requestedTopK, useReranker, null, false);
                    List<KnowledgeVectorHit> untagged = relaxed.hits.stream()
                            .filter(hit -> !hasAnyCropTag(hit.classification, hit.content)).toList();
                    return new SearchResult(untagged.isEmpty() ? "READY_EMPTY" : "DEGRADED", untagged,
                            relaxed.retrievalProvider, relaxed.rerankerStatus);
                }
                return new SearchResult("READY_EMPTY", Collections.emptyList(), retrievalProvider(denseHit, lexicalHit), "READY_EMPTY");
            }
            String rerankerStatus = useReranker ? "UNAVAILABLE" : "NOT_USED";
            List<KnowledgeReranker.RerankResult> ranked = Collections.emptyList();
            if (useReranker && reranker != null && reranker.configured()) {
                try {
                    ranked = reranker.rerank(query, candidates.stream().map(value -> value.content).toList(), finalTopK);
                    rerankerStatus = "READY";
                } catch (Exception error) {
                    log.warn("Qwen knowledge reranker unavailable: {}", error.getClass().getSimpleName());
                }
            }
            List<KnowledgeVectorHit> output = new ArrayList<>();
            if (!ranked.isEmpty()) {
                for (KnowledgeReranker.RerankResult result : ranked) {
                    if (result.getIndex() >= 0 && result.getIndex() < candidates.size()) {
                        KnowledgeVectorHit hit = candidates.get(result.getIndex());
                        hit.rerankScore = result.getScore();
                        output.add(hit);
                    }
                }
            } else {
                output.addAll(candidates);
                output.sort(fallbackComparator());
            }
            int limit = Math.max(1, Math.min(requestedTopK, finalTopK));
            if (output.size() > limit) output = new ArrayList<>(output.subList(0, limit));
            if (output.isEmpty() && cropCode != null && allowUntaggedRelaxation) {
                // A strict crop filter must not turn legacy, untagged public
                // knowledge into a false outage. Relax once, but retain only
                // chunks without any crop tag; never return another crop.
                SearchResult relaxed = searchResult(query, actorId, requestedTopK, useReranker, null, false);
                List<KnowledgeVectorHit> untagged = relaxed.hits.stream().filter(hit -> !hasAnyCropTag(hit.classification, hit.content)).toList();
                return new SearchResult(untagged.isEmpty() ? "READY_EMPTY" : "DEGRADED", untagged,
                        relaxed.retrievalProvider, relaxed.rerankerStatus);
            }
            String status = !useReranker || rerankerStatus.equals("READY") ? "READY" : "DEGRADED";
            return new SearchResult(status, output, retrievalProvider(denseHit, lexicalHit), rerankerStatus);
        } catch (Exception error) {
            log.warn("Qwen knowledge retrieval unavailable: {}", error.getClass().getSimpleName());
            return new SearchResult("UNAVAILABLE", Collections.emptyList(), embeddingProvider.provider(), "UNAVAILABLE");
        }
    }

    private boolean matchesCropTag(String classification, String cropCode, String content) {
        if (cropCode == null || cropCode.isBlank()) return false;
        String source = (classification == null ? "" : classification) + "\n" + (content == null ? "" : content);
        String normalized = source.toLowerCase(java.util.Locale.ROOT).replace("_", "-");
        String classificationNormalized = classification == null ? "" : classification.trim().toLowerCase(java.util.Locale.ROOT).replace("_", "-");
        String code = cropCode.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("crop:" + code) || normalized.contains("crop=" + code)
                || normalized.contains("crop-" + code) || classificationNormalized.equals(code);
    }

    private boolean hasAnyCropTag(String classification, String content) {
        String source = (classification == null ? "" : classification) + "\n" + (content == null ? "" : content);
        if (source.toLowerCase(java.util.Locale.ROOT).matches("(?s).*crop\\s*[:=\\-].*")) return true;
        String normalized = classification == null ? "" : classification.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("plum") || normalized.equals("citrus") || normalized.equals("rice") || normalized.equals("rice_late");
    }

    private List<KnowledgeVectorHit> bm25Candidates(String query, Long actorId, String cropCode) throws Exception {
        CachedBm25Corpus cached = getBm25Corpus(actorId);
        if (cached == null || cached.corpus.totalDocuments() == 0) return List.of();
        List<KnowledgeVectorHit> result = new ArrayList<>();
        // Score the whole cached corpus before applying crop filtering. A global
        // top-N slice could otherwise hide the best match for the requested crop.
        for (KnowledgeBm25.ScoredDocument scored : cached.corpus.scoreAgainst(query, Integer.MAX_VALUE)) {
            CachedChunk chunk = cached.chunks.get(scored.document().id());
            if (chunk == null || (cropCode != null && !matchesCropTag(chunk.classification, cropCode, chunk.content))) continue;
            KnowledgeVectorHit hit = chunk.toHit();
            hit.bm25Score = scored.score();
            result.add(hit);
            if (result.size() >= lexicalCandidateN) break;
        }
        return result;
    }

    private List<KnowledgeVectorHit> fuseCandidates(List<KnowledgeVectorHit> dense, List<KnowledgeVectorHit> lexical) {
        return "weighted".equals(fusionMode)
                ? weightedFuse(dense, lexical, candidateTopN)
                : rrfFuse(dense, lexical, candidateTopN, rrfK, denseWeight, bm25Weight);
    }

    /** Package-visible deterministic seam for formula and ordering tests. */
    static List<KnowledgeVectorHit> rrfFuse(List<KnowledgeVectorHit> dense, List<KnowledgeVectorHit> lexical,
                                            int candidateLimit, double rrfK, double denseWeight, double bm25Weight) {
        List<KnowledgeVectorHit> merged = mergeRoutes(dense, lexical);
        for (KnowledgeVectorHit hit : merged) {
            hit.rrfScore = denseWeight * (hit.denseRank == 0 ? 0D : 1D / (rrfK + hit.denseRank))
                    + bm25Weight * (hit.bm25Rank == 0 ? 0D : 1D / (rrfK + hit.bm25Rank));
            hit.hybridScore = hit.rrfScore;
        }
        merged.sort(rrfComparator());
        return capCandidates(merged, candidateLimit);
    }

    /** Package-visible seam preserving the legacy weighted fusion for rollback/A-B. */
    static List<KnowledgeVectorHit> weightedFuse(List<KnowledgeVectorHit> dense, List<KnowledgeVectorHit> lexical,
                                                 int candidateLimit) {
        List<KnowledgeVectorHit> merged = mergeRoutes(dense, lexical);
        applyHybridScores(merged, !lexical.isEmpty());
        merged.sort(Comparator.comparingDouble((KnowledgeVectorHit value) -> value.hybridScore).reversed()
                .thenComparing(value -> value.chunkId, Comparator.nullsFirst(String::compareTo)));
        return capCandidates(merged, candidateLimit);
    }

    private static List<KnowledgeVectorHit> mergeRoutes(List<KnowledgeVectorHit> dense, List<KnowledgeVectorHit> lexical) {
        Map<String, KnowledgeVectorHit> merged = new LinkedHashMap<>();
        List<KnowledgeVectorHit> denseOrdered = new ArrayList<>(dense == null ? List.of() : dense);
        denseOrdered.removeIf(Objects::isNull);
        denseOrdered.sort(Comparator.comparingDouble((KnowledgeVectorHit value) -> value.vectorScore).reversed()
                .thenComparing(value -> value.chunkId, Comparator.nullsFirst(String::compareTo)));
        int denseRank = 0;
        for (KnowledgeVectorHit hit : denseOrdered) {
            if (hit.chunkId == null || merged.containsKey(hit.chunkId)) continue;
            hit.denseRank = ++denseRank;
            merged.put(hit.chunkId, hit);
        }

        List<KnowledgeVectorHit> lexicalOrdered = new ArrayList<>(lexical == null ? List.of() : lexical);
        lexicalOrdered.removeIf(Objects::isNull);
        lexicalOrdered.sort(Comparator.comparingDouble((KnowledgeVectorHit value) -> value.bm25Score).reversed()
                .thenComparing(value -> value.chunkId, Comparator.nullsFirst(String::compareTo)));
        int bm25Rank = 0;
        for (KnowledgeVectorHit hit : lexicalOrdered) {
            if (hit.chunkId == null || hit.bm25Score <= 0D) continue;
            KnowledgeVectorHit existing = merged.get(hit.chunkId);
            if (existing == null) {
                hit.bm25Rank = ++bm25Rank;
                merged.put(hit.chunkId, hit);
            } else if (existing.bm25Rank == 0) {
                existing.bm25Rank = ++bm25Rank;
                existing.bm25Score = hit.bm25Score;
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static List<KnowledgeVectorHit> capCandidates(List<KnowledgeVectorHit> candidates, int limit) {
        int safeLimit = Math.max(1, limit);
        if (candidates.size() <= safeLimit) return candidates;
        return new ArrayList<>(candidates.subList(0, safeLimit));
    }

    private static Comparator<KnowledgeVectorHit> rrfComparator() {
        return Comparator.comparingDouble((KnowledgeVectorHit value) -> value.rrfScore).reversed()
                .thenComparing((left, right) -> Boolean.compare(hasBothRoutes(right), hasBothRoutes(left)))
                .thenComparing(Comparator.comparingDouble((KnowledgeVectorHit value) -> value.vectorScore).reversed())
                .thenComparing(value -> value.chunkId, Comparator.nullsFirst(String::compareTo));
    }

    private Comparator<KnowledgeVectorHit> fallbackComparator() {
        return "rrf".equals(fusionMode)
                ? rrfComparator()
                : Comparator.comparingDouble((KnowledgeVectorHit value) -> value.hybridScore).reversed()
                .thenComparing(value -> value.chunkId, Comparator.nullsFirst(String::compareTo));
    }

    private static boolean hasBothRoutes(KnowledgeVectorHit hit) {
        return hit.denseRank > 0 && hit.bm25Rank > 0;
    }

    private static void applyHybridScores(List<KnowledgeVectorHit> candidates, boolean hybrid) {
        double maxVector = candidates.stream().mapToDouble(hit -> hit.vectorScore).max().orElse(0D);
        double maxBm25 = candidates.stream().mapToDouble(hit -> hit.bm25Score).max().orElse(0D);
        for (KnowledgeVectorHit hit : candidates) {
            double vector = maxVector <= 0D ? 0D : Math.max(0D, hit.vectorScore) / maxVector;
            double bm25 = maxBm25 <= 0D ? 0D : Math.max(0D, hit.bm25Score) / maxBm25;
            hit.hybridScore = hybrid ? .55D * vector + .45D * bm25 : vector;
        }
    }

    private String retrievalProvider(boolean denseHit, boolean lexicalHit) {
        if ("rrf".equals(fusionMode)) {
            if (denseHit && lexicalHit) return embeddingProvider.provider() + "+BM25[RRF]";
            if (denseHit) return embeddingProvider.provider() + "[RRF]";
            if (lexicalHit) return "BM25[RRF]";
            return embeddingProvider.provider();
        }
        if (denseHit && lexicalHit) return embeddingProvider.provider() + "+BM25";
        if (denseHit) return embeddingProvider.provider();
        if (lexicalHit) return "BM25";
        return embeddingProvider.provider();
    }

    private CachedBm25Corpus getBm25Corpus(Long actorId) throws Exception {
        final String fingerprint;
        try {
            fingerprint = findCorpusFingerprint(actorId);
        } catch (Exception error) {
            // A fingerprint outage must not make lexical recall an outage.
            // Build directly using the same actor-scoped query as the legacy path.
            bm25FallbackRebuilds.incrementAndGet();
            log.warn("BM25 corpus fingerprint unavailable; rebuilding directly: {}", error.getClass().getSimpleName());
            return buildBm25Corpus(actorId, null);
        }
        try {
            return bm25IndexCache.getOrBuild(actorId, fingerprint,
                    () -> {
                        try {
                            return buildBm25Corpus(actorId, fingerprint);
                        } catch (Exception error) {
                            throw new Bm25BuildException(error);
                        }
                    });
        } catch (Bm25BuildException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private CachedBm25Corpus buildBm25Corpus(Long actorId, String fingerprint) throws Exception {
        List<KnowledgeVectorHit> all = findAllChunks(actorId);
        Map<String, CachedChunk> chunks = new LinkedHashMap<>();
        List<KnowledgeBm25.Document> documents = new ArrayList<>();
        for (KnowledgeVectorHit hit : all) {
            if (hit == null || hit.chunkId == null || chunks.containsKey(hit.chunkId)) continue;
            CachedChunk cached = CachedChunk.from(hit);
            chunks.put(hit.chunkId, cached);
            documents.add(new KnowledgeBm25.Document(hit.chunkId, hit.content));
        }
        return new CachedBm25Corpus(fingerprint, new KnowledgeBm25().buildCorpus(documents), chunks);
    }

    private String findCorpusFingerprint(Long actorId) throws Exception {
        String sql = "SELECT c.chunk_id,c.content_hash " +
                "FROM knowledge_chunks c JOIN knowledge_sources s ON s.source_id=c.source_id JOIN knowledge_documents d ON d.document_id=c.document_id " +
                "JOIN knowledge_ingestions i ON i.version_id=c.version_id WHERE s.source_type='ADMIN_CURATED' AND s.status='ACTIVE' " +
                "AND d.status='ACTIVE' AND i.status='READY' AND i.embedding_model=? AND i.index_collection=? AND d.current_version_id=c.version_id " +
                "AND (s.owner_user_id IS NULL OR s.owner_user_id=?) AND (d.owner_user_id IS NULL OR d.owner_user_id=?) " +
                "ORDER BY c.chunk_id";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, MODEL); p.setString(2, COLLECTION);
            bindActor(p, 3, actorId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    String chunkId = r.getString(1);
                    String contentHash = r.getString(2);
                    digest.update((chunkId == null ? "" : chunkId).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update((contentHash == null ? "" : contentHash).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }

    private void bindActor(PreparedStatement statement, int firstIndex, Long actorId) throws Exception {
        if (actorId == null) {
            statement.setNull(firstIndex, java.sql.Types.BIGINT);
            statement.setNull(firstIndex + 1, java.sql.Types.BIGINT);
        } else {
            statement.setLong(firstIndex, actorId);
            statement.setLong(firstIndex + 1, actorId);
        }
    }

    public long getBm25IndexHitCount() { return bm25IndexCache.hitCount(); }

    public long getBm25IndexRebuildCount() { return bm25IndexCache.rebuildCount() + bm25FallbackRebuilds.get(); }

    public int getBm25IndexCacheSize() { return bm25IndexCache.size(); }

    static final class Bm25IndexCache {
        private final int capacity;
        private final LinkedHashMap<Long, CachedBm25Corpus> entries = new LinkedHashMap<>(16, 0.75F, true);
        private long hits;
        private long rebuilds;

        Bm25IndexCache(int capacity) { this.capacity = Math.max(1, capacity); }

        synchronized CachedBm25Corpus getOrBuild(Long actorId, String fingerprint, Supplier<CachedBm25Corpus> builder) {
            CachedBm25Corpus cached = entries.get(actorId);
            if (cached != null && Objects.equals(cached.fingerprint, fingerprint)) {
                hits++;
                log.debug("BM25 index cache hit scope={}", actorId == null ? "public" : "actor");
                return cached;
            }
            CachedBm25Corpus built = Objects.requireNonNull(builder.get(), "BM25 corpus builder returned null");
            entries.put(actorId, built);
            rebuilds++;
            while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
            log.info("BM25 index rebuilt scope={} documents={} cacheSize={}",
                    actorId == null ? "public" : "actor", built.corpus.totalDocuments(), entries.size());
            return built;
        }

        synchronized long hitCount() { return hits; }

        synchronized long rebuildCount() { return rebuilds; }

        synchronized int size() { return entries.size(); }
    }

    static final class CachedBm25Corpus {
        final String fingerprint;
        final KnowledgeBm25.Bm25Corpus corpus;
        final Map<String, CachedChunk> chunks;

        CachedBm25Corpus(String fingerprint, KnowledgeBm25.Bm25Corpus corpus, Map<String, CachedChunk> chunks) {
            this.fingerprint = fingerprint;
            this.corpus = corpus;
            this.chunks = Map.copyOf(chunks);
        }
    }

    static record CachedChunk(String chunkId, String sourceId, String sourceName, String documentId,
                              String documentTitle, String relativePath, String versionId, String content,
                              String contentHash, String classification) {
        static CachedChunk from(KnowledgeVectorHit hit) {
            return new CachedChunk(hit.chunkId, hit.sourceId, hit.sourceName, hit.documentId, hit.documentTitle,
                    hit.relativePath, hit.versionId, hit.content, hit.contentHash, hit.classification);
        }

        KnowledgeVectorHit toHit() {
            return new KnowledgeVectorHit(chunkId, sourceId, sourceName, documentId, documentTitle, relativePath,
                    versionId, content, contentHash, classification);
        }
    }

    private static final class Bm25BuildException extends RuntimeException {
        Bm25BuildException(Exception cause) { super(cause); }
    }

    public Map<String, Object> health() {
        Map<String, Object> report = baseReport(false);
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider", embeddingProvider == null ? "UNAVAILABLE" : embeddingProvider.provider());
        embedding.put("model", embeddingProvider == null ? MODEL : embeddingProvider.model());
        embedding.put("dimension", dimension);
        embedding.put("status", isConfigured() ? "READY" : "UNAVAILABLE");
        report.put("knowledgeEmbedding", embedding);
        Map<String, Object> rerank = new LinkedHashMap<>();
        rerank.put("provider", reranker == null ? "UNAVAILABLE" : reranker.provider());
        rerank.put("model", reranker == null ? "gte-rerank-v2" : reranker.model());
        rerank.put("status", reranker != null && reranker.configured() ? "READY" : "UNAVAILABLE");
        report.put("knowledgeReranker", rerank);
        if (!isProductionEnabled()) { report.put("status", "UNAVAILABLE"); report.put("reason", "PROVIDER_NOT_BAILIAN"); return report; }
        Map<String, Object> index = initialize(false);
        if ("PLAN".equals(index.get("status"))) { index.put("status", "UNAVAILABLE"); index.put("reason", "COLLECTION_MISSING"); }
        report.putAll(index);
        return report;
    }

    private Map<String, Object> baseReport(boolean execute) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("collection", COLLECTION); report.put("embeddingModel", MODEL); report.put("dimension", dimension);
        report.put("metric", METRIC); report.put("index", INDEX); report.put("nlist", nlist);
        report.put("candidateTopN", candidateTopN); report.put("finalTopK", finalTopK); report.put("mode", execute ? "EXECUTE" : "DRY_RUN");
        report.put("created", false); report.put("minScore", minScore);
        report.put("fusionMode", fusionMode); report.put("rrfK", rrfK);
        report.put("denseWeight", denseWeight); report.put("bm25Weight", bm25Weight);
        report.put("denseCandidateN", denseCandidateN); report.put("lexicalCandidateN", lexicalCandidateN);
        report.put("bm25IndexCacheCapacity", bm25IndexCache.capacity);
        report.put("bm25IndexCacheSize", bm25IndexCache.size());
        report.put("bm25IndexCacheHits", bm25IndexCache.hitCount());
        report.put("bm25IndexRebuilds", getBm25IndexRebuildCount());
        return report;
    }

    private void createCollection() {
        FieldType id = FieldType.newBuilder().withName(ID_FIELD).withDataType(DataType.VarChar).withMaxLength(ID_MAX_LENGTH).withPrimaryKey(true).withAutoID(false).build();
        FieldType vector = FieldType.newBuilder().withName(VECTOR_FIELD).withDataType(DataType.FloatVector).withDimension(dimension).build();
        R<RpcStatus> result = client.createCollection(CreateCollectionParam.newBuilder().withCollectionName(COLLECTION)
                .withDescription("FarMap administrator-curated knowledge chunks, Qwen text embeddings")
                .addFieldType(id).addFieldType(vector).addFieldType(varchar("source_id", 112)).addFieldType(varchar("document_id", 112))
                .addFieldType(varchar("version_id", 112)).addFieldType(FieldType.newBuilder().withName("chunk_index").withDataType(DataType.Int64).build())
                .addFieldType(varchar("content_hash", 64)).addFieldType(varchar("classification", 64)).addFieldType(varchar("embedding_model", 128)).build());
        require(result, "createCollection");
    }

    private FieldType varchar(String name, int maxLength) { return FieldType.newBuilder().withName(name).withDataType(DataType.VarChar).withMaxLength(maxLength).build(); }

    private void validateSchema() {
        R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
        require(response, "describeCollection");
        List<String> problems = new ArrayList<>();
        List<io.milvus.grpc.FieldSchema> fields = response.getData().getSchema().getFieldsList();
        validateVarChar(fields, ID_FIELD, ID_MAX_LENGTH, true, problems);
        io.milvus.grpc.FieldSchema vector = fields.stream().filter(field -> VECTOR_FIELD.equals(field.getName())).findFirst().orElse(null);
        if (vector == null || vector.getDataType() != DataType.FloatVector || !String.valueOf(dimension).equals(typeParam(vector.getTypeParamsList(), "dim"))) problems.add("vector contract mismatch");
        validateVarChar(fields, "source_id", 112, false, problems); validateVarChar(fields, "document_id", 112, false, problems);
        validateVarChar(fields, "version_id", 112, false, problems); validateVarChar(fields, "content_hash", 64, false, problems);
        validateVarChar(fields, "classification", 64, false, problems); validateVarChar(fields, "embedding_model", 128, false, problems);
        io.milvus.grpc.FieldSchema chunkIndex = fields.stream().filter(field -> "chunk_index".equals(field.getName())).findFirst().orElse(null);
        if (chunkIndex == null || chunkIndex.getDataType() != DataType.Int64) problems.add("chunk_index type mismatch");
        if (!problems.isEmpty()) throw new SchemaMismatchException(problems);
    }

    private void validateVarChar(List<io.milvus.grpc.FieldSchema> fields, String name, int maxLength, boolean primaryKey, List<String> problems) {
        io.milvus.grpc.FieldSchema field = fields.stream().filter(item -> name.equals(item.getName())).findFirst().orElse(null);
        if (field == null) { problems.add("missing " + name); return; }
        if (field.getDataType() != DataType.VarChar || !String.valueOf(maxLength).equals(typeParam(field.getTypeParamsList(), "max_length"))) problems.add(name + " contract mismatch");
        if (primaryKey && (!field.getIsPrimaryKey() || field.getAutoID())) problems.add(name + " primary key mismatch");
    }

    private void createIndex() { require(client.createIndex(CreateIndexParam.newBuilder().withCollectionName(COLLECTION).withFieldName(VECTOR_FIELD).withIndexType(IndexType.IVF_FLAT).withMetricType(MetricType.COSINE).withExtraParam("{\"nlist\": " + nlist + "}").withSyncMode(Boolean.TRUE).build()), "createIndex"); }

    private void validateIndex() {
        R<DescribeIndexResponse> response = client.describeIndex(DescribeIndexParam.newBuilder().withCollectionName(COLLECTION).build());
        require(response, "describeIndex");
        if (response.getData().getIndexDescriptionsCount() == 0) throw new SchemaMismatchException(List.of("missing vector index"));
        IndexDescription index = response.getData().getIndexDescriptionsList().stream().filter(item -> VECTOR_FIELD.equals(item.getFieldName())).findFirst().orElse(response.getData().getIndexDescriptions(0));
        Map<String, String> params = new LinkedHashMap<>(); for (KeyValuePair param : index.getParamsList()) params.put(param.getKey(), param.getValue());
        List<String> problems = new ArrayList<>();
        if (!VECTOR_FIELD.equals(index.getFieldName())) problems.add("index field mismatch");
        if (!INDEX.equalsIgnoreCase(params.getOrDefault("index_type", ""))) problems.add("index_type mismatch");
        if (!METRIC.equalsIgnoreCase(params.getOrDefault("metric_type", ""))) problems.add("metric_type mismatch");
        String extra = params.getOrDefault("params", ""); if (!extra.contains("\"nlist\":" + nlist) && !extra.contains("\"nlist\": " + nlist)) problems.add("nlist mismatch");
        if (!problems.isEmpty()) throw new SchemaMismatchException(problems);
    }

    private void loadCollection() { require(client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build()), "loadCollection"); }

    private long rowCount() {
        R<GetCollectionStatisticsResponse> response = client.getCollectionStatistics(GetCollectionStatisticsParam.newBuilder().withCollectionName(COLLECTION).build());
        require(response, "getCollectionStatistics");
        for (KeyValuePair stat : response.getData().getStatsList()) if ("row_count".equalsIgnoreCase(stat.getKey())) return Long.parseLong(stat.getValue());
        throw new IllegalStateException("Milvus row_count is missing");
    }

    private KnowledgeVectorHit findChunk(String chunkId, Long actorId) throws Exception {
        if (dataSource == null) return null;
        String sql = "SELECT c.chunk_id,c.source_id,s.name,c.document_id,d.title,d.external_reference,c.version_id,c.content,c.content_hash,c.classification " +
                "FROM knowledge_chunks c JOIN knowledge_sources s ON s.source_id=c.source_id JOIN knowledge_documents d ON d.document_id=c.document_id " +
                "JOIN knowledge_ingestions i ON i.version_id=c.version_id WHERE c.chunk_id=? AND s.source_type='ADMIN_CURATED' AND s.status='ACTIVE' " +
                "AND d.status='ACTIVE' AND i.status='READY' AND i.embedding_model=? AND i.index_collection=? AND d.current_version_id=c.version_id " +
                "AND (s.owner_user_id IS NULL OR s.owner_user_id=?) AND (d.owner_user_id IS NULL OR d.owner_user_id=?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, chunkId); p.setString(2, MODEL); p.setString(3, COLLECTION);
            if (actorId == null) { p.setNull(4, java.sql.Types.BIGINT); p.setNull(5, java.sql.Types.BIGINT); } else { p.setLong(4, actorId); p.setLong(5, actorId); }
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return new KnowledgeVectorHit(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10));
            }
        }
    }

    private List<KnowledgeVectorHit> findAllChunks(Long actorId) throws Exception {
        if (dataSource == null) return List.of();
        String sql = "SELECT c.chunk_id,c.source_id,s.name,c.document_id,d.title,d.external_reference,c.version_id,c.content,c.content_hash,c.classification " +
                "FROM knowledge_chunks c JOIN knowledge_sources s ON s.source_id=c.source_id JOIN knowledge_documents d ON d.document_id=c.document_id " +
                "JOIN knowledge_ingestions i ON i.version_id=c.version_id WHERE s.source_type='ADMIN_CURATED' AND s.status='ACTIVE' " +
                "AND d.status='ACTIVE' AND i.status='READY' AND i.embedding_model=? AND i.index_collection=? AND d.current_version_id=c.version_id " +
                "AND (s.owner_user_id IS NULL OR s.owner_user_id=?) AND (d.owner_user_id IS NULL OR d.owner_user_id=?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, MODEL); p.setString(2, COLLECTION);
            if (actorId == null) { p.setNull(3, java.sql.Types.BIGINT); p.setNull(4, java.sql.Types.BIGINT); }
            else { p.setLong(3, actorId); p.setLong(4, actorId); }
            try (ResultSet r = p.executeQuery()) {
                List<KnowledgeVectorHit> result = new ArrayList<>();
                while (r.next()) result.add(new KnowledgeVectorHit(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10)));
                return result;
            }
        }
    }

    private void validateVector(List<Float> vector) {
        if (vector == null || vector.size() != dimension) throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "Qwen query embedding dimension mismatch");
        for (Float value : vector) if (value == null || !Float.isFinite(value)) throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "Qwen query embedding contains invalid value");
    }

    private String typeParam(List<KeyValuePair> params, String key) { return params.stream().filter(param -> key.equals(param.getKey())).map(KeyValuePair::getValue).findFirst().orElse(""); }
    private void require(R<?> response, String operation) { if (response == null || response.getStatus() != R.Status.Success.getCode()) throw new IllegalStateException("MILVUS_" + operation.toUpperCase() + "_FAILED: " + (response == null ? "null" : response.getMessage())); }

    public static final class EmbeddingRow {
        public final String chunkId, sourceId, documentId, versionId, contentHash, classification, embeddingModel;
        public final int chunkIndex;
        public final List<Float> vector;
        public EmbeddingRow(String chunkId, String sourceId, String documentId, String versionId, int chunkIndex, String contentHash, String classification, String embeddingModel, List<Float> vector) {
            this.chunkId = chunkId; this.sourceId = sourceId; this.documentId = documentId; this.versionId = versionId; this.chunkIndex = chunkIndex;
            this.contentHash = contentHash; this.classification = classification; this.embeddingModel = embeddingModel; this.vector = vector;
        }
    }

    public static final class KnowledgeVectorHit {
        public final String chunkId, sourceId, sourceName, documentId, documentTitle, relativePath, versionId, content, contentHash, classification;
        public double vectorScore;
        public double bm25Score;
        public double hybridScore;
        public int denseRank;
        public int bm25Rank;
        public double rrfScore;
        public double rerankScore = Double.NaN;
        KnowledgeVectorHit(String a, String b, String c, String d, String e, String f, String g, String h, String i, String j) {
            chunkId = a; sourceId = b; sourceName = c; documentId = d; documentTitle = e; relativePath = f; versionId = g; content = h; contentHash = i; classification = j;
        }
    }

    public static final class SearchResult {
        private final String status, retrievalProvider, rerankerStatus;
        private final List<KnowledgeVectorHit> hits;
        SearchResult(String status, List<KnowledgeVectorHit> hits, String retrievalProvider, String rerankerStatus) { this.status = status; this.hits = hits; this.retrievalProvider = retrievalProvider; this.rerankerStatus = rerankerStatus; }
        public String getStatus() { return status; }
        public List<KnowledgeVectorHit> getHits() { return hits; }
        public String getRetrievalProvider() { return retrievalProvider; }
        public String getRerankerStatus() { return rerankerStatus; }
    }

    public static final class SchemaMismatchException extends IllegalStateException { public SchemaMismatchException(List<String> problems) { super("SCHEMA_MISMATCH: " + String.join(", ", problems)); } }
}

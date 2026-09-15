package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.ai.IntelligenceGateway;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dedicated semantic store for admin-curated knowledge chunks. */
@Slf4j
@Component
public class KnowledgeVectorStore {
    public static final String COLLECTION = "farmap_knowledge_chunks_v1";
    public static final String ID_FIELD = "chunk_id";
    public static final String VECTOR_FIELD = "vector";
    public static final int DIMENSION = 512;
    public static final int ID_MAX_LENGTH = 160;
    public static final int NLIST = 128;
    public static final String METRIC = "COSINE";
    public static final String INDEX = "IVF_FLAT";

    private final DataSource dataSource;
    private final IntelligenceGateway gateway;
    private final MilvusServiceClient client;
    private final int nlist;
    private final double minScore;
    private volatile boolean initialized;

    @Autowired
    public KnowledgeVectorStore(@Autowired(required = false) DataSource dataSource,
                                IntelligenceGateway gateway,
                                @Autowired(required = false) MilvusServiceClient client,
                                @Value("${farmap.ai.retrieval.knowledge.milvus-nlist:128}") int nlist,
                                @Value("${farmap.ai.retrieval.knowledge.min-score:0.45}") double minScore) {
        this.dataSource = dataSource;
        this.gateway = gateway;
        this.client = client;
        this.nlist = nlist;
        this.minScore = minScore;
    }

    /** Compatibility constructor for unit tests and non-Spring callers. */
    public KnowledgeVectorStore(DataSource dataSource, IntelligenceGateway gateway,
                                MilvusServiceClient client, int nlist) {
        this(dataSource, gateway, client, nlist, 0.45D);
    }

    public boolean isConfigured() { return client != null; }

    /**
     * Initialize without ever dropping, clearing, or recreating an existing
     * collection. Existing schema and index are read-only validated.
     */
    public synchronized Map<String, Object> initialize(boolean execute) {
        Map<String, Object> report = baseReport(execute);
        if (client == null) {
            report.put("status", "UNAVAILABLE");
            report.put("reason", "MILVUS_CLIENT_UNAVAILABLE");
            return report;
        }
        try {
            R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION).build());
            require(has, "hasCollection");
            boolean exists = Boolean.TRUE.equals(has.getData());
            report.put("collectionExists", exists);
            boolean created = false;
            if (!exists) {
                if (!execute) {
                    report.put("status", "PLAN");
                    return report;
                }
                createCollection();
                created = true;
                report.put("collectionExists", true);
                report.put("created", true);
            }

            validateSchema();
            if (created) createIndex();
            validateIndex();
            if (execute) loadCollection();
            long rows = rowCount();
            report.put("rowCount", rows);
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
        if (!"READY".equals(state.get("status"))) {
            throw KnowledgeBackendException.persistence("知识向量集合不可用，索引写入已拒绝", null);
        }
        List<String> ids = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();
        List<String> versionIds = new ArrayList<>();
        List<Long> indexes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        List<String> classifications = new ArrayList<>();
        for (EmbeddingRow row : rows) {
            if (row == null || row.chunkId == null || row.vector == null || row.vector.size() != DIMENSION) {
                throw KnowledgeBackendException.validation("embedding dimension must be 512");
            }
            ids.add(row.chunkId); vectors.add(row.vector); sourceIds.add(row.sourceId); documentIds.add(row.documentId);
            versionIds.add(row.versionId); indexes.add((long) row.chunkIndex); hashes.add(row.contentHash);
            classifications.add(row.classification);
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
        R<MutationResult> result = client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION).withFields(fields).build());
        if (result == null || result.getStatus() != R.Status.Success.getCode()) {
            throw KnowledgeBackendException.persistence("知识向量写入 Milvus 失败", null);
        }
    }

    public List<KnowledgeVectorHit> search(String query, Long actorId, int topK) {
        return searchResult(query, actorId, topK).hits;
    }

    public SearchResult searchResult(String query, Long actorId, int topK) {
        if (query == null || query.isBlank() || client == null) {
            return new SearchResult(client == null ? "UNAVAILABLE" : "READY_EMPTY", Collections.emptyList());
        }
        int bounded = Math.max(1, Math.min(20, topK));
        Map<String, Object> state = initialize(false);
        if (!"READY".equals(state.get("status"))) {
            return new SearchResult("UNAVAILABLE", Collections.emptyList());
        }
        try {
            if (!initialized) {
                require(client.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION).build()), "loadCollection");
                initialized = true;
            }
            if (gateway == null) return new SearchResult("UNAVAILABLE", Collections.emptyList());
            Map<String, Object> embedding = gateway.embedText(query, "knowledge-retrieval", "knowledge-retrieval");
            List<Float> vector = readVector(embedding);
            SearchParam param = SearchParam.newBuilder().withCollectionName(COLLECTION)
                    .withMetricType(MetricType.COSINE).withOutFields(Collections.singletonList(ID_FIELD))
                    .withTopK(bounded).withVectors(Collections.singletonList(vector)).withVectorFieldName(VECTOR_FIELD)
                    .withParams("{\"nprobe\": 10}").build();
            R<SearchResults> response = client.search(param);
            if (response == null || response.getStatus() != R.Status.Success.getCode()
                    || response.getData() == null) {
                return new SearchResult("UNAVAILABLE", Collections.emptyList());
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            List<KnowledgeVectorHit> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : scores) {
                Object id = score.get(ID_FIELD);
                if (id == null) continue;
                double similarity = Math.max(-1D, Math.min(1D, score.getScore()));
                if (similarity < minScore) continue;
                KnowledgeVectorHit hit = findChunk(String.valueOf(id), actorId);
                if (hit != null) {
                    hit.score = similarity;
                    hits.add(hit);
                }
            }
            return new SearchResult(hits.isEmpty() ? "READY_EMPTY" : "READY", hits);
        } catch (Exception error) {
            log.warn("Knowledge semantic retrieval unavailable: {}", error.getClass().getSimpleName());
            return new SearchResult("UNAVAILABLE", Collections.emptyList());
        }
    }

    public Map<String, Object> health() {
        if (client == null) return Map.of("status", "UNAVAILABLE", "collection", COLLECTION,
                "reason", "MILVUS_CLIENT_UNAVAILABLE");
        try {
            Map<String, Object> value = initialize(false);
            if ("PLAN".equals(value.get("status"))) {
                value.put("status", "UNAVAILABLE");
                value.put("reason", "COLLECTION_MISSING");
            } else if ("READY".equals(value.get("status")) && !textEmbeddingReady()) {
                value.put("status", "UNAVAILABLE");
                value.put("reason", "BGE_UNAVAILABLE");
            } else if ("READY".equals(value.get("status")) && Long.valueOf(0L).equals(value.get("rowCount"))) {
                value.put("status", "READY_EMPTY");
            }
            return value;
        } catch (Exception error) {
            return Map.of("status", "UNAVAILABLE", "collection", COLLECTION,
                    "error", error.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean textEmbeddingReady() {
        if (gateway == null) return false;
        try {
            Map<String, Object> health = gateway.health();
            Object embedding = health.get("textEmbedding");
            if (embedding instanceof Map<?, ?> details) {
                return "READY".equals(String.valueOf(details.get("status")));
            }
            return "READY".equals(String.valueOf(embedding));
        } catch (Exception error) {
            return false;
        }
    }

    private Map<String, Object> baseReport(boolean execute) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("collection", COLLECTION); report.put("dimension", DIMENSION); report.put("metric", METRIC);
        report.put("index", INDEX); report.put("nlist", nlist); report.put("mode", execute ? "EXECUTE" : "DRY_RUN");
        report.put("created", false); report.put("minScore", minScore);
        return report;
    }

    private void createCollection() {
        FieldType id = FieldType.newBuilder().withName(ID_FIELD).withDataType(DataType.VarChar)
                .withMaxLength(ID_MAX_LENGTH).withPrimaryKey(true).withAutoID(false).build();
        FieldType vector = FieldType.newBuilder().withName(VECTOR_FIELD).withDataType(DataType.FloatVector)
                .withDimension(DIMENSION).build();
        FieldType source = varchar("source_id", 112);
        FieldType document = varchar("document_id", 112);
        FieldType version = varchar("version_id", 112);
        FieldType index = FieldType.newBuilder().withName("chunk_index").withDataType(DataType.Int64).build();
        FieldType hash = varchar("content_hash", 64);
        FieldType classification = varchar("classification", 64);
        R<RpcStatus> result = client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).withDescription("FarMap admin-curated knowledge chunks, BGE text embeddings")
                .addFieldType(id).addFieldType(vector).addFieldType(source).addFieldType(document).addFieldType(version)
                .addFieldType(index).addFieldType(hash).addFieldType(classification).build());
        require(result, "createCollection");
    }

    private FieldType varchar(String name, int maxLength) {
        return FieldType.newBuilder().withName(name).withDataType(DataType.VarChar).withMaxLength(maxLength).build();
    }

    private void validateSchema() {
        R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        require(response, "describeCollection");
        List<String> problems = new ArrayList<>();
        var fields = response.getData().getSchema().getFieldsList();
        validateVarChar(fields, ID_FIELD, ID_MAX_LENGTH, true, problems);
        var vector = fields.stream().filter(field -> VECTOR_FIELD.equals(field.getName())).findFirst().orElse(null);
        if (vector == null) problems.add("missing " + VECTOR_FIELD);
        else {
            if (vector.getDataType() != DataType.FloatVector) problems.add(VECTOR_FIELD + " type mismatch");
            if (!String.valueOf(DIMENSION).equals(typeParam(vector.getTypeParamsList(), "dim"))) problems.add(VECTOR_FIELD + " dim mismatch");
        }
        validateVarChar(fields, "source_id", 112, false, problems);
        validateVarChar(fields, "document_id", 112, false, problems);
        validateVarChar(fields, "version_id", 112, false, problems);
        validateVarChar(fields, "content_hash", 64, false, problems);
        validateVarChar(fields, "classification", 64, false, problems);
        var chunkIndex = fields.stream().filter(field -> "chunk_index".equals(field.getName())).findFirst().orElse(null);
        if (chunkIndex == null || chunkIndex.getDataType() != DataType.Int64) problems.add("chunk_index type mismatch");
        if (!problems.isEmpty()) throw new SchemaMismatchException(problems);
    }

    private void validateVarChar(List<io.milvus.grpc.FieldSchema> fields, String name, int maxLength,
                                 boolean primaryKey, List<String> problems) {
        var field = fields.stream().filter(item -> name.equals(item.getName())).findFirst().orElse(null);
        if (field == null) { problems.add("missing " + name); return; }
        if (field.getDataType() != DataType.VarChar) problems.add(name + " type mismatch");
        if (!String.valueOf(maxLength).equals(typeParam(field.getTypeParamsList(), "max_length"))) problems.add(name + " max_length mismatch");
        if (primaryKey && (!field.getIsPrimaryKey() || field.getAutoID())) problems.add(name + " primary key mismatch");
    }

    private void createIndex() {
        R<RpcStatus> result = client.createIndex(CreateIndexParam.newBuilder().withCollectionName(COLLECTION)
                .withFieldName(VECTOR_FIELD).withIndexType(IndexType.IVF_FLAT).withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\": " + nlist + "}").withSyncMode(Boolean.TRUE).build());
        require(result, "createIndex");
    }

    private void validateIndex() {
        R<DescribeIndexResponse> response = client.describeIndex(DescribeIndexParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        require(response, "describeIndex");
        if (response.getData().getIndexDescriptionsCount() == 0) throw new SchemaMismatchException(List.of("missing vector index"));
        IndexDescription index = response.getData().getIndexDescriptionsList().stream()
                .filter(item -> VECTOR_FIELD.equals(item.getFieldName())).findFirst()
                .orElse(response.getData().getIndexDescriptions(0));
        Map<String, String> params = new LinkedHashMap<>();
        for (KeyValuePair param : index.getParamsList()) params.put(param.getKey(), param.getValue());
        List<String> problems = new ArrayList<>();
        if (!VECTOR_FIELD.equals(index.getFieldName())) problems.add("index field mismatch");
        if (!INDEX.equalsIgnoreCase(params.getOrDefault("index_type", ""))) problems.add("index_type mismatch");
        if (!METRIC.equalsIgnoreCase(params.getOrDefault("metric_type", ""))) problems.add("metric_type mismatch");
        String extra = params.getOrDefault("params", "");
        if (!extra.contains("\"nlist\":" + nlist) && !extra.contains("\"nlist\": " + nlist)) problems.add("nlist mismatch");
        if (!problems.isEmpty()) throw new SchemaMismatchException(problems);
    }

    private void loadCollection() {
        require(client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build()), "loadCollection");
    }

    private long rowCount() {
        R<GetCollectionStatisticsResponse> response = client.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder().withCollectionName(COLLECTION).build());
        require(response, "getCollectionStatistics");
        for (KeyValuePair stat : response.getData().getStatsList()) {
            if ("row_count".equalsIgnoreCase(stat.getKey())) return Long.parseLong(stat.getValue());
        }
        throw new IllegalStateException("Milvus row_count is missing");
    }

    private KnowledgeVectorHit findChunk(String chunkId, Long actorId) throws Exception {
        if (dataSource == null) return null;
        String sql = "SELECT c.chunk_id,c.source_id,s.name,c.document_id,d.title,c.version_id,c.content,c.content_hash,c.classification " +
                "FROM knowledge_chunks c JOIN knowledge_sources s ON s.source_id=c.source_id " +
                "JOIN knowledge_documents d ON d.document_id=c.document_id " +
                "JOIN knowledge_ingestions i ON i.version_id=c.version_id " +
                "WHERE c.chunk_id=? AND s.source_type='ADMIN_CURATED' AND s.status='ACTIVE' AND d.status='ACTIVE' " +
                "AND i.status='READY' AND d.current_version_id=c.version_id " +
                "AND (s.owner_user_id IS NULL OR s.owner_user_id=?) AND (d.owner_user_id IS NULL OR d.owner_user_id=?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, chunkId);
            if (actorId == null) { p.setNull(2, java.sql.Types.BIGINT); p.setNull(3, java.sql.Types.BIGINT); }
            else { p.setLong(2, actorId); p.setLong(3, actorId); }
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return new KnowledgeVectorHit(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9));
            }
        }
    }

    private List<Float> readVector(Map<String, Object> embedding) {
        Object raw = embedding == null ? null : embedding.get("vector");
        if (!(raw instanceof List<?> values) || values.size() != DIMENSION) {
            throw new IllegalStateException("AI_CONTRACT_INVALID: embedding dimension must be 512");
        }
        List<Float> vector = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Number number)) throw new IllegalStateException("AI_CONTRACT_INVALID: vector value is not numeric");
            float item = number.floatValue();
            if (Float.isNaN(item) || Float.isInfinite(item)) throw new IllegalStateException("AI_CONTRACT_INVALID: invalid vector value");
            vector.add(item);
        }
        return vector;
    }

    private String typeParam(List<KeyValuePair> params, String key) {
        return params.stream().filter(param -> key.equals(param.getKey())).map(KeyValuePair::getValue).findFirst().orElse("");
    }

    private void require(R<?> response, String operation) {
        if (response == null || response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("MILVUS_" + operation.toUpperCase() + "_FAILED: "
                    + (response == null ? "null" : response.getMessage()));
        }
    }

    public static final class SearchResult {
        private final String status;
        private final List<KnowledgeVectorHit> hits;
        public SearchResult(String status, List<KnowledgeVectorHit> hits) { this.status = status; this.hits = hits; }
        public String getStatus() { return status; }
        public List<KnowledgeVectorHit> getHits() { return hits; }
    }

    public static final class EmbeddingRow {
        public final String chunkId, sourceId, documentId, versionId, contentHash, classification;
        public final int chunkIndex;
        public final List<Float> vector;
        public EmbeddingRow(String chunkId, List<Float> vector) {
            this(chunkId, null, null, null, 0, null, null, vector);
        }
        public EmbeddingRow(String chunkId, String sourceId, String documentId, String versionId, int chunkIndex,
                            String contentHash, String classification, List<Float> vector) {
            this.chunkId = chunkId; this.sourceId = sourceId; this.documentId = documentId; this.versionId = versionId;
            this.chunkIndex = chunkIndex; this.contentHash = contentHash; this.classification = classification; this.vector = vector;
        }
    }

    public static final class KnowledgeVectorHit {
        public final String chunkId, sourceId, sourceName, documentId, documentTitle, versionId, content, contentHash, classification;
        public double score;
        KnowledgeVectorHit(String a, String b, String c, String d, String e, String f, String g, String h, String i) {
            chunkId = a; sourceId = b; sourceName = c; documentId = d; documentTitle = e; versionId = f;
            content = g; contentHash = h; classification = i;
        }
    }

    public static final class SchemaMismatchException extends IllegalStateException {
        public SchemaMismatchException(List<String> problems) { super("SCHEMA_MISMATCH: " + String.join(", ", problems)); }
    }
}

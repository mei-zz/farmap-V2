package com.mei.zhgy.service.rag;

import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.MutationResult;
import io.milvus.param.RpcStatus;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Indexes user-provided camera screenshots and field detail photos separately
 * from expert historical cases. The image itself is stored in COS; Milvus
 * stores only the CLIP vector and this table stores the auditable provenance.
 */
@Service
@Slf4j
public class UploadedImageAssetIndexService {
    public static final String COLLECTION = "farmap_multimodal_image_assets_v1";
    private static final String ASSET_ID = "asset_id";
    private static final String VECTOR = "vector";
    private static final int DIMENSION = 512;

    private final JdbcTemplate jdbc;
    private final ImageEmbeddingProvider embeddings;
    private final MilvusServiceClient milvus;
    private volatile boolean schemaChecked;

    @Autowired
    public UploadedImageAssetIndexService(JdbcTemplate jdbc,
                                          ImageEmbeddingProvider embeddings,
                                          @Autowired(required = false) MilvusServiceClient milvus) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.milvus = milvus;
    }

    public IndexResult index(String imageUrl, Metadata metadata) {
        if (imageUrl == null || imageUrl.isBlank()) throw new IllegalArgumentException("imageUrl 不能为空");
        if (milvus == null) return IndexResult.pending("MILVUS_UNAVAILABLE");
        EmbeddingProvider.EmbeddingResult embedding = embeddings.embedImage(imageUrl);
        if (embedding == null || embedding.getVector() == null || embedding.getVector().size() != DIMENSION) {
            return IndexResult.pending("IMAGE_EMBEDDING_UNAVAILABLE");
        }
        String assetId = "asset-" + UUID.randomUUID();
        try {
            ensureSchema();
            ensureCollection();
            List<InsertParam.Field> fields = Arrays.asList(
                    new InsertParam.Field(ASSET_ID, Collections.singletonList(assetId)),
                    new InsertParam.Field(VECTOR, Collections.singletonList(embedding.getVector())));
            R<MutationResult> result = milvus.insert(InsertParam.newBuilder()
                    .withCollectionName(COLLECTION).withFields(fields).build());
            if (result == null || result.getStatus() != R.Status.Success.getCode()) {
                return IndexResult.pending("MILVUS_INSERT_FAILED");
            }
            milvus.flush(FlushParam.newBuilder().addCollectionName(COLLECTION).withSyncFlush(Boolean.TRUE).build());
            Metadata value = metadata == null ? new Metadata(null, null, null, null, "manual_upload", null, null, null) : metadata;
            jdbc.update("INSERT INTO farmap_image_assets(asset_id,farm_id,field_id,point_id,camera_id,source,object_key,image_url,file_name,content_type,created_at,vector_model,vector_dimension,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    assetId, value.farmId(), value.fieldId(), value.pointId(), value.cameraId(), value.source(), value.objectKey(), imageUrl,
                    value.fileName(), value.contentType(), Timestamp.from(Instant.now()), embedding.getModel(), embedding.getDimension(), "READY");
            return new IndexResult(assetId, "READY", embedding.getModel());
        } catch (Exception error) {
            log.warn("用户图片向量索引失败，保留图片作为诊断输入: {}", error.getClass().getSimpleName());
            return IndexResult.pending(error.getClass().getSimpleName());
        }
    }

    public List<SimilarAsset> search(String imageUrl, Integer farmId, String fieldId, int topK) {
        if (milvus == null || imageUrl == null || imageUrl.isBlank()) return Collections.emptyList();
        EmbeddingProvider.EmbeddingResult embedding = embeddings.embedImage(imageUrl);
        if (embedding == null || embedding.getVector() == null || embedding.getVector().size() != DIMENSION) return Collections.emptyList();
        try {
            ensureSchema();
            ensureCollection();
            SearchParam search = SearchParam.newBuilder().withCollectionName(COLLECTION)
                    .withMetricType(io.milvus.param.MetricType.L2).withOutFields(Collections.singletonList(ASSET_ID))
                    .withTopK(Math.max(1, Math.min(topK * 4, 20))).withVectors(Collections.singletonList(embedding.getVector()))
                    .withVectorFieldName(VECTOR).withParams("{\"nprobe\": 10}").build();
            R<SearchResults> result = milvus.search(search);
            if (result == null || result.getStatus() != R.Status.Success.getCode()) return Collections.emptyList();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
            List<SearchResultsWrapper.IDScore> ids = wrapper.getIDScore(0);
            List<SimilarAsset> output = new ArrayList<>();
            for (SearchResultsWrapper.IDScore id : ids) {
                String assetId = String.valueOf(id.get(ASSET_ID));
                Map<String, Object> row = jdbc.query("SELECT asset_id,farm_id,field_id,point_id,camera_id,source,image_url,file_name,created_at FROM farmap_image_assets WHERE asset_id=?",
                        ps -> ps.setString(1, assetId), rs -> rs.next() ? row(rs) : null);
                if (row == null) continue;
                if (farmId != null && row.get("farm_id") instanceof Number n && n.intValue() != farmId) continue;
                if (fieldId != null && row.get("field_id") != null && !fieldId.equals(String.valueOf(row.get("field_id")))) continue;
                double distance = id.getScore();
                output.add(new SimilarAsset(row, 1D / (1D + Math.max(0D, distance))));
                if (output.size() >= topK) break;
            }
            return output;
        } catch (Exception error) {
            log.debug("用户图片相似检索不可用: {}", error.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    public Map<String, Object> health() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("collection", COLLECTION);
        output.put("dimension", DIMENSION);
        output.put("status", "UNAVAILABLE");
        if (milvus == null) return output;
        try {
            ensureSchema();
            ensureCollection();
            R<GetCollectionStatisticsResponse> stats = milvus.getCollectionStatistics(GetCollectionStatisticsParam.newBuilder().withCollectionName(COLLECTION).build());
            long count = 0;
            if (stats != null && stats.getData() != null) for (KeyValuePair item : stats.getData().getStatsList()) if ("row_count".equalsIgnoreCase(item.getKey())) count = Long.parseLong(item.getValue());
            output.put("rowCount", count);
            output.put("status", "READY");
            return output;
        } catch (Exception error) {
            output.put("error", error.getClass().getSimpleName());
            return output;
        }
    }

    private void ensureSchema() {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS farmap_image_assets (asset_id VARCHAR(112) NOT NULL,farm_id INT NULL,field_id VARCHAR(96) NULL,point_id VARCHAR(128) NULL,camera_id VARCHAR(128) NULL,source VARCHAR(32) NOT NULL,object_key VARCHAR(512) NULL,image_url TEXT NOT NULL,file_name VARCHAR(255) NULL,content_type VARCHAR(64) NULL,created_at TIMESTAMP(3) NOT NULL,vector_model VARCHAR(128) NULL,vector_dimension INT NULL,status VARCHAR(32) NOT NULL,PRIMARY KEY(asset_id),INDEX idx_image_assets_farm(farm_id),INDEX idx_image_assets_field(field_id))");
            schemaChecked = true;
        }
    }

    private synchronized void ensureCollection() {
        R<Boolean> existing = milvus.hasCollection(HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
        if (existing != null && Boolean.TRUE.equals(existing.getData())) {
            milvus.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            return;
        }
        FieldType id = FieldType.newBuilder().withName(ASSET_ID).withDataType(DataType.VarChar).withMaxLength(112).withPrimaryKey(true).withAutoID(false).build();
        FieldType vector = FieldType.newBuilder().withName(VECTOR).withDataType(DataType.FloatVector).withDimension(DIMENSION).build();
        R<RpcStatus> created = milvus.createCollection(CreateCollectionParam.newBuilder().withCollectionName(COLLECTION).withDescription("FarMap user image evidence vectors").addFieldType(id).addFieldType(vector).build());
        if (created == null || created.getStatus() != R.Status.Success.getCode()) throw new IllegalStateException("image asset collection create failed");
        milvus.createIndex(CreateIndexParam.newBuilder().withCollectionName(COLLECTION).withFieldName(VECTOR).withIndexType(io.milvus.param.IndexType.IVF_FLAT).withMetricType(io.milvus.param.MetricType.L2).withExtraParam("{\"nlist\": 128}").withSyncMode(Boolean.TRUE).build());
        milvus.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("asset_id", rs.getString("asset_id")); row.put("farm_id", rs.getObject("farm_id")); row.put("field_id", rs.getString("field_id"));
        row.put("point_id", rs.getString("point_id")); row.put("camera_id", rs.getString("camera_id")); row.put("source", rs.getString("source"));
        row.put("image_url", rs.getString("image_url")); row.put("file_name", rs.getString("file_name")); row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    public record Metadata(Integer farmId, String fieldId, String pointId, String cameraId, String source,
                           String objectKey, String fileName, String contentType) {}
    public record IndexResult(String assetId, String status, String model) {
        static IndexResult pending(String reason) { return new IndexResult(null, "PENDING:" + reason, null); }
    }
    public record SimilarAsset(Map<String, Object> asset, double similarity) {}
}

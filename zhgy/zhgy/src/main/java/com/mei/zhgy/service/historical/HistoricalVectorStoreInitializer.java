package com.mei.zhgy.service.historical;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.IndexDescription;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit, idempotent bootstrap for the historical image vector store.
 *
 * This class never drops or alters an existing collection. An existing
 * collection must match the contract or initialization stops with
 * SCHEMA_MISMATCH.
 */
@Component
public class HistoricalVectorStoreInitializer {
    public static final String COLLECTION = "farmap_image_vectors_new";
    public static final String ID_FIELD = "request_id";
    public static final String VECTOR_FIELD = "vector";
    public static final int DIMENSION = 512;
    public static final int ID_MAX_LENGTH = 64;
    public static final int NLIST = 128;
    public static final String METRIC = "L2";
    public static final String INDEX = "IVF_FLAT";

    private final MilvusServiceClient client;

    public HistoricalVectorStoreInitializer(MilvusServiceClient client) {
        this.client = client;
    }

    /**
     * Inspect the store. Missing collections are only created when execute is
     * true. Existing collections are always read-only validated.
     */
    public Map<String, Object> initialize(boolean execute) {
        Map<String, Object> report = baseReport(execute);
        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        requireSuccess(has, "hasCollection");
        boolean exists = Boolean.TRUE.equals(has.getData());
        report.put("collectionExists", exists);

        boolean created = false;
        if (!exists) {
            if (!execute) {
                report.put("status", "PLAN");
                report.put("historical", "EMPTY");
                report.put("rowCount", 0L);
                return report;
            }
            createCollection();
            created = true;
            report.put("collectionExists", true);
            report.put("created", true);
        }

        validateSchema();
        if (created) {
            createIndex();
        } else {
            validateIndex();
        }
        if (execute) loadCollection();
        long rowCount = rowCount();
        report.put("rowCount", rowCount);
        report.put("status", "READY");
        report.put("historical", rowCount == 0 ? "EMPTY" : "READY");
        report.put("retriever", rowCount == 0 ? "READY_EMPTY" : "READY");
        return report;
    }

    private Map<String, Object> baseReport(boolean execute) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("collection", COLLECTION);
        report.put("dimension", DIMENSION);
        report.put("metric", METRIC);
        report.put("index", INDEX);
        report.put("nlist", NLIST);
        report.put("mode", execute ? "EXECUTE" : "DRY_RUN");
        report.put("created", false);
        return report;
    }

    private void createCollection() {
        FieldType id = FieldType.newBuilder()
                .withName(ID_FIELD)
                .withDataType(DataType.VarChar)
                .withMaxLength(ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();
        FieldType vector = FieldType.newBuilder()
                .withName(VECTOR_FIELD)
                .withDataType(DataType.FloatVector)
                .withDimension(DIMENSION)
                .build();
        R<RpcStatus> result = client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("Confirmed expert historical case image vectors")
                .addFieldType(id)
                .addFieldType(vector)
                .build());
        requireSuccess(result, "createCollection");
    }

    private void validateSchema() {
        R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        requireSuccess(response, "describeCollection");
        List<String> problems = new ArrayList<>();
        var fields = response.getData().getSchema().getFieldsList();
        var id = fields.stream().filter(field -> ID_FIELD.equals(field.getName())).findFirst().orElse(null);
        var vector = fields.stream().filter(field -> VECTOR_FIELD.equals(field.getName())).findFirst().orElse(null);
        if (id == null) problems.add("missing " + ID_FIELD);
        else {
            if (id.getDataType() != DataType.VarChar) problems.add(ID_FIELD + " type=" + id.getDataType());
            if (!id.getIsPrimaryKey()) problems.add(ID_FIELD + " primaryKey=false");
            if (id.getAutoID()) problems.add(ID_FIELD + " autoID=true");
            if (!String.valueOf(ID_MAX_LENGTH).equals(typeParam(id.getTypeParamsList(), "max_length"))) {
                problems.add(ID_FIELD + " max_length mismatch");
            }
        }
        if (vector == null) problems.add("missing " + VECTOR_FIELD);
        else {
            if (vector.getDataType() != DataType.FloatVector) problems.add(VECTOR_FIELD + " type=" + vector.getDataType());
            if (!String.valueOf(DIMENSION).equals(typeParam(vector.getTypeParamsList(), "dim"))) {
                problems.add(VECTOR_FIELD + " dim mismatch");
            }
        }
        if (!problems.isEmpty()) {
            throw new SchemaMismatchException(problems);
        }
    }

    private void createIndex() {
        R<RpcStatus> result = client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName(VECTOR_FIELD)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.L2)
                .withExtraParam("{\"nlist\": " + NLIST + "}")
                .withSyncMode(Boolean.TRUE)
                .build());
        requireSuccess(result, "createIndex");
    }

    private void validateIndex() {
        R<DescribeIndexResponse> response = client.describeIndex(DescribeIndexParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        requireSuccess(response, "describeIndex");
        if (response.getData().getIndexDescriptionsCount() == 0) {
            throw new SchemaMismatchException(List.of("missing vector index"));
        }
        IndexDescription index = response.getData().getIndexDescriptionsList().stream()
                .filter(item -> VECTOR_FIELD.equals(item.getFieldName()))
                .findFirst().orElse(response.getData().getIndexDescriptions(0));
        Map<String, String> params = new LinkedHashMap<>();
        for (KeyValuePair param : index.getParamsList()) params.put(param.getKey(), param.getValue());
        List<String> problems = new ArrayList<>();
        if (!VECTOR_FIELD.equals(index.getFieldName())) problems.add("index field mismatch");
        if (!INDEX.equalsIgnoreCase(params.getOrDefault("index_type", ""))) problems.add("index_type mismatch");
        if (!METRIC.equalsIgnoreCase(params.getOrDefault("metric_type", ""))) problems.add("metric_type mismatch");
        String extra = params.getOrDefault("params", "");
        if (!extra.contains("\"nlist\":128") && !extra.contains("\"nlist\": 128")) problems.add("nlist mismatch");
        if (!problems.isEmpty()) throw new SchemaMismatchException(problems);
    }

    private void loadCollection() {
        R<RpcStatus> result = client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        requireSuccess(result, "loadCollection");
    }

    private long rowCount() {
        R<GetCollectionStatisticsResponse> response = client.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder().withCollectionName(COLLECTION).build());
        requireSuccess(response, "getCollectionStatistics");
        for (KeyValuePair stat : response.getData().getStatsList()) {
            if ("row_count".equalsIgnoreCase(stat.getKey())) return Long.parseLong(stat.getValue());
        }
        return 0L;
    }

    private String typeParam(List<KeyValuePair> params, String key) {
        return params.stream().filter(param -> key.equals(param.getKey())).map(KeyValuePair::getValue).findFirst().orElse("");
    }

    private void requireSuccess(R<?> response, String operation) {
        if (response == null || response.getStatus() != R.Status.Success.getCode()) {
            String message = response == null ? "null response" : response.getMessage();
            throw new IllegalStateException("MILVUS_" + operation.toUpperCase() + "_FAILED: " + message);
        }
    }

    public static final class SchemaMismatchException extends IllegalStateException {
        public SchemaMismatchException(List<String> problems) {
            super("SCHEMA_MISMATCH: " + String.join(", ", problems));
        }
    }
}

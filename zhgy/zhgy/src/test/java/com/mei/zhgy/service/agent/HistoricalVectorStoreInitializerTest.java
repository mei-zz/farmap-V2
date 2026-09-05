package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.CollectionSchema;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoricalVectorStoreInitializerTest {
    @Test void dryRunPlansMissingCollectionWithoutCreating() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(false));

        var report = new HistoricalVectorStoreInitializer(client).initialize(false);

        assertEquals("PLAN", report.get("status"));
        assertEquals(false, report.get("collectionExists"));
        assertEquals(512, report.get("dimension"));
        verify(client, never()).createCollection(any(CreateCollectionParam.class));
        verify(client, never()).loadCollection(any());
    }

    @Test void executeCreatesAndReportsEmptyReadyCollection() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(false));
        when(client.createCollection(any(CreateCollectionParam.class))).thenReturn(R.success());
        when(client.createIndex(any())).thenReturn(R.success());
        when(client.loadCollection(any())).thenReturn(R.success());
        GetCollectionStatisticsResponse stats = GetCollectionStatisticsResponse.newBuilder()
                .addStats(KeyValuePair.newBuilder().setKey("row_count").setValue("0").build()).build();
        when(client.describeCollection(any())).thenReturn(R.success(schema(512)));
        when(client.getCollectionStatistics(any())).thenReturn(R.success(stats));

        var report = new HistoricalVectorStoreInitializer(client).initialize(true);

        assertEquals("READY", report.get("status"));
        assertEquals("EMPTY", report.get("historical"));
        assertEquals("READY_EMPTY", report.get("retriever"));
        assertEquals(0L, report.get("rowCount"));
        verify(client).createCollection(any(CreateCollectionParam.class));
        verify(client).createIndex(any());
        verify(client).loadCollection(any());
    }

    static DescribeCollectionResponse schema(int dimension) {
        FieldSchema id = FieldSchema.newBuilder().setName("request_id").setDataType(DataType.VarChar)
                .setIsPrimaryKey(true).setAutoID(false)
                .addTypeParams(KeyValuePair.newBuilder().setKey("max_length").setValue("64").build()).build();
        FieldSchema vector = FieldSchema.newBuilder().setName("vector").setDataType(DataType.FloatVector)
                .addTypeParams(KeyValuePair.newBuilder().setKey("dim").setValue(String.valueOf(dimension)).build()).build();
        return DescribeCollectionResponse.newBuilder().setSchema(CollectionSchema.newBuilder()
                .setName("farmap_image_vectors_new").addFields(id).addFields(vector).build()).build();
    }
}

package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.IndexDescription;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoricalVectorStoreSchemaTest {
    @Test void existingDimensionMismatchStopsWithoutMutation() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(HistoricalVectorStoreInitializerTest.schema(256)));

        assertThrows(HistoricalVectorStoreInitializer.SchemaMismatchException.class,
                () -> new HistoricalVectorStoreInitializer(client).initialize(false));
        verify(client, never()).dropCollection(any());
        verify(client, never()).createCollection(any(CreateCollectionParam.class));
    }

    @Test void existingIndexMetricMismatchStopsWithoutRebuild() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(HistoricalVectorStoreInitializerTest.schema(512)));
        IndexDescription index = IndexDescription.newBuilder().setFieldName("vector")
                .addParams(KeyValuePair.newBuilder().setKey("index_type").setValue("IVF_FLAT").build())
                .addParams(KeyValuePair.newBuilder().setKey("metric_type").setValue("IP").build())
                .addParams(KeyValuePair.newBuilder().setKey("params").setValue("{\"nlist\":128}").build()).build();
        DescribeIndexResponse indexes = DescribeIndexResponse.newBuilder().addIndexDescriptions(index).build();
        when(client.describeIndex(any())).thenReturn(R.success(indexes));

        assertThrows(HistoricalVectorStoreInitializer.SchemaMismatchException.class,
                () -> new HistoricalVectorStoreInitializer(client).initialize(false));
        verify(client, never()).dropIndex(any());
        verify(client, never()).createIndex(any());
    }
}

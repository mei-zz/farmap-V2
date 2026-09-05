package com.mei.zhgy.service.agent;

import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductLoopRegressionTest {
    @Test void emptyHistoricalStateIsDataStateNotSystemFailure() {
        assertEquals("farmap_image_vectors_new", HistoricalVectorStoreInitializer.COLLECTION);
        assertEquals(512, HistoricalVectorStoreInitializer.DIMENSION);
        assertEquals("L2", HistoricalVectorStoreInitializer.METRIC);
        assertEquals("IVF_FLAT", HistoricalVectorStoreInitializer.INDEX);
        assertEquals(128, HistoricalVectorStoreInitializer.NLIST);
    }
}

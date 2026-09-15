package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeIngestionServiceTest {
    @Test
    void unauthenticatedCallerCannotReachDatabaseOrFilesystemImport() {
        KnowledgeBackendException error = assertThrows(KnowledgeBackendException.class, () ->
                new KnowledgeIngestionService().importDocument(
                        "Agriculture", "Guide", "guide.txt", "safe text".getBytes(StandardCharsets.UTF_8), null));

        assertEquals("FORBIDDEN", error.getCode());
    }

    @Test
    void ingestionLimitIsExposedAsTheSingleFilePolicy() {
        assertEquals(10 * 1024 * 1024, KnowledgeIngestionService.MAX_FILE_BYTES);
    }
}

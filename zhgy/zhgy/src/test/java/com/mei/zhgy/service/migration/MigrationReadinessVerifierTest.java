package com.mei.zhgy.service.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationReadinessVerifierTest {
    @Test
    void noDatasourceIsNotReadyAndVerifierNeverFallsBack() {
        MigrationReadinessVerifier verifier = new MigrationReadinessVerifier(null);
        var report = verifier.verify();
        assertEquals("NOT_READY", report.get("status"));
        assertEquals("DATASOURCE_NOT_CONFIGURED", report.get("reason"));
        assertFalse(verifier.isReady());
    }

    @Test
    void requiredBoundariesAreDeclaredOnce() {
        assertTrue(MigrationReadinessVerifier.requiredColumns().containsKey("agent_diagnoses"));
        assertTrue(MigrationReadinessVerifier.requiredColumns().containsKey("knowledge_versions"));
        assertTrue(MigrationReadinessVerifier.requiredColumns().get("knowledge_ingestions").contains("raw_storage_key"));
        assertTrue(MigrationReadinessVerifier.requiredColumns().get("knowledge_ingestions").contains("completed_at"));
        assertTrue(MigrationReadinessVerifier.requiredColumns().containsKey("farm_map_points"));
        assertTrue(MigrationReadinessVerifier.requiredColumns().get("farm_map_points").contains("coordinate_reference_system"));
        assertTrue(MigrationReadinessVerifier.requiredColumns().get("copilot_long_term_memories").contains("source_conversation_id"));
        assertEquals(11, MigrationReadinessVerifier.requiredUniqueIndexes().size());
    }
}

package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBackendV1MysqlTest {
    @Test
    void persistsAndReloadsCurrentVersionWithMysql() {
        Assumptions.assumeTrue("mysql".equalsIgnoreCase(System.getenv().getOrDefault("FARMAP_AGENT_PERSISTENCE", "file")), "run with FARMAP_AGENT_PERSISTENCE=mysql");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        ds.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        ds.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        KnowledgeBackendService service = new KnowledgeBackendService(ds, null, false);
        String token = UUID.randomUUID().toString(); String sourceId = null, documentId = null;
        try {
            Map<String,Object> source = service.createSource(Map.of("name", "mysql-" + token), 42L); sourceId = String.valueOf(source.get("sourceId"));
            Map<String,Object> document = service.createDocument(sourceId, Map.of("title", "Drainage"), 42L); documentId = String.valueOf(document.get("documentId"));
            Map<String,Object> first = service.createVersion(documentId, Map.of("content", "inspect ditch"), 42L);
            service.activateVersion(documentId, String.valueOf(first.get("versionId")), 42L);
            Map<String,Object> second = service.createVersion(documentId, Map.of("content", "inspect ditch and pump"), 42L);
            service.activateVersion(documentId, String.valueOf(second.get("versionId")), 42L);
            Map<String,Object> reloaded = new KnowledgeBackendService(ds, null, false).getDocument(documentId, 42L);
            final String persistedDocumentId = documentId;
            assertEquals(second.get("versionId"), reloaded.get("currentVersionId"));
            assertTrue(service.retrieveCurrent(42L).stream().anyMatch(unit -> persistedDocumentId.equals(unit.documentId)
                    && second.get("versionId").equals(unit.versionId)), "the user's active version must be retrievable alongside packaged knowledge");
            assertThrows(KnowledgeBackendException.class, () -> service.getDocument(persistedDocumentId, 99L));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_versions WHERE document_id=?", Integer.class, documentId));
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_events WHERE entity_id=?", Integer.class, documentId) >= 1);
        } finally {
            if (documentId != null) { jdbc.update("DELETE FROM knowledge_events WHERE entity_id=?", documentId); jdbc.update("DELETE FROM knowledge_versions WHERE document_id=?", documentId); jdbc.update("DELETE FROM knowledge_documents WHERE document_id=?", documentId); }
            if (sourceId != null) { jdbc.update("DELETE FROM knowledge_events WHERE entity_id=?", sourceId); jdbc.update("DELETE FROM knowledge_sources WHERE source_id=?", sourceId); }
        }
    }
}

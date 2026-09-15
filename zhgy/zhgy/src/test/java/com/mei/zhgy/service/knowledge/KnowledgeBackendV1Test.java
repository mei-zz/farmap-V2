package com.mei.zhgy.service.knowledge;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBackendV1Test {
    @Test
    void versionsAreStableAppendOnlyAndCurrentPointerIsExplicit() {
        KnowledgeBackendService service = new KnowledgeBackendService();
        Map<String,Object> source = service.createSource(Map.of("name","Farm SOP","sourceType","USER_MANAGED"), 7L);
        Map<String,Object> document = service.createDocument(String.valueOf(source.get("sourceId")), Map.of("title","Drainage"), 7L);
        Map<String,Object> first = service.createVersion(String.valueOf(document.get("documentId")), Map.of("content","inspect ditch"), 7L);
        assertNull(document.get("currentVersionId"));
        service.activateVersion(String.valueOf(document.get("documentId")), String.valueOf(first.get("versionId")), 7L);
        Map<String,Object> duplicate = service.createVersion(String.valueOf(document.get("documentId")), Map.of("content","inspect ditch"), 7L);
        assertEquals(first.get("versionId"), duplicate.get("versionId"));
        Map<String,Object> second = service.createVersion(String.valueOf(document.get("documentId")), Map.of("content","inspect ditch and pump"), 7L);
        service.activateVersion(String.valueOf(document.get("documentId")), String.valueOf(second.get("versionId")), 7L);
        assertEquals(second.get("versionId"), service.getDocument(String.valueOf(document.get("documentId")), 7L).get("currentVersionId"));
        assertEquals(2, service.listVersions(String.valueOf(document.get("documentId")), 7L).size());
        assertEquals(1, service.retrieveCurrent(7L).size());
        assertTrue(service.retrieveCurrent(7L).get(0).versionId.equals(second.get("versionId")));
    }

    @Test
    void ownershipAndCrossDocumentActivationFailClosed() {
        KnowledgeBackendService service = new KnowledgeBackendService();
        Map<String,Object> a = service.createSource(Map.of("name","A"), 1L);
        Map<String,Object> b = service.createSource(Map.of("name","B"), 2L);
        Map<String,Object> da = service.createDocument(String.valueOf(a.get("sourceId")), Map.of("title","same"), 1L);
        Map<String,Object> db = service.createDocument(String.valueOf(b.get("sourceId")), Map.of("title","same"), 2L);
        Map<String,Object> v = service.createVersion(String.valueOf(da.get("documentId")), Map.of("content","a"), 1L);
        assertThrows(KnowledgeBackendException.class, () -> service.getSource(String.valueOf(a.get("sourceId")), 2L));
        assertThrows(KnowledgeBackendException.class, () -> service.activateVersion(String.valueOf(db.get("documentId")), String.valueOf(v.get("versionId")), 2L));
    }

    @Test
    void packagedRegistrationIsIdempotentAndReadOnly() {
        KnowledgeBackendService service = new KnowledgeBackendService();
        Map<String,Object> first = service.ensurePackagedKnowledge("classpath:test.txt", "a\r\nb");
        Map<String,Object> second = service.ensurePackagedKnowledge("classpath:test.txt", "a\nb");
        assertEquals(first.get("versionId"), second.get("versionId"));
        assertEquals(1, service.retrieveCurrent(9L).size());
        Map<String,Object> source = service.listSources(9L).get(0);
        assertThrows(KnowledgeBackendException.class, () -> service.setSourceStatus(String.valueOf(source.get("sourceId")), "DISABLED", 9L));
    }
}

package com.mei.zhgy.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.historical.HistoricalVectorStoreInitializer;
import com.mei.zhgy.vo.KnowledgeGraphResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a bounded read model from authoritative relational relationships.
 * No semantic nodes are generated and no vector collection is written.
 */
@Service
public class KnowledgeGraphService {
    private static final int MAX_VISIBLE_NODES = 150;
    private static final int MAX_DIAGNOSES = 50;
    private static final String QWEN_COLLECTION = QwenKnowledgeVectorStore.COLLECTION;
    private static final String QWEN_MODEL = QwenKnowledgeVectorStore.MODEL;

    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final QwenKnowledgeVectorStore qwenVectors;
    private final HistoricalVectorStoreInitializer historicalVectors;

    @Autowired
    public KnowledgeGraphService(
            @Autowired(required = false) DataSource dataSource,
            ObjectMapper mapper,
            @Autowired(required = false) QwenKnowledgeVectorStore qwenVectors,
            @Autowired(required = false) HistoricalVectorStoreInitializer historicalVectors) {
        this.dataSource = dataSource;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper.findAndRegisterModules();
        this.qwenVectors = qwenVectors;
        this.historicalVectors = historicalVectors;
    }

    public KnowledgeGraphResponse read(Long actorId, String nodeTypes, String sourceId, String documentId,
                                       String diagnosisId, Integer requestedLimit, Boolean includeEvidence) {
        if (actorId == null) throw KnowledgeBackendException.forbidden(null);
        if (dataSource == null) {
            return unavailable();
        }
        int limit = requestedLimit == null ? MAX_VISIBLE_NODES : Math.max(1, Math.min(requestedLimit, MAX_VISIBLE_NODES));
        boolean withEvidence = includeEvidence == null || includeEvidence;
        Set<String> requestedTypes = parseTypes(nodeTypes);
        LinkedHashMap<String, KnowledgeGraphResponse.Node> nodes = new LinkedHashMap<>();
        LinkedHashMap<String, KnowledgeGraphResponse.Edge> edges = new LinkedHashMap<>();
        Set<String> diagnosesWithEvidence = new HashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            List<KnowledgeRow> knowledgeRows = knowledgeRows(connection, actorId, sourceId, documentId);
            Set<String> scopedVersionIds = knowledgeRows.stream()
                    .map(row -> row.versionId)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> scopedDocumentIds = knowledgeRows.stream()
                    .map(row -> row.documentId)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            for (KnowledgeRow row : knowledgeRows) {
                addNode(nodes, sourceNode(row));
                addNode(nodes, documentNode(row));
                if (row.versionId != null) addNode(nodes, versionNode(row));
                addEdge(edges, "edge-source-document-" + row.sourceId + "-" + row.documentId,
                        row.sourceId, row.documentId, "HAS_DOCUMENT");
                if (row.versionId != null) addEdge(edges, "edge-document-version-" + row.documentId + "-" + row.versionId,
                        row.documentId, row.versionId, "HAS_VERSION");
            }

            long chunkCount = countVisibleChunks(connection, actorId, sourceId, documentId);
            long productionChunkCount = countProductionChunks(connection, actorId, sourceId, documentId);
            CoverageData coverageData = coverage(connection, actorId, sourceId);

            Map<String, DiagnosisData> diagnosisData = diagnosisRows(connection, actorId, diagnosisId);
            Set<String> scopedDiagnosisIds = new HashSet<>();
            for (DiagnosisData diagnosis : diagnosisData.values()) {
                if (!matchesKnowledgeScope(diagnosis, sourceId, scopedDocumentIds, scopedVersionIds)) continue;
                scopedDiagnosisIds.add(diagnosis.diagnosisId);
                addNode(nodes, diagnosis.node);
                if (!withEvidence) continue;
                for (EvidenceData evidence : diagnosis.evidence) {
                    addNode(nodes, evidence.node);
                    diagnosesWithEvidence.add(diagnosis.diagnosisId);
                    addEdge(edges, "edge-evidence-diagnosis-" + evidence.id + "-" + diagnosis.diagnosisId,
                            evidence.id, diagnosis.diagnosisId, "USED_BY");
                    if (evidence.versionId != null && nodes.containsKey(evidence.versionId)) {
                        addEdge(edges, "edge-version-evidence-" + evidence.versionId + "-" + evidence.id,
                                evidence.versionId, evidence.id, "SUPPORTS");
                    }
                }
            }

            Map<String, ReviewData> reviews = reviewRows(connection, actorId, diagnosisId);
            for (ReviewData review : reviews.values()) {
                if (!scopedDiagnosisIds.contains(review.diagnosisId)) continue;
                addNode(nodes, review.node);
                if (nodes.containsKey(review.diagnosisId)) {
                    addEdge(edges, "edge-diagnosis-review-" + review.diagnosisId + "-" + review.reviewId,
                            review.diagnosisId, review.reviewId, "REVIEWED_BY");
                }
            }

            long candidateCount = 0;
            for (CandidateData candidate : candidateRows(connection, actorId, diagnosisId).values()) {
                if (!scopedDiagnosisIds.contains(candidate.diagnosisId) || !reviews.containsKey(candidate.reviewId)) continue;
                candidateCount++;
                addNode(nodes, candidate.node);
                addEdge(edges, "edge-review-candidate-" + candidate.reviewId + "-" + candidate.candidateId,
                        candidate.reviewId, candidate.candidateId, "PRODUCES_CANDIDATE");
            }

            RuntimeData runtime = runtimeData(productionChunkCount);
            List<KnowledgeGraphResponse.Node> visibleNodes = nodes.values().stream()
                    .filter(node -> requestedTypes.isEmpty() || requestedTypes.contains(node.type()))
                    .limit(limit)
                    .toList();
            Set<String> visibleIds = new HashSet<>();
            visibleNodes.forEach(node -> visibleIds.add(node.id()));
            List<KnowledgeGraphResponse.Edge> visibleEdges = edges.values().stream()
                    .filter(edge -> visibleIds.contains(edge.source()) && visibleIds.contains(edge.target()))
                    .toList();

            KnowledgeGraphResponse.Summary summary = new KnowledgeGraphResponse.Summary(
                    countType(nodes, "KNOWLEDGE_SOURCE"),
                    countType(nodes, "KNOWLEDGE_DOCUMENT"),
                    countType(nodes, "KNOWLEDGE_VERSION"),
                    chunkCount,
                    productionChunkCount,
                    runtime.productionVectorCount,
                    countType(nodes, "EVIDENCE"),
                    (long) diagnosesWithEvidence.size(),
                    countType(nodes, "EXPERT_REVIEW"),
                    candidateCount);

            KnowledgeGraphResponse.Coverage coverage = new KnowledgeGraphResponse.Coverage(
                    null,
                    coverageData.readyDocuments,
                    null,
                    null,
                    coverageData.registeredFiles,
                    coverageData.registeredFiles == null ? "UNAVAILABLE" : "PARTIAL",
                    "当前关系库记录已入库文件；原始 corpus 中未进入 ingestion registry 的文件没有可安全推断的数据库计数。");

            return new KnowledgeGraphResponse(
                    graphStatus(visibleNodes, runtime.milvusStatus),
                    Instant.now(),
                    visibleNodes,
                    visibleEdges,
                    summary,
                    coverage,
                    runtime.collections,
                    runtime.pipeline,
                    runtime.retrieval);
        } catch (KnowledgeBackendException error) {
            throw error;
        } catch (Exception error) {
            throw KnowledgeBackendException.persistence("Knowledge Graph 查询不可用", error);
        }
    }

    private Map<String, DiagnosisData> diagnosisRows(Connection connection, Long actorId, String diagnosisId) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT diagnosis_id,user_id,farm_id,field_id,status,risk_level,confidence,summary,mode,source_run_id,created_at,updated_at,payload_json FROM agent_diagnoses WHERE user_id=?");
        List<Object> params = new ArrayList<>(); params.add(actorId);
        if (diagnosisId != null && !diagnosisId.isBlank()) { sql.append(" AND diagnosis_id=?"); params.add(diagnosisId); }
        sql.append(" ORDER BY created_at DESC LIMIT ").append(MAX_DIAGNOSES);
        Map<String, DiagnosisData> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            setParams(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("diagnosis_id");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    put(metadata, "farmId", rs.getObject("farm_id")); put(metadata, "fieldId", rs.getString("field_id"));
                    put(metadata, "status", rs.getString("status")); put(metadata, "riskLevel", rs.getString("risk_level"));
                    put(metadata, "confidence", rs.getObject("confidence")); put(metadata, "mode", rs.getString("mode"));
                    put(metadata, "sourceRunId", rs.getString("source_run_id")); put(metadata, "createdAt", instant(rs.getTimestamp("created_at")));
                    put(metadata, "updatedAt", instant(rs.getTimestamp("updated_at")));
                    KnowledgeGraphResponse.Node node = new KnowledgeGraphResponse.Node(id, "DIAGNOSIS", "Diagnosis · " + shortId(id), rs.getString("status"), metadata);
                    List<EvidenceData> evidence = parseEvidence(id, rs.getString("payload_json"));
                    result.put(id, new DiagnosisData(id, node, evidence));
                }
            }
        }
        return result;
    }

    private List<EvidenceData> parseEvidence(String diagnosisId, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Collections.emptyList();
        try {
            JsonNode payload = mapper.readTree(payloadJson);
            JsonNode array = payload == null ? null : payload.get("evidence");
            if (array == null || !array.isArray()) return Collections.emptyList();
            Map<String, EvidenceData> result = new LinkedHashMap<>();
            for (JsonNode raw : array) {
                if (raw == null || !raw.isObject()) continue;
                String id = firstText(raw, "id", "evidenceId");
                if (id == null) continue;
                JsonNode metadataNode = raw.get("metadata");
                JsonNode sourceNode = raw.get("source");
                String sourceId = firstNestedText(metadataNode, sourceNode, "sourceId");
                String documentId = firstNestedText(metadataNode, sourceNode, "documentId", "document");
                String versionId = firstNestedText(metadataNode, sourceNode, "versionId", "version");
                Map<String, Object> metadata = new LinkedHashMap<>();
                put(metadata, "diagnosisId", diagnosisId); put(metadata, "sourceId", sourceId);
                put(metadata, "documentId", documentId); put(metadata, "versionId", versionId);
                put(metadata, "modality", firstText(raw, "modality")); put(metadata, "score", number(raw.get("score")));
                put(metadata, "timestamp", firstNestedText(sourceNode, metadataNode, "timestamp", "date"));
                String label = firstText(raw, "title", "id");
                String status = firstNestedText(metadataNode, sourceNode, "status");
                result.putIfAbsent(id, new EvidenceData(id, sourceId, documentId, versionId,
                        new KnowledgeGraphResponse.Node(id, "EVIDENCE", label == null ? "Evidence · " + shortId(id) : label, status, metadata)));
            }
            return new ArrayList<>(result.values());
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private Map<String, ReviewData> reviewRows(Connection connection, Long actorId, String diagnosisId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT r.review_id,r.diagnosis_id,r.task_id,r.outcome_id,r.recommendation_id,r.scope,r.diagnosis_judgment,r.recommendation_judgment,r.outcome_judgment,r.expert_confidence,r.version,r.created_at,r.updated_at FROM agent_expert_reviews_v2 r WHERE r.subject_owner_user_id=? AND EXISTS (SELECT 1 FROM agent_diagnoses d WHERE d.diagnosis_id=r.diagnosis_id AND d.user_id=?)");
        List<Object> params = new ArrayList<>(); params.add(actorId); params.add(actorId);
        if (diagnosisId != null && !diagnosisId.isBlank()) { sql.append(" AND r.diagnosis_id=?"); params.add(diagnosisId); }
        sql.append(" ORDER BY r.created_at DESC");
        Map<String, ReviewData> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            setParams(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("review_id");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    put(metadata, "diagnosisId", rs.getString("diagnosis_id")); put(metadata, "taskId", rs.getString("task_id"));
                    put(metadata, "outcomeId", rs.getString("outcome_id")); put(metadata, "recommendationId", rs.getString("recommendation_id"));
                    put(metadata, "scope", rs.getString("scope")); put(metadata, "diagnosisJudgment", rs.getString("diagnosis_judgment"));
                    put(metadata, "recommendationJudgment", rs.getString("recommendation_judgment")); put(metadata, "outcomeJudgment", rs.getString("outcome_judgment"));
                    put(metadata, "expertConfidence", rs.getObject("expert_confidence")); put(metadata, "version", rs.getObject("version"));
                    put(metadata, "createdAt", instant(rs.getTimestamp("created_at"))); put(metadata, "updatedAt", instant(rs.getTimestamp("updated_at")));
                    String status = rs.getString("diagnosis_judgment");
                    result.put(id, new ReviewData(id, rs.getString("diagnosis_id"), new KnowledgeGraphResponse.Node(id, "EXPERT_REVIEW", "Expert Review · " + shortId(id), status, metadata)));
                }
            }
        }
        return result;
    }

    private Map<String, CandidateData> candidateRows(Connection connection, Long actorId, String diagnosisId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT candidate_id,review_id,diagnosis_id,task_id,outcome_id,status,eligibility_reason,learning_value,created_at,updated_at FROM historical_case_candidates WHERE owner_user_id=?");
        List<Object> params = new ArrayList<>(); params.add(actorId);
        if (diagnosisId != null && !diagnosisId.isBlank()) { sql.append(" AND diagnosis_id=?"); params.add(diagnosisId); }
        sql.append(" ORDER BY created_at DESC");
        Map<String, CandidateData> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            setParams(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("candidate_id");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    put(metadata, "reviewId", rs.getString("review_id")); put(metadata, "diagnosisId", rs.getString("diagnosis_id"));
                    put(metadata, "taskId", rs.getString("task_id")); put(metadata, "outcomeId", rs.getString("outcome_id"));
                    put(metadata, "status", rs.getString("status")); put(metadata, "eligibilityReason", rs.getString("eligibility_reason"));
                    put(metadata, "learningValue", rs.getString("learning_value")); put(metadata, "createdAt", instant(rs.getTimestamp("created_at")));
                    put(metadata, "updatedAt", instant(rs.getTimestamp("updated_at")));
                    result.put(id, new CandidateData(id, rs.getString("review_id"), rs.getString("diagnosis_id"), new KnowledgeGraphResponse.Node(id, "HISTORICAL_CASE_CANDIDATE", "Candidate · " + shortId(id), rs.getString("status"), metadata)));
                }
            }
        }
        return result;
    }

    private List<KnowledgeRow> knowledgeRows(Connection connection, Long actorId, String sourceId, String documentId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT s.source_id,s.name,s.source_type,s.status,s.created_at,s.updated_at,d.document_id,d.title,d.status,d.current_version_id,d.version,d.created_at,d.updated_at,v.version_id,v.version_number,v.content_type,v.created_at,COALESCE((SELECT SUM(i.chunk_count) FROM knowledge_ingestions i WHERE i.version_id=v.version_id AND i.status='READY'),0), (SELECT i.embedding_model FROM knowledge_ingestions i WHERE i.version_id=v.version_id ORDER BY i.created_at DESC LIMIT 1), (SELECT i.embedding_dimension FROM knowledge_ingestions i WHERE i.version_id=v.version_id ORDER BY i.created_at DESC LIMIT 1), (SELECT i.index_collection FROM knowledge_ingestions i WHERE i.version_id=v.version_id ORDER BY i.created_at DESC LIMIT 1) FROM knowledge_sources s JOIN knowledge_documents d ON d.source_id=s.source_id LEFT JOIN knowledge_versions v ON v.version_id=d.current_version_id WHERE s.status='ACTIVE' AND d.status='ACTIVE' AND (s.owner_user_id IS NULL OR s.owner_user_id=?) AND (d.owner_user_id IS NULL OR d.owner_user_id=?)");
        List<Object> params = new ArrayList<>(); params.add(actorId); params.add(actorId);
        if (sourceId != null && !sourceId.isBlank()) { sql.append(" AND s.source_id=?"); params.add(sourceId); }
        if (documentId != null && !documentId.isBlank()) { sql.append(" AND d.document_id=?"); params.add(documentId); }
        sql.append(" ORDER BY s.created_at,d.created_at");
        List<KnowledgeRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            setParams(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(new KnowledgeRow(rs));
            }
        }
        return result;
    }

    private long countVisibleChunks(Connection c, Long actorId, String sourceId, String documentId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT c.chunk_id) FROM knowledge_chunks c JOIN knowledge_sources s ON s.source_id=c.source_id JOIN knowledge_documents d ON d.document_id=c.document_id WHERE s.status='ACTIVE' AND d.status='ACTIVE' AND (s.owner_user_id IS NULL OR s.owner_user_id=?) AND (d.owner_user_id IS NULL OR d.owner_user_id=?)");
        List<Object> params = new ArrayList<>(); params.add(actorId); params.add(actorId);
        if (sourceId != null && !sourceId.isBlank()) { sql.append(" AND c.source_id=?"); params.add(sourceId); }
        if (documentId != null && !documentId.isBlank()) { sql.append(" AND c.document_id=?"); params.add(documentId); }
        return scalar(c, sql.toString(), params);
    }

    private long countProductionChunks(Connection c, Long actorId, String sourceId, String documentId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT c.chunk_id) FROM knowledge_chunks c JOIN knowledge_sources s ON s.source_id=c.source_id JOIN knowledge_documents d ON d.document_id=c.document_id JOIN knowledge_ingestions i ON i.version_id=c.version_id WHERE i.status='READY' AND i.index_collection=? AND i.embedding_model=? AND s.status='ACTIVE' AND d.status='ACTIVE' AND (s.owner_user_id IS NULL OR s.owner_user_id=?) AND (d.owner_user_id IS NULL OR d.owner_user_id=?)");
        List<Object> params = new ArrayList<>(); params.add(QWEN_COLLECTION); params.add(QWEN_MODEL); params.add(actorId); params.add(actorId);
        if (sourceId != null && !sourceId.isBlank()) { sql.append(" AND c.source_id=?"); params.add(sourceId); }
        if (documentId != null && !documentId.isBlank()) { sql.append(" AND c.document_id=?"); params.add(documentId); }
        return scalar(c, sql.toString(), params);
    }

    private boolean matchesKnowledgeScope(DiagnosisData diagnosis, String sourceId, Set<String> scopedDocumentIds, Set<String> scopedVersionIds) {
        if (sourceId == null || sourceId.isBlank()) return true;
        return diagnosis.evidence.stream().anyMatch(evidence -> sourceId.equals(evidence.sourceId)
                || (evidence.documentId != null && scopedDocumentIds.contains(evidence.documentId))
                || (evidence.versionId != null && scopedVersionIds.contains(evidence.versionId)));
    }

    private CoverageData coverage(Connection c, Long actorId, String sourceId) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT i.source_file_name), COUNT(DISTINCT CASE WHEN i.index_collection=? AND i.embedding_model=? AND i.status='READY' THEN i.document_id END) FROM knowledge_ingestions i JOIN knowledge_sources s ON s.source_id=i.source_id WHERE i.status='READY' AND s.status='ACTIVE' AND (s.owner_user_id IS NULL OR s.owner_user_id=?)";
        if (sourceId != null && !sourceId.isBlank()) sql += " AND i.source_id=?";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, QWEN_COLLECTION); p.setString(2, QWEN_MODEL); p.setLong(3, actorId);
            if (sourceId != null && !sourceId.isBlank()) p.setString(4, sourceId);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return new CoverageData(r.getLong(1), r.getLong(2)); }
        }
        return new CoverageData(null, null);
    }

    private RuntimeData runtimeData(long productionChunkCount) {
        Map<String, Object> qwen = new LinkedHashMap<>();
        if (qwenVectors == null) qwen.put("status", "UNAVAILABLE");
        else {
            try { qwen.putAll(qwenVectors.health()); }
            catch (Exception ignored) { qwen.put("status", "UNAVAILABLE"); }
        }
        String rawQwenStatus = String.valueOf(qwen.getOrDefault("status", "UNAVAILABLE"));
        String milvusStatus = "READY".equals(rawQwenStatus) ? "READY" : productionChunkCount > 0 ? "DEGRADED" : "UNAVAILABLE";
        Long vectorCount = "READY".equals(rawQwenStatus) ? longValue(qwen.get("rowCount")) : null;
        Map<String, Object> embedding = map(qwen.get("knowledgeEmbedding"));
        Map<String, Object> rerank = map(qwen.get("knowledgeReranker"));
        String embeddingStatus = String.valueOf(embedding.getOrDefault("status", qwenVectors == null ? "UNAVAILABLE" : "READY"));
        String rerankStatus = String.valueOf(rerank.getOrDefault("status", "UNAVAILABLE"));
        Integer candidateTopN = intValue(qwen.get("candidateTopN"), 20);
        Integer finalTopK = intValue(qwen.get("finalTopK"), 5);

        Map<String, Object> historical = new LinkedHashMap<>();
        if (historicalVectors == null) historical.put("status", "UNAVAILABLE");
        else {
            try { historical.putAll(historicalVectors.initialize(false)); }
            catch (Exception ignored) { historical.put("status", "UNAVAILABLE"); }
        }
        String historicalRaw = String.valueOf(historical.getOrDefault("status", "UNAVAILABLE"));
        String historicalStatus = "READY".equals(historicalRaw) && Long.valueOf(0L).equals(longValue(historical.get("rowCount")))
                ? "READY_EMPTY" : "READY".equals(historicalRaw) ? "READY" : "UNAVAILABLE";
        Long historicalCount = "READY".equals(historicalRaw) ? longValue(historical.get("rowCount")) : null;

        KnowledgeGraphResponse.Collections collections = new KnowledgeGraphResponse.Collections(
                new KnowledgeGraphResponse.VectorCollection(QWEN_COLLECTION, milvusStatus, vectorCount, QWEN_MODEL, 1024, "COSINE",
                        "生产知识向量状态来自当前 Milvus health；关系库登记的生产 chunk 数为 " + productionChunkCount + "。"),
                new KnowledgeGraphResponse.VectorCollection(HistoricalVectorStoreInitializer.COLLECTION, historicalStatus, historicalCount, null, 512, "L2",
                        historicalStatus.equals("READY_EMPTY") ? "历史案例库暂无向量内容。" : "历史图像向量集合与生产知识集合分开统计。"));

        List<KnowledgeGraphResponse.Stage> stages = new ArrayList<>();
        stages.add(new KnowledgeGraphResponse.Stage("document", "Knowledge Document", productionChunkCount > 0 ? "READY" : "READY_EMPTY", "MySQL canonical metadata"));
        stages.add(new KnowledgeGraphResponse.Stage("extraction", "Text Extraction", productionChunkCount > 0 ? "READY" : "READY_EMPTY", "READY ingestion registry"));
        stages.add(new KnowledgeGraphResponse.Stage("chunking", "Chunking", productionChunkCount > 0 ? "READY" : "READY_EMPTY", "Production chunk metadata"));
        stages.add(new KnowledgeGraphResponse.Stage("embedding", "Qwen Embedding", embeddingStatus, QWEN_MODEL + " / 1024d"));
        stages.add(new KnowledgeGraphResponse.Stage("milvus", "Milvus", milvusStatus, QWEN_COLLECTION));
        stages.add(new KnowledgeGraphResponse.Stage("retrieval", "Candidate Retrieval", "READY".equals(milvusStatus) ? "READY" : "UNAVAILABLE", "Top-N=" + candidateTopN));
        stages.add(new KnowledgeGraphResponse.Stage("rerank", "GTE Rerank", rerankStatus, "gte-rerank-v2 / Top-K=" + finalTopK));
        stages.add(new KnowledgeGraphResponse.Stage("evidence", "Evidence", "READY", "Stored Diagnosis Evidence provenance"));
        stages.add(new KnowledgeGraphResponse.Stage("diagnosis", "Diagnosis", "READY", "Actor-scoped persisted diagnoses"));
        stages.add(new KnowledgeGraphResponse.Stage("historical-image", "Historical Image Store", historicalStatus, HistoricalVectorStoreInitializer.COLLECTION));
        KnowledgeGraphResponse.Pipeline pipeline = new KnowledgeGraphResponse.Pipeline(milvusStatus, stages);
        KnowledgeGraphResponse.Retrieval retrieval = new KnowledgeGraphResponse.Retrieval(
                "READY".equals(milvusStatus) ? "READY" : "UNAVAILABLE", candidateTopN, finalTopK,
                String.valueOf(embedding.getOrDefault("provider", "UNAVAILABLE")),
                String.valueOf(embedding.getOrDefault("model", QWEN_MODEL)), "gte-rerank-v2", rerankStatus,
                "Qwen vector score 与 GTE rerank score 保持分开，不做 raw combination。");
        return new RuntimeData(milvusStatus, vectorCount, collections, pipeline, retrieval);
    }

    private KnowledgeGraphResponse unavailable() {
        return new KnowledgeGraphResponse("UNAVAILABLE", Instant.now(), List.of(), List.of(),
                new KnowledgeGraphResponse.Summary(0L, 0L, 0L, 0L, 0L, null, 0L, 0L, 0L, 0L),
                new KnowledgeGraphResponse.Coverage(null, null, null, null, null, "UNAVAILABLE", "Knowledge 数据库不可用。"),
                new KnowledgeGraphResponse.Collections(
                        new KnowledgeGraphResponse.VectorCollection(QWEN_COLLECTION, "UNAVAILABLE", null, QWEN_MODEL, 1024, "COSINE", null),
                        new KnowledgeGraphResponse.VectorCollection(HistoricalVectorStoreInitializer.COLLECTION, "UNAVAILABLE", null, null, 512, "L2", null)),
                new KnowledgeGraphResponse.Pipeline("UNAVAILABLE", List.of()),
                new KnowledgeGraphResponse.Retrieval("UNAVAILABLE", 20, 5, "UNAVAILABLE", QWEN_MODEL, "gte-rerank-v2", "UNAVAILABLE", null));
    }

    private KnowledgeGraphResponse.Node sourceNode(KnowledgeRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>(); put(metadata, "sourceType", row.sourceType); put(metadata, "createdAt", row.sourceCreatedAt); put(metadata, "updatedAt", row.sourceUpdatedAt);
        return new KnowledgeGraphResponse.Node(row.sourceId, "KNOWLEDGE_SOURCE", row.sourceName, row.sourceStatus, metadata);
    }
    private KnowledgeGraphResponse.Node documentNode(KnowledgeRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>(); put(metadata, "sourceId", row.sourceId); put(metadata, "currentVersionId", row.versionId); put(metadata, "version", row.documentVersion); put(metadata, "chunkCount", row.chunkCount); put(metadata, "embeddingModel", row.embeddingModel); put(metadata, "updatedAt", row.documentUpdatedAt);
        return new KnowledgeGraphResponse.Node(row.documentId, "KNOWLEDGE_DOCUMENT", row.documentTitle, row.documentStatus, metadata);
    }
    private KnowledgeGraphResponse.Node versionNode(KnowledgeRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>(); put(metadata, "documentId", row.documentId); put(metadata, "versionNumber", row.versionNumber); put(metadata, "contentType", row.contentType); put(metadata, "chunkCount", row.chunkCount); put(metadata, "embeddingModel", row.embeddingModel); put(metadata, "embeddingDimension", row.embeddingDimension); put(metadata, "indexCollection", row.indexCollection); put(metadata, "createdAt", row.versionCreatedAt);
        String label = "Version " + (row.versionNumber == null ? "—" : row.versionNumber);
        return new KnowledgeGraphResponse.Node(row.versionId, "KNOWLEDGE_VERSION", label, row.versionStatus, metadata);
    }

    private void addNode(Map<String, KnowledgeGraphResponse.Node> nodes, KnowledgeGraphResponse.Node node) { if (node != null && node.id() != null) nodes.putIfAbsent(node.id(), node); }
    private void addEdge(Map<String, KnowledgeGraphResponse.Edge> edges, String id, String source, String target, String type) { if (source != null && target != null) edges.putIfAbsent(id, new KnowledgeGraphResponse.Edge(id, source, target, type)); }
    private long countType(Map<String, KnowledgeGraphResponse.Node> nodes, String type) { return nodes.values().stream().filter(node -> type.equals(node.type())).count(); }
    private String graphStatus(List<KnowledgeGraphResponse.Node> nodes, String milvusStatus) { return nodes.isEmpty() ? "EMPTY" : "READY".equals(milvusStatus) ? "READY" : "DEGRADED"; }
    private Set<String> parseTypes(String value) { if (value == null || value.isBlank()) return Collections.emptySet(); return new HashSet<>(Arrays.stream(value.split(",")).map(item -> item.trim().toUpperCase(Locale.ROOT)).filter(item -> !item.isBlank()).toList()); }
    private long scalar(Connection c, String sql, List<Object> params) throws SQLException { try (PreparedStatement p=c.prepareStatement(sql)) { setParams(p,params); try(ResultSet r=p.executeQuery()){ return r.next()?r.getLong(1):0L; } } }
    private void setParams(PreparedStatement p, List<Object> params) throws SQLException { for(int i=0;i<params.size();i++){ Object value=params.get(i); if(value instanceof Long l)p.setLong(i+1,l); else p.setString(i+1,String.valueOf(value)); } }
    private String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private void put(Map<String,Object> map,String key,Object value){ if(value!=null) map.put(key,value); }
    private Map<String,Object> map(Object value){
        Map<String,Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?,?> raw) for (Map.Entry<?,?> entry : raw.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
    private Long longValue(Object value){ return value instanceof Number n ? n.longValue() : value == null ? null : parseLong(String.valueOf(value)); }
    private Long parseLong(String value){ try{return Long.parseLong(value);}catch(Exception ignored){return null;} }
    private Integer intValue(Object value,int fallback){ return value instanceof Number n ? n.intValue() : fallback; }
    private Number number(JsonNode value){ return value != null && value.isNumber() ? value.numberValue() : null; }
    private String firstText(JsonNode node,String... keys){ if(node==null)return null; for(String key:keys){JsonNode value=node.get(key);if(value!=null&&!value.isNull()&&!value.asText().isBlank())return value.asText();}return null; }
    private String firstNestedText(JsonNode first,JsonNode second,String... keys){String value=firstText(first,keys);return value==null?firstText(second,keys):value;}
    private String shortId(String value){ if(value==null)return "—"; int index=value.lastIndexOf('-'); return index>=0&&index+1<value.length()?value.substring(index+1,Math.min(value.length(),index+9)):value.substring(0,Math.min(value.length(),12)); }

    private static final class DiagnosisData { final String diagnosisId; final KnowledgeGraphResponse.Node node; final List<EvidenceData> evidence; DiagnosisData(String id,KnowledgeGraphResponse.Node node,List<EvidenceData> evidence){this.diagnosisId=id;this.node=node;this.evidence=evidence;} }
    private static final class EvidenceData { final String id; final String sourceId; final String documentId; final String versionId; final KnowledgeGraphResponse.Node node; EvidenceData(String id,String sourceId,String documentId,String versionId,KnowledgeGraphResponse.Node node){this.id=id;this.sourceId=sourceId;this.documentId=documentId;this.versionId=versionId;this.node=node;} }
    private static final class ReviewData { final String reviewId; final String diagnosisId; final KnowledgeGraphResponse.Node node; ReviewData(String id,String diagnosisId,KnowledgeGraphResponse.Node node){this.reviewId=id;this.diagnosisId=diagnosisId;this.node=node;} }
    private static final class CandidateData { final String candidateId; final String reviewId; final String diagnosisId; final KnowledgeGraphResponse.Node node; CandidateData(String id,String reviewId,String diagnosisId,KnowledgeGraphResponse.Node node){this.candidateId=id;this.reviewId=reviewId;this.diagnosisId=diagnosisId;this.node=node;} }
    private record CoverageData(Long registeredFiles,Long readyDocuments) {}
    private record RuntimeData(String milvusStatus,Long productionVectorCount,KnowledgeGraphResponse.Collections collections,KnowledgeGraphResponse.Pipeline pipeline,KnowledgeGraphResponse.Retrieval retrieval) {}

    private static final class KnowledgeRow {
        final String sourceId,sourceName,sourceType,sourceStatus,sourceCreatedAt,sourceUpdatedAt,documentId,documentTitle,documentStatus,versionId,contentType,versionStatus,embeddingModel,indexCollection,documentUpdatedAt,versionCreatedAt;
        final Long documentVersion,versionNumber,chunkCount; final Integer embeddingDimension;
        KnowledgeRow(ResultSet r) throws SQLException { sourceId=r.getString(1);sourceName=r.getString(2);sourceType=r.getString(3);sourceStatus=r.getString(4);sourceCreatedAt=ts(r.getTimestamp(5));sourceUpdatedAt=ts(r.getTimestamp(6));documentId=r.getString(7);documentTitle=r.getString(8);documentStatus=r.getString(9);documentVersion=r.getObject(11)==null?null:r.getLong(11);documentUpdatedAt=ts(r.getTimestamp(13));versionId=r.getString(14);versionNumber=r.getObject(15)==null?null:r.getLong(15);contentType=r.getString(16);versionCreatedAt=ts(r.getTimestamp(17));chunkCount=r.getObject(18)==null?0L:r.getLong(18);embeddingModel=r.getString(19);embeddingDimension=r.getObject(20)==null?null:r.getInt(20);indexCollection=r.getString(21);versionStatus=embeddingModel==null?"UNINDEXED":"READY"; }
        private static String ts(Timestamp value){return value==null?null:value.toInstant().toString();}
    }
}

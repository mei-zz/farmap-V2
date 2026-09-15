package com.mei.zhgy.service.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.entity.User;
import com.mei.zhgy.mapper.UserMapper;
import com.mei.zhgy.service.ai.IntelligenceGateway;
import com.mei.zhgy.service.ai.IntelligenceGatewayException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Admin-only ingestion coordinator. It never accepts a client filesystem path. */
@Service
public class KnowledgeIngestionService {
    public static final String ADMIN_CURATED = "ADMIN_CURATED";
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int DEFAULT_CHUNK_SIZE = KnowledgeChunker.MAX_CHARS;
    private static final int DEFAULT_CHUNK_OVERLAP = KnowledgeChunker.OVERLAP_CHARS;

    private final DataSource dataSource;
    private final UserMapper users;
    private final IntelligenceGateway gateway;
    private final KnowledgeVectorStore vectors;
    private final KnowledgeEmbeddingProvider embeddingProvider;
    private final QwenKnowledgeVectorStore qwenVectors;
    private final KnowledgeBackendService backend;
    private final ObjectMapper mapper;
    private final Path storageRoot;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int maxCorpusFileBytes;
    private volatile boolean schemaChecked;

    public KnowledgeIngestionService() {
        this(null, null, null, null, null, new ObjectMapper().findAndRegisterModules(),
                DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, defaultStorageDir(), null, null, 64 * 1024 * 1024);
    }

    public KnowledgeIngestionService(DataSource dataSource, UserMapper users, IntelligenceGateway gateway,
                                    KnowledgeVectorStore vectors, KnowledgeBackendService backend,
                                    ObjectMapper mapper) {
        this(dataSource, users, gateway, vectors, backend, mapper,
                DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, defaultStorageDir(), null, null, 64 * 1024 * 1024);
    }

    @Autowired
    public KnowledgeIngestionService(@Autowired(required = false) DataSource dataSource,
                                    @Autowired(required = false) UserMapper users,
                                    @Autowired(required = false) IntelligenceGateway gateway,
                                    @Autowired(required = false) KnowledgeVectorStore vectors,
                                    @Autowired(required = false) KnowledgeBackendService backend,
                                    ObjectMapper mapper,
                                    @Value("${farmap.knowledge.chunk-size:1200}") int chunkSize,
                                    @Value("${farmap.knowledge.chunk-overlap:120}") int chunkOverlap,
                                    @Value("${farmap.knowledge.storage-dir:.data/knowledge}") String storageDir,
                                    @Autowired(required = false) KnowledgeEmbeddingProvider embeddingProvider,
                                    @Autowired(required = false) QwenKnowledgeVectorStore qwenVectors,
                                    @Value("${farmap.knowledge.max-corpus-file-bytes:67108864}") int maxCorpusFileBytes) {
        this.dataSource = dataSource;
        this.users = users;
        this.gateway = gateway;
        this.vectors = vectors;
        this.embeddingProvider = embeddingProvider;
        this.qwenVectors = qwenVectors;
        this.backend = backend == null ? new KnowledgeBackendService(dataSource, mapper, false) : backend;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper.findAndRegisterModules();
        if (chunkSize < 1 || chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("farmap knowledge chunk size/overlap configuration is invalid");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.maxCorpusFileBytes = Math.max(MAX_FILE_BYTES, maxCorpusFileBytes);
        this.storageRoot = Path.of(storageDir == null || storageDir.isBlank() ? defaultStorageDir() : storageDir)
                .toAbsolutePath().normalize();
    }

    public Map<String, Object> importDocument(String sourceName, String title, String fileName,
                                              byte[] bytes, Long actorId) {
        return importDocumentInternal(sourceName, title, fileName, bytes, actorId, false);
    }

    /** Directory-corpus entry point. The relative path is the document identity metadata. */
    public Map<String, Object> importCorpusDocument(String sourceName, String title, String relativePath,
                                                    byte[] bytes, Long actorId) {
        return importDocumentInternal(sourceName, title, relativePath, bytes, actorId, true);
    }

    private Map<String, Object> importDocumentInternal(String sourceName, String title, String fileName,
                                                        byte[] bytes, Long actorId, boolean corpusImport) {
        requireAdmin(actorId);
        if (dataSource == null) {
            throw KnowledgeBackendException.persistence("Knowledge ingestion 数据库不可用，已拒绝写入", null);
        }
        int maxBytes = corpusImport ? maxCorpusFileBytes : MAX_FILE_BYTES;
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes) {
            throw KnowledgeBackendException.validation("文件大小必须在 1B 至 " + (maxBytes / (1024 * 1024)) + "MB 之间");
        }
        String safeFileName = corpusImport ? safeCorpusPath(fileName) : safeFileName(fileName);
        String source = required(sourceName, "sourceName");
        String documentTitle = title == null || title.isBlank() ? leafName(safeFileName) : title.trim();
        if (documentTitle.length() > 255) {
            throw KnowledgeBackendException.validation("title 长度不能超过 255");
        }

        String fileHash = hash(bytes);
        String content;
        try {
            content = KnowledgeDocumentExtractor.extract(safeFileName, bytes);
        } catch (IllegalArgumentException error) {
            throw KnowledgeBackendException.validation(error.getMessage());
        } catch (Exception error) {
            throw KnowledgeBackendException.validation("文档解析失败：" + error.getClass().getSimpleName());
        }
        if (content == null || content.isBlank()) {
            throw new KnowledgeBackendException("KNOWLEDGE_TEXT_EXTRACTION_EMPTY", "文档未解析出可索引文本");
        }
        List<String> chunks = KnowledgeChunker.chunk(content, chunkSize, chunkOverlap);
        if (chunks.isEmpty()) {
            throw new KnowledgeBackendException("KNOWLEDGE_TEXT_EXTRACTION_EMPTY", "文档未解析出可索引文本");
        }

        Map<String, Object> ingestion = createIngestion(source, documentTitle, safeFileName, fileHash,
                hash(content), KnowledgeDocumentExtractor.contentType(safeFileName), content, bytes, actorId);
        ingestion.put("classification", cropClassification(documentTitle, content));
        String status = String.valueOf(ingestion.get("status"));
        if (!KnowledgeIngestionStatus.UPLOADED.name().equals(status)
                && !KnowledgeIngestionStatus.PROCESSING.name().equals(status)) {
            return ingestion;
        }

        String ingestionId = String.valueOf(ingestion.get("ingestionId"));
        String versionId = String.valueOf(ingestion.get("versionId"));
        try {
            updateIngestion(ingestionId, KnowledgeIngestionStatus.PROCESSING, null, null, 0, null, null, 0);
            boolean qwen = embeddingProvider != null && "BAILIAN".equalsIgnoreCase(embeddingProvider.provider());
            if (qwen) {
                if (qwenVectors == null || !qwenVectors.isProductionEnabled()) throw KnowledgeBackendException.persistence("Qwen 知识向量集合未配置，已拒绝混用 BGE", null);
                List<com.mei.zhgy.service.ai.EmbeddingProvider.EmbeddingResult> embeddings = embeddingProvider.embedTexts(chunks);
                List<QwenKnowledgeVectorStore.EmbeddingRow> rows = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    List<Float> vector = embeddings.get(i).getVector();
                    rows.add(new QwenKnowledgeVectorStore.EmbeddingRow(chunkId(versionId, i), String.valueOf(ingestion.get("sourceId")),
                            String.valueOf(ingestion.get("documentId")), versionId, i, hash(chunks.get(i)), String.valueOf(ingestion.getOrDefault("classification", ADMIN_CURATED)),
                            embeddingProvider.model(), vector));
                }
                persistChunks(versionId, ingestion, chunks);
                qwenVectors.insert(rows);
                updateIngestion(ingestionId, KnowledgeIngestionStatus.READY, null, null, chunks.size(), embeddingProvider.model(),
                        QwenKnowledgeVectorStore.COLLECTION, embeddingProvider.dimension());
            } else {
                if (gateway == null || vectors == null) throw KnowledgeBackendException.persistence("AI Gateway 或知识向量集合未配置", null);
                List<Map<String, Object>> embeddings = gateway.embedTexts(chunks, "knowledge-ingestion-" + versionId, "knowledge-ingestion");
                if (embeddings.size() != chunks.size()) throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "embedding 数量与 chunk 数量不一致");
                List<KnowledgeVectorStore.EmbeddingRow> rows = new ArrayList<>();
                String model = null;
                for (int i = 0; i < chunks.size(); i++) {
                    Map<String, Object> embedding = embeddings.get(i);
                    List<Float> vector = readVector(embedding);
                    if (model == null) model = String.valueOf(embedding.getOrDefault("model", "BAAI/bge-small-zh-v1.5"));
                    rows.add(new KnowledgeVectorStore.EmbeddingRow(chunkId(versionId, i), String.valueOf(ingestion.get("sourceId")),
                            String.valueOf(ingestion.get("documentId")), versionId, i, hash(chunks.get(i)), String.valueOf(ingestion.getOrDefault("classification", ADMIN_CURATED)), vector));
                }
                persistChunks(versionId, ingestion, chunks);
                vectors.insert(rows);
                updateIngestion(ingestionId, KnowledgeIngestionStatus.READY, null, null, chunks.size(), model,
                        KnowledgeVectorStore.COLLECTION, KnowledgeVectorStore.DIMENSION);
            }

            // Persist the relational retrieval artifact before indexing it. A
            // failed Milvus write remains FAILED and is never eligible for RAG.
            Map<String, Object> ready = getIngestion(ingestionId);
            // Activation is deliberately after READY. If it fails, the old
            // current_version_id remains intact and this failed version is not searchable.
            activate(versionId, String.valueOf(ingestion.get("documentId")));
            ready.put("ingestionOutcome", "IMPORTED");
            ready.put("activated", true);
            return ready;
        } catch (IntelligenceGatewayException error) {
            safeFail(ingestionId, error.getCode(), error.getMessage());
            throw KnowledgeBackendException.persistence("Knowledge embedding 未完成，已标记 FAILED", error);
        } catch (KnowledgeBackendException error) {
            safeFail(ingestionId, error.getCode(), error.getMessage());
            throw error;
        } catch (Exception error) {
            safeFail(ingestionId, "INGESTION_FAILED", error.getMessage());
            throw KnowledgeBackendException.persistence("Knowledge ingestion 未完成，已标记 FAILED", error);
        }
    }

    public Map<String, Object> getIngestion(String ingestionId) {
        if (dataSource == null) {
            throw KnowledgeBackendException.persistence("Knowledge ingestion 数据库不可用", null);
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT ingestion_id,source_id,document_id,version_id,status,source_file_name,source_file_hash," +
                        "raw_storage_key,content_type,chunk_count,embedding_model,embedding_dimension,index_collection," +
                        "failure_code,failure_message,created_by,started_at,completed_at,created_at,updated_at " +
                        "FROM knowledge_ingestions WHERE ingestion_id=?")) {
            p.setString(1, ingestionId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    throw KnowledgeBackendException.notFound("INGESTION_NOT_FOUND", "ingestion 不存在", ingestionId);
                }
                return row(r);
            }
        } catch (KnowledgeBackendException error) {
            throw error;
        } catch (Exception error) {
            throw KnowledgeBackendException.persistence("Knowledge ingestion 查询不可用", error);
        }
    }

    public Map<String, Object> health() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("collection", KnowledgeVectorStore.COLLECTION);
        report.put("mode", "ADMIN_CURATED_ONLY");
        if (dataSource == null) {
            report.put("status", "UNAVAILABLE");
            return report;
        }
        try (Connection c = dataSource.getConnection()) {
            ensureSchema(c);
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT status,COUNT(*) FROM knowledge_ingestions GROUP BY status")) {
                Map<String, Long> counts = new LinkedHashMap<>();
                while (r.next()) counts.put(r.getString(1), r.getLong(2));
                report.put("ingestions", counts);
            }
            Map<String, Object> vectorState = vectors == null
                    ? Map.of("status", "UNAVAILABLE") : vectors.health();
            report.put("vectorStore", vectorState);
            String vectorStatus = String.valueOf(vectorState.getOrDefault("status", "UNAVAILABLE"));
            report.put("status", "READY".equals(vectorStatus) || "READY_EMPTY".equals(vectorStatus)
                    ? vectorStatus : "UNAVAILABLE");
            return report;
        } catch (Exception error) {
            report.put("status", "UNAVAILABLE");
            report.put("error", error.getClass().getSimpleName());
            return report;
        }
    }

    private Map<String, Object> createIngestion(String sourceName, String title, String fileName,
                                                String fileHash, String contentHash, String contentType,
                                                String content, byte[] bytes, Long actorId) {
        Path rawFile = null;
        try (Connection c = dataSource.getConnection()) {
            backend.ensureSchema(c);
            ensureSchema(c);
            c.setAutoCommit(false);
            try {
                String origin = "admin://" + sourceName.trim().toLowerCase(Locale.ROOT);
                String sourceId = findSource(c, origin);
                if (sourceId == null) {
                    sourceId = "knowledge-source-" + UUID.randomUUID();
                    insertSource(c, sourceId, sourceName.trim(), origin);
                } else if (!sourceActive(c, sourceId)) {
                    throw KnowledgeBackendException.conflict("KNOWLEDGE_SOURCE_DISABLED",
                            "Knowledge Source 已禁用，不能导入新版本", sourceId);
                }

                Map<String, Object> existing = findByFileHash(c, sourceId, fileHash);
                if (existing != null) {
                    if (KnowledgeIngestionStatus.FAILED.name().equalsIgnoreCase(String.valueOf(existing.get("status")))) {
                        String ingestionId = String.valueOf(existing.get("ingestionId"));
                        Timestamp retryAt = Timestamp.from(Instant.now());
                        try (PreparedStatement retry = c.prepareStatement(
                                "UPDATE knowledge_ingestions SET status='UPLOADED',failure_code=NULL,failure_message=NULL," +
                                        "chunk_count=0,embedding_model=NULL,embedding_dimension=NULL,index_collection=NULL," +
                                        "started_at=NULL,completed_at=NULL,updated_at=? WHERE ingestion_id=?")) {
                            retry.setTimestamp(1, retryAt);
                            retry.setString(2, ingestionId);
                            retry.executeUpdate();
                        }
                        insertEvent(c, ingestionId, "KNOWLEDGE_INGESTION_RETRY", actorId, "UPLOADED");
                        c.commit();
                        return getIngestion(ingestionId);
                    }
                    insertEvent(c, String.valueOf(existing.get("ingestionId")),
                            "KNOWLEDGE_INGESTION_SKIPPED_IDEMPOTENT", actorId, "SKIPPED_IDEMPOTENT");
                    existing.put("ingestionOutcome", "SKIPPED_IDEMPOTENT");
                    c.commit();
                    return existing;
                }

                Map<String, Object> document = findDocument(c, sourceId, fileName);
                String documentId;
                String versionId;
                if (document == null) {
                    documentId = "knowledge-document-" + UUID.randomUUID();
                    insertDocument(c, documentId, sourceId, title, fileName);
                } else {
                    documentId = String.valueOf(document.get("documentId"));
                }

                Map<String, Object> existingVersion = findVersionByHash(c, documentId, contentHash);
                if (existingVersion != null) {
                    Map<String, Object> existingIngestion = findIngestionByVersion(
                            c, String.valueOf(existingVersion.get("versionId")));
                    if (existingIngestion != null) {
                        insertEvent(c, String.valueOf(existingIngestion.get("ingestionId")),
                                "KNOWLEDGE_INGESTION_SKIPPED_IDEMPOTENT", actorId, "SKIPPED_IDEMPOTENT");
                        existingIngestion.put("ingestionOutcome", "SKIPPED_IDEMPOTENT");
                        c.commit();
                        return existingIngestion;
                    }
                    versionId = String.valueOf(existingVersion.get("versionId"));
                } else {
                    versionId = "knowledge-version-" + UUID.randomUUID();
                    insertVersion(c, versionId, documentId, nextVersion(c, documentId), contentType,
                            content, contentHash, actorId);
                }

                String ingestionId = "knowledge-ingestion-" + UUID.randomUUID();
                String rawStorageKey = rawStorageKey(fileName);
                rawFile = writeRaw(rawStorageKey, bytes);
                Timestamp now = Timestamp.from(Instant.now());
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO knowledge_ingestions(ingestion_id,source_id,document_id,version_id,status," +
                                "source_file_name,source_file_hash,raw_storage_key,content_type,chunk_count,created_by," +
                                "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1, ingestionId); p.setString(2, sourceId); p.setString(3, documentId);
                    p.setString(4, versionId); p.setString(5, KnowledgeIngestionStatus.UPLOADED.name());
                    p.setString(6, fileName); p.setString(7, fileHash); p.setString(8, rawStorageKey);
                    p.setString(9, contentType); p.setInt(10, 0); p.setLong(11, actorId);
                    p.setTimestamp(12, now); p.setTimestamp(13, now); p.executeUpdate();
                }
                insertEvent(c, ingestionId, "KNOWLEDGE_INGESTION_UPLOADED", actorId, "UPLOADED");
                c.commit();
                rawFile = null;
                return getIngestion(ingestionId);
            } catch (Exception error) {
                try { c.rollback(); } catch (Exception ignored) { }
                throw error;
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) { }
            }
        } catch (KnowledgeBackendException error) {
            deleteQuietly(rawFile);
            throw error;
        } catch (Exception error) {
            deleteQuietly(rawFile);
            throw KnowledgeBackendException.persistence("Knowledge ingestion 初始化未持久化", error);
        }
    }

    private void persistChunks(String versionId, Map<String, Object> ingestion, List<String> chunks) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            ensureSchema(c);
            c.setAutoCommit(false);
            try {
                for (int i = 0; i < chunks.size(); i++) {
                    try (PreparedStatement p = c.prepareStatement(
                            "INSERT IGNORE INTO knowledge_chunks(chunk_id,source_id,document_id,version_id,chunk_index," +
                                    "title,content,content_hash,classification,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                        p.setString(1, chunkId(versionId, i)); p.setString(2, String.valueOf(ingestion.get("sourceId")));
                        p.setString(3, String.valueOf(ingestion.get("documentId"))); p.setString(4, versionId);
                        p.setInt(5, i); p.setString(6, String.valueOf(ingestion.get("sourceFileName")));
                        p.setString(7, chunks.get(i)); p.setString(8, hash(chunks.get(i)));
                        p.setString(9, String.valueOf(ingestion.getOrDefault("classification", ADMIN_CURATED)));
                        p.setTimestamp(10, Timestamp.from(Instant.now())); p.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception error) {
                try { c.rollback(); } catch (Exception ignored) { }
                if (error instanceof SQLException sql) throw sql;
                throw new SQLException(error);
            } finally {
                try { c.setAutoCommit(true); } catch (Exception ignored) { }
            }
        }
    }

    private void activate(String versionId, String documentId) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(
                "UPDATE knowledge_documents SET current_version_id=?,version=version+1,updated_at=? " +
                        "WHERE document_id=? AND EXISTS (SELECT 1 FROM knowledge_ingestions " +
                        "WHERE version_id=? AND status='READY')")) {
            p.setString(1, versionId); p.setTimestamp(2, Timestamp.from(Instant.now()));
            p.setString(3, documentId); p.setString(4, versionId);
            if (p.executeUpdate() != 1) throw new SQLException("READY version activation affected no document");
        }
    }

    private void updateIngestion(String id, KnowledgeIngestionStatus status, String code, String message,
                                 int chunks, String model, String collection, int dimension) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(
                "UPDATE knowledge_ingestions SET status=?,failure_code=?,failure_message=?,chunk_count=?," +
                        "embedding_model=?,embedding_dimension=?,index_collection=?," +
                        "started_at=CASE WHEN ?='PROCESSING' AND started_at IS NULL THEN ? ELSE started_at END," +
                        "completed_at=CASE WHEN ? IN ('READY','FAILED') THEN ? ELSE completed_at END,updated_at=? " +
                        "WHERE ingestion_id=?")) {
            p.setString(1, status.name()); p.setString(2, code); p.setString(3, truncate(message, 512));
            p.setInt(4, chunks); p.setString(5, model);
            if (dimension < 1) p.setNull(6, java.sql.Types.INTEGER); else p.setInt(6, dimension);
            p.setString(7, collection); p.setString(8, status.name()); p.setTimestamp(9, now);
            p.setString(10, status.name()); p.setTimestamp(11, now); p.setTimestamp(12, now); p.setString(13, id);
            p.executeUpdate();
        }
    }

    private List<Float> readVector(Map<String, Object> embedding) {
        Object value = embedding == null ? null : embedding.get("vector");
        if (!(value instanceof List<?> values) || values.size() != KnowledgeVectorStore.DIMENSION) {
            throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "embedding dimension must be 512");
        }
        List<Float> vector = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof Number number)) {
                throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "embedding vector contains non-number");
            }
            float numberValue = number.floatValue();
            if (Float.isNaN(numberValue) || Float.isInfinite(numberValue)) {
                throw new KnowledgeBackendException("AI_CONTRACT_INVALID", "embedding vector contains invalid value");
            }
            vector.add(numberValue);
        }
        return vector;
    }

    private void safeFail(String id, String code, String message) {
        try { updateIngestion(id, KnowledgeIngestionStatus.FAILED, code, message, 0, null, null, 0); }
        catch (Exception ignored) { }
    }

    private String findSource(Connection c, String origin) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT source_id FROM knowledge_sources WHERE source_type=? AND origin_uri=?")) {
            p.setString(1, ADMIN_CURATED); p.setString(2, origin);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private boolean sourceActive(Connection c, String sourceId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT status FROM knowledge_sources WHERE source_id=?")) {
            p.setString(1, sourceId);
            try (ResultSet r = p.executeQuery()) { return r.next() && "ACTIVE".equalsIgnoreCase(r.getString(1)); }
        }
    }

    private Map<String, Object> findDocument(Connection c, String sourceId, String fileName) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT document_id,current_version_id FROM knowledge_documents " +
                        "WHERE source_id=? AND external_reference=?")) {
            p.setString(1, sourceId); p.setString(2, fileName);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("documentId", r.getString(1)); result.put("currentVersionId", r.getString(2));
                return result;
            }
        }
    }

    private Map<String, Object> findByFileHash(Connection c, String sourceId, String fileHash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT ingestion_id,source_id,document_id,version_id,status,source_file_name,source_file_hash," +
                        "raw_storage_key,content_type,chunk_count,embedding_model,embedding_dimension,index_collection," +
                        "failure_code,failure_message,created_by,started_at,completed_at,created_at,updated_at " +
                        "FROM knowledge_ingestions WHERE source_id=? AND source_file_hash=?")) {
            p.setString(1, sourceId); p.setString(2, fileHash);
            try (ResultSet r = p.executeQuery()) { return r.next() ? row(r) : null; }
        }
    }

    private Map<String, Object> findIngestionByVersion(Connection c, String versionId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT ingestion_id,source_id,document_id,version_id,status,source_file_name,source_file_hash," +
                        "raw_storage_key,content_type,chunk_count,embedding_model,embedding_dimension,index_collection," +
                        "failure_code,failure_message,created_by,started_at,completed_at,created_at,updated_at " +
                        "FROM knowledge_ingestions WHERE version_id=?")) {
            p.setString(1, versionId);
            try (ResultSet r = p.executeQuery()) { return r.next() ? row(r) : null; }
        }
    }

    private Map<String, Object> findVersionByHash(Connection c, String documentId, String contentHash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT version_id FROM knowledge_versions WHERE document_id=? AND content_hash=?")) {
            p.setString(1, documentId); p.setString(2, contentHash);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return Map.of("versionId", r.getString(1));
            }
        }
    }

    private int nextVersion(Connection c, String documentId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT COALESCE(MAX(version_number),0)+1 FROM knowledge_versions WHERE document_id=?")) {
            p.setString(1, documentId);
            try (ResultSet r = p.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    private void insertSource(Connection c, String sourceId, String name, String origin) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO knowledge_sources(source_id,name,source_type,description,origin_uri,owner_user_id,status," +
                        "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, sourceId); p.setString(2, name); p.setString(3, ADMIN_CURATED);
            p.setString(4, "Administrator-curated knowledge source"); p.setString(5, origin);
            p.setNull(6, java.sql.Types.BIGINT); p.setString(7, "ACTIVE"); p.setTimestamp(8, now); p.setTimestamp(9, now);
            p.executeUpdate();
        }
    }

    private void insertDocument(Connection c, String documentId, String sourceId, String title, String fileName) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO knowledge_documents(document_id,source_id,title,external_reference,owner_user_id,status," +
                        "current_version_id,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, documentId); p.setString(2, sourceId); p.setString(3, title); p.setString(4, fileName);
            p.setNull(5, java.sql.Types.BIGINT); p.setString(6, "ACTIVE"); p.setNull(7, java.sql.Types.VARCHAR);
            p.setLong(8, 0L); p.setTimestamp(9, now); p.setTimestamp(10, now); p.executeUpdate();
        }
    }

    private void insertVersion(Connection c, String versionId, String documentId, int versionNumber,
                               String contentType, String content, String contentHash, Long actorId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO knowledge_versions(version_id,document_id,version_number,content_type,content,content_hash," +
                        "created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1, versionId); p.setString(2, documentId); p.setInt(3, versionNumber);
            p.setString(4, contentType); p.setString(5, content); p.setString(6, contentHash);
            p.setLong(7, actorId); p.setTimestamp(8, Timestamp.from(Instant.now())); p.executeUpdate();
        }
    }

    private Map<String, Object> row(ResultSet r) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ingestionId", r.getString(1)); m.put("sourceId", r.getString(2)); m.put("documentId", r.getString(3));
        m.put("versionId", r.getString(4)); m.put("status", r.getString(5)); m.put("sourceFileName", r.getString(6));
        m.put("sourceFileHash", r.getString(7)); m.put("rawStorageKey", r.getString(8)); m.put("contentType", r.getString(9));
        m.put("chunkCount", r.getInt(10)); m.put("embeddingModel", r.getString(11)); m.put("embeddingDimension", r.getObject(12));
        m.put("indexCollection", r.getString(13)); m.put("failureCode", r.getString(14)); m.put("failureMessage", r.getString(15));
        m.put("createdBy", r.getObject(16)); m.put("startedAt", instant(r.getTimestamp(17))); m.put("completedAt", instant(r.getTimestamp(18)));
        m.put("createdAt", instant(r.getTimestamp(19))); m.put("updatedAt", instant(r.getTimestamp(20)));
        return m;
    }

    private void ensureSchema(Connection c) throws SQLException {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS knowledge_ingestions (" +
                        "ingestion_id VARCHAR(112) NOT NULL,source_id VARCHAR(112) NOT NULL,document_id VARCHAR(112) NOT NULL," +
                        "version_id VARCHAR(112) NOT NULL,status VARCHAR(32) NOT NULL,source_file_name VARCHAR(255) NOT NULL," +
                        "source_file_hash CHAR(64) NOT NULL,raw_storage_key VARCHAR(255) NOT NULL,content_type VARCHAR(128) NOT NULL," +
                        "chunk_count INT NOT NULL DEFAULT 0,embedding_model VARCHAR(255) NULL,embedding_dimension INT NULL," +
                        "index_collection VARCHAR(255) NULL,failure_code VARCHAR(96) NULL,failure_message VARCHAR(512) NULL," +
                        "created_by BIGINT NULL,started_at TIMESTAMP(3) NULL,completed_at TIMESTAMP(3) NULL," +
                        "created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(ingestion_id)," +
                        "UNIQUE KEY uk_knowledge_ingestion_version(version_id)," +
                        "UNIQUE KEY uk_knowledge_ingestion_file_hash(source_id,source_file_hash)," +
                        "INDEX idx_knowledge_ingestion_status(status),INDEX idx_knowledge_ingestion_document(document_id))");
                s.execute("CREATE TABLE IF NOT EXISTS knowledge_chunks (" +
                        "chunk_id VARCHAR(160) NOT NULL,source_id VARCHAR(112) NOT NULL,document_id VARCHAR(112) NOT NULL," +
                        "version_id VARCHAR(112) NOT NULL,chunk_index INT NOT NULL,title VARCHAR(255) NULL,content LONGTEXT NOT NULL," +
                        "content_hash CHAR(64) NOT NULL,classification VARCHAR(64) NOT NULL DEFAULT 'ADMIN_CURATED'," +
                        "created_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(chunk_id)," +
                        "UNIQUE KEY uk_knowledge_chunk_version_index(version_id,chunk_index),INDEX idx_knowledge_chunk_version(version_id)," +
                        "INDEX idx_knowledge_chunk_source(source_id),INDEX idx_knowledge_chunk_document(document_id))");
                schemaChecked = true;
            }
        }
    }

    private void insertEvent(Connection c, String entityId, String type, Long actorId, String status) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO knowledge_events(event_id,entity_type,entity_id,event_type,actor_id,metadata_json,created_at) VALUES(?,?,?,?,?,?,?)")) {
            p.setString(1, "knowledge-event-" + UUID.randomUUID()); p.setString(2, "KNOWLEDGE_INGESTION");
            p.setString(3, entityId); p.setString(4, type); p.setLong(5, actorId); p.setString(6, json(Map.of("status", status)));
            p.setTimestamp(7, Timestamp.from(Instant.now())); p.executeUpdate();
        }
    }

    private void requireAdmin(Long actorId) {
        if (actorId == null || users == null) {
            throw KnowledgeBackendException.forbidden(actorId == null ? null : String.valueOf(actorId));
        }
        User user = users.getById(actorId.intValue());
        if (user == null || !"admin".equalsIgnoreCase(user.getRole())) {
            throw KnowledgeBackendException.forbidden(String.valueOf(actorId));
        }
    }

    private String safeFileName(String value) {
        if (value == null || value.isBlank()) throw KnowledgeBackendException.validation("fileName 不能为空");
        String name = value.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 255 || name.contains("..") || name.isBlank()) throw KnowledgeBackendException.validation("文件名不合法");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".docx"))) {
            throw KnowledgeBackendException.validation("只支持 PDF、TXT、Markdown、DOCX 文件");
        }
        return name;
    }

    private String safeCorpusPath(String value) {
        if (value == null || value.isBlank()) throw KnowledgeBackendException.validation("relativePath 不能为空");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.contains("../") || normalized.equals("..")) {
            throw KnowledgeBackendException.validation("relativePath 不合法");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".docx"))) {
            throw KnowledgeBackendException.validation("只支持 PDF、TXT、Markdown、DOCX 文件");
        }
        if (normalized.length() > 255) throw KnowledgeBackendException.validation("relativePath 长度不能超过 255");
        return normalized;
    }

    private String leafName(String value) {
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw KnowledgeBackendException.validation(name + " 不能为空");
        if (value.trim().length() > 247) throw KnowledgeBackendException.validation(name + " 长度不能超过 247");
        return value.trim();
    }

    /** Crop isolation accepts an explicit header and also unambiguous crop titles. */
    private String cropClassification(String title, String content) {
        String value = (title == null ? "" : title) + "\n" + (content == null ? "" : content);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:crop|作物)\\s*[:=：]\\s*([a-z][a-z_]*|脆李|巫山脆李|柑橘|水稻|晚稻|再生稻)").matcher(value);
        if (!matcher.find()) {
            String titleValue = title == null ? "" : title.toLowerCase(Locale.ROOT);
            if (titleValue.matches(".*(脆李|巫山脆李|李子|plum).*")) return ADMIN_CURATED + ":crop:plum";
            if (titleValue.matches(".*(柑橘|沃柑|脐橙|椪柑|春见|砂糖橘|爱媛|citrus|orange).*")) return ADMIN_CURATED + ":crop:citrus";
            if (titleValue.matches(".*(水稻|稻谷|中稻|rice).*")) return ADMIN_CURATED + ":crop:rice";
            if (titleValue.matches(".*(晚稻|再生稻).*")) return ADMIN_CURATED + ":crop:rice_late";
            return ADMIN_CURATED;
        }
        String raw = matcher.group(1).toLowerCase(Locale.ROOT);
        String code = switch (raw) {
            case "脆李", "巫山脆李" -> "plum";
            case "柑橘" -> "citrus";
            case "水稻" -> "rice";
            case "晚稻", "再生稻" -> "rice_late";
            default -> raw;
        };
        return ADMIN_CURATED + ":crop:" + code;
    }

    private String chunkId(String versionId, int index) { return versionId + "-chunk-" + index; }

    private String rawStorageKey(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String extension = lower.endsWith(".markdown") ? ".md" : fileName.substring(fileName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        return "knowledge-" + UUID.randomUUID() + extension;
    }

    private Path writeRaw(String key, byte[] bytes) {
        try {
            Files.createDirectories(storageRoot);
            Path target = storageRoot.resolve(key).normalize();
            if (!target.startsWith(storageRoot)) throw new SecurityException("raw knowledge path escaped storage root");
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return target;
        } catch (Exception error) {
            throw KnowledgeBackendException.persistence("原始知识文件保存失败", error);
        }
    }

    private void deleteQuietly(Path path) { if (path != null) try { Files.deleteIfExists(path); } catch (Exception ignored) { } }

    private String hash(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private String hash(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }

    private String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private String truncate(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException error) { return "{}"; } }

    private static String defaultStorageDir() {
        return System.getenv().getOrDefault("FARMAP_KNOWLEDGE_STORAGE_DIR", ".data/knowledge");
    }
}

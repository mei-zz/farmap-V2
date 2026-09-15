package com.mei.zhgy.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.TextEmbeddingProvider;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.knowledge.KnowledgeBackendService;
import com.mei.zhgy.service.knowledge.KnowledgeQueryRewriter;
import com.mei.zhgy.service.knowledge.KnowledgeVectorStore;
import com.mei.zhgy.service.knowledge.QwenKnowledgeVectorStore;
import com.mei.zhgy.service.knowledge.AgriculturalStructuredKnowledgeService;
import com.mei.zhgy.service.knowledge.KnowledgeBm25;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeEvidenceRetriever implements DiagnosisRetriever {
    @Value("${diagnosis.rag.knowledge-resource:knowledge/orchard_management.txt}") private String resourcePath = "knowledge/orchard_management.txt";
    @Value("${farmap.ai.retrieval.knowledge.candidate-count:8}") private int candidateCount = 8;
    @Value("${farmap.ai.retrieval.knowledge.top-k:3}") private int topK = 3;
    @Value("${farmap.ai.retrieval.knowledge.lexical-weight:0.35}") private double lexicalWeight = .35;
    @Value("${farmap.ai.retrieval.knowledge.semantic-weight:0.55}") private double semanticWeight = .55;
    @Value("${farmap.ai.retrieval.knowledge.authority-weight:0.10}") private double authorityWeight = .10;
    @Value("${farmap.ai.retrieval.knowledge.min-score:0.45}") private double minScore = .45;
    @Value("${farmap.ai.retrieval.knowledge.cache-file:.cache/knowledge-embeddings.json}") private String cacheFile = ".cache/knowledge-embeddings.json";
    private final TextEmbeddingProvider embeddingProvider;
    private final KnowledgeBackendService knowledgeBackend;
    private final KnowledgeVectorStore vectorStore;
    private final QwenKnowledgeVectorStore qwenVectorStore;
    private final CropResolver cropResolver;
    private final KnowledgeQueryRewriter queryRewriter;
    private final AgriculturalStructuredKnowledgeService structuredKnowledge;
    private List<Chunk> chunks = Collections.emptyList();
    private final Map<String, EmbeddingProvider.EmbeddingResult> cache = new LinkedHashMap<>();

    public KnowledgeEvidenceRetriever() { this(null, null, null, null, new CropResolver()); }
    public KnowledgeEvidenceRetriever(TextEmbeddingProvider embeddingProvider) { this(embeddingProvider, null, null, null, new CropResolver()); }
    public KnowledgeEvidenceRetriever(TextEmbeddingProvider embeddingProvider, KnowledgeBackendService knowledgeBackend) { this(embeddingProvider, knowledgeBackend, null, null, new CropResolver()); }
    public KnowledgeEvidenceRetriever(TextEmbeddingProvider embeddingProvider, KnowledgeBackendService knowledgeBackend, KnowledgeVectorStore vectorStore) { this(embeddingProvider, knowledgeBackend, vectorStore, null, new CropResolver()); }
    public KnowledgeEvidenceRetriever(TextEmbeddingProvider embeddingProvider,
                                                 KnowledgeBackendService knowledgeBackend,
                                                 KnowledgeVectorStore vectorStore,
                                                 QwenKnowledgeVectorStore qwenVectorStore) {
        this(embeddingProvider, knowledgeBackend, vectorStore, qwenVectorStore, new CropResolver());
    }

    public KnowledgeEvidenceRetriever(TextEmbeddingProvider embeddingProvider,
                                      KnowledgeBackendService knowledgeBackend,
                                      KnowledgeVectorStore vectorStore,
                                      QwenKnowledgeVectorStore qwenVectorStore,
                                      CropResolver cropResolver) {
        this(embeddingProvider, knowledgeBackend, vectorStore, qwenVectorStore, cropResolver,
                new KnowledgeQueryRewriter(cropResolver, null), null);
    }

    @Autowired
    public KnowledgeEvidenceRetriever(TextEmbeddingProvider embeddingProvider,
                                      KnowledgeBackendService knowledgeBackend,
                                      KnowledgeVectorStore vectorStore,
                                      QwenKnowledgeVectorStore qwenVectorStore,
                                      CropResolver cropResolver,
                                      KnowledgeQueryRewriter queryRewriter,
                                      AgriculturalStructuredKnowledgeService structuredKnowledge) {
        this.embeddingProvider = embeddingProvider;
        this.knowledgeBackend = knowledgeBackend;
        this.vectorStore = vectorStore;
        this.qwenVectorStore = qwenVectorStore;
        this.cropResolver = cropResolver == null ? new CropResolver() : cropResolver;
        this.queryRewriter = queryRewriter == null ? new KnowledgeQueryRewriter(this.cropResolver, null) : queryRewriter;
        this.structuredKnowledge = structuredKnowledge;
    }

    @PostConstruct public void loadKnowledge() {
        try {
            String text = StreamUtils.copyToString(new ClassPathResource(resourcePath).getInputStream(), StandardCharsets.UTF_8);
            String[] paragraphs = text.split("\\n\\s*\\n");
            List<Chunk> loaded = new ArrayList<>();
            for (int i = 0; i < paragraphs.length; i++) {
                String content = paragraphs[i].replaceAll("\\s+", " ").trim();
                if (!content.isEmpty()) loaded.add(new Chunk("orchard-" + (i + 1), content,
                        "knowledge-source-packaged-orchard", "FarMap Curated Orchard Knowledge",
                        "knowledge-document-packaged-orchard", "orchard_management.txt",
                        "knowledge-version-packaged-orchard-v1", hash(content)));
            }
            chunks = loaded;
            loadCache();
            if (knowledgeBackend != null && knowledgeBackend.hasPersistentStore()) {
                knowledgeBackend.ensurePackagedKnowledge("classpath:" + resourcePath, text);
            }
        } catch (Exception error) { log.warn("Knowledge resource unavailable: {}", error.getClass().getSimpleName()); }
    }

    @Override public String modality() { return "knowledge"; }

    @Override public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        return retrieveWithStatus(request, null).getEvidence();
    }

    @Override public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request, Long userId) {
        return retrieveWithStatus(request, userId).getEvidence();
    }

    public RetrievalResult retrieveWithStatus(DiagnosisAnalyzeRequest request, Long userId) {
        KnowledgeQueryRewriter.RewrittenQuery rewritten = queryRewriter.rewrite(request);
        String query = rewritten.query();
        String cropCode = rewritten.cropCode();
        int effectiveTopK = rewritten.topK();
        boolean untaggedOnly = flag(request == null ? null : request.getContext(), "untaggedOnly");
        AgriculturalStructuredKnowledgeService.LookupResult structured = untaggedOnly || structuredKnowledge == null
                ? AgriculturalStructuredKnowledgeService.LookupResult.unavailable()
                : structuredKnowledge.lookup(request, rewritten);
        List<DiagnosisEvidenceVO> structuredEvidence = structuredEvidence(structured, request, rewritten);
        if (structured.deterministic() && !structuredEvidence.isEmpty()) {
            return new RetrievalResult("READY", limit(structuredEvidence, effectiveTopK));
        }

        // Production diagnosis has one authoritative text-vector generation.
        // When the Qwen/Milvus wiring exists, never fall back to the historical
        // BGE store or lexical packaged corpus.
        if (qwenVectorStore != null) {
            if (!qwenVectorStore.isConfigured()) {
                return structuredEvidence.isEmpty() ? new RetrievalResult("UNAVAILABLE", Collections.emptyList())
                        : new RetrievalResult("DEGRADED", limit(structuredEvidence, effectiveTopK));
            }
            QwenKnowledgeVectorStore.SearchResult result = qwenVectorStore.searchResult(query, userId,
                    effectiveTopK, true, cropCode);
            // Keep focused test doubles and older integrations compatible with
            // the pre-P0 overload while production uses the crop-aware call.
            if (result == null) result = qwenVectorStore.searchResult(query, userId,
                    effectiveTopK, true);
            if ("UNAVAILABLE".equals(result.getStatus())) {
                return structuredEvidence.isEmpty() ? new RetrievalResult("UNAVAILABLE", Collections.emptyList())
                        : new RetrievalResult("DEGRADED", limit(structuredEvidence, effectiveTopK));
            }
            List<QwenKnowledgeVectorStore.KnowledgeVectorHit> hits = untaggedOnly
                    ? result.getHits().stream().filter(hit -> !hasAnyCropTag(hit.classification, hit.content)).toList()
                    : result.getHits();
            if (hits.isEmpty()) {
                return structuredEvidence.isEmpty() ? new RetrievalResult("READY_EMPTY", Collections.emptyList())
                        : new RetrievalResult(result.getStatus(), limit(structuredEvidence, effectiveTopK));
            }
            List<DiagnosisEvidenceVO> evidence = qwenSemanticEvidence(hits, request, result, rewritten);
            evidence.addAll(0, structuredEvidence);
            return new RetrievalResult(result.getStatus(), limit(evidence, effectiveTopK));
        }

        List<Chunk> available = chunks;
        String sourceMode = "DEMO";
        String status = "READY";
        if (knowledgeBackend != null) {
            List<KnowledgeBackendService.RetrievalUnit> units = knowledgeBackend.retrieveCurrent(userId);
            available = units.stream().map(unit -> new Chunk(unit.chunkId, unit.content, unit.sourceId, unit.sourceName, unit.documentId, unit.documentTitle, unit.versionId, unit.contentHash, unit.classification)).toList();
            sourceMode = knowledgeBackend.hasPersistentStore() ? "REAL" : "DEMO";
            status = available.isEmpty() ? "READY_EMPTY" : "READY";
        } else if (available.isEmpty()) {
            return structuredEvidence.isEmpty() ? new RetrievalResult("UNAVAILABLE", Collections.emptyList())
                    : new RetrievalResult("READY", limit(structuredEvidence, effectiveTopK));
        }
        if (available.isEmpty()) return new RetrievalResult(status, limit(structuredEvidence, effectiveTopK));
        if (untaggedOnly) available = available.stream().filter(chunk -> chunk.cropCode == null).toList();
        if (available.isEmpty()) return new RetrievalResult("READY_EMPTY", limit(structuredEvidence, effectiveTopK));
        boolean strictCropFilter = cropCode != null && (qwenVectorStore != null
                || (knowledgeBackend != null && knowledgeBackend.hasPersistentStore()));
        if (strictCropFilter) {
            List<Chunk> tagged = available.stream().filter(chunk -> cropCode.equals(chunk.cropCode)).toList();
            if (!tagged.isEmpty()) {
                available = tagged;
            } else {
                // One safe relaxation for legacy public chunks: retain only
                // untagged chunks, never a chunk tagged for another crop.
                available = available.stream().filter(chunk -> chunk.cropCode == null).toList();
                if (available.isEmpty()) return new RetrievalResult("READY_EMPTY", limit(structuredEvidence, effectiveTopK));
            }
        }
        if (vectorStore != null) {
            if (!strictCropFilter && !untaggedOnly) {
                List<KnowledgeVectorStore.KnowledgeVectorHit> semanticHits = vectorStore.search(query, userId, effectiveTopK);
                if (!semanticHits.isEmpty()) {
                    List<DiagnosisEvidenceVO> evidence = semanticEvidence(semanticHits, request, rewritten);
                    evidence.addAll(0, structuredEvidence);
                    return new RetrievalResult("READY", limit(evidence, effectiveTopK));
                }
            }
        }
        List<Chunk> filteredAvailable = available;
        Map<String, Double> bm25Scores = bm25Scores(filteredAvailable, query);
        List<Scored> candidates = filteredAvailable.stream().map(chunk -> new Scored(chunk, bm25Scores.getOrDefault(chunk.id, 0D)))
                .sorted(Comparator.comparingDouble((Scored value) -> value.lexical).reversed())
                .limit(candidateCount).collect(Collectors.toList());
        boolean semantic = rerank(query, candidates);
        candidates = candidates.stream()
                .filter(value -> value.lexical > 0D || (semantic && value.semantic >= minScore))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return new RetrievalResult("READY_EMPTY", limit(structuredEvidence, effectiveTopK));
        candidates.sort(Comparator.comparingDouble((Scored value) -> value.score(lexicalWeight, semanticWeight, authorityWeight, semantic)).reversed());
        List<DiagnosisEvidenceVO> output = new ArrayList<>();
        for (int i = 0; i < Math.min(effectiveTopK, candidates.size()); i++) {
            Scored item = candidates.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", sourceMode);
            metadata.put("knowledgeStatus", status);
            metadata.put("retrievalType", semantic ? "hybrid" : "lexical");
            putRewriteMetadata(metadata, rewritten);
            metadata.put("chunkId", item.chunk.id);
            metadata.put("cropCode", item.chunk.cropCode);
            metadata.put("contentHash", item.chunk.contentHash == null ? hash(item.chunk.text) : item.chunk.contentHash);
            metadata.put("sourceId", item.chunk.sourceId);
            metadata.put("documentId", item.chunk.documentId);
            metadata.put("versionId", item.chunk.versionId);
            metadata.put("candidateCount", candidates.size());
            metadata.put("lexicalScore", item.lexical);
            metadata.put("bm25Score", item.lexical);
            if (semantic) metadata.put("semanticScore", item.semantic);
            metadata.put("embeddingModel", semantic ? "BAAI/bge-small-zh-v1.5" : "not-invoked");
            metadata.put("embeddingProvider", semantic ? "LOCAL" : "FALLBACK");
            metadata.put("embeddingDimension", semantic ? 512 : 0);
            String evidenceId = "knowledge-" + (item.chunk.versionId == null ? "legacy" : item.chunk.versionId) + "-" + item.chunk.id;
            output.add(DiagnosisEvidenceVO.builder().id(evidenceId).modality("knowledge")
                    .title("农业知识：" + item.chunk.id)
                    .summary("知识段落经" + (semantic ? " BGE 混合排序" : "关键词召回") + "后入选。")
                    .score(item.score(lexicalWeight, semanticWeight, authorityWeight, semantic))
                    .source(DiagnosisEvidenceVO.Source.builder().type("knowledge").name(item.chunk.sourceName == null ? "Agricultural knowledge base" : item.chunk.sourceName)
                            .sourceId(item.chunk.sourceId).documentId(item.chunk.documentId).versionId(item.chunk.versionId)
                            .document(item.chunk.documentTitle == null ? "orchard_management.txt" : item.chunk.documentTitle).section(request.getGrowthStage()).fieldId(request.getFieldId()).build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().textSnippet(item.chunk.text.substring(0, Math.min(240, item.chunk.text.length()))).build())
                    .metadata(metadata).build());
        }
        output.addAll(0, structuredEvidence);
        return new RetrievalResult(status, limit(output, effectiveTopK));
    }

    private List<DiagnosisEvidenceVO> semanticEvidence(List<KnowledgeVectorStore.KnowledgeVectorHit> hits, DiagnosisAnalyzeRequest request, KnowledgeQueryRewriter.RewrittenQuery rewritten) {
        List<DiagnosisEvidenceVO> output = new ArrayList<>();
        for (KnowledgeVectorStore.KnowledgeVectorHit hit : hits) {
            Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("sourceMode", "REAL"); metadata.put("knowledgeStatus", "READY"); metadata.put("retrievalType", "milvus-semantic"); metadata.put("chunkId", hit.chunkId); metadata.put("sourceId", hit.sourceId); metadata.put("documentId", hit.documentId); metadata.put("versionId", hit.versionId); metadata.put("contentHash", hit.contentHash); metadata.put("embeddingModel", "BAAI/bge-small-zh-v1.5"); metadata.put("embeddingProvider", "LOCAL"); metadata.put("embeddingDimension", 512); metadata.put("classification", hit.classification); putRewriteMetadata(metadata, rewritten);
            output.add(DiagnosisEvidenceVO.builder().id("knowledge-" + hit.versionId + "-" + hit.chunkId).modality("knowledge").title("农业知识：" + hit.chunkId).summary("经 BGE 向量检索命中管理员维护的知识片段。").score(hit.score).source(DiagnosisEvidenceVO.Source.builder().type("knowledge").name(hit.sourceName).sourceId(hit.sourceId).documentId(hit.documentId).versionId(hit.versionId).document(hit.documentTitle).section(request.getGrowthStage()).fieldId(request.getFieldId()).build()).preview(DiagnosisEvidenceVO.Preview.builder().textSnippet(hit.content.substring(0, Math.min(240, hit.content.length()))).build()).metadata(metadata).build());
        }
        return output;
    }

    private List<DiagnosisEvidenceVO> qwenSemanticEvidence(List<QwenKnowledgeVectorStore.KnowledgeVectorHit> hits,
                                                            DiagnosisAnalyzeRequest request,
                                                            QwenKnowledgeVectorStore.SearchResult result,
                                                            KnowledgeQueryRewriter.RewrittenQuery rewritten) {
        List<DiagnosisEvidenceVO> output = new ArrayList<>();
        for (QwenKnowledgeVectorStore.KnowledgeVectorHit hit : hits) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", "REAL");
            metadata.put("knowledgeStatus", result.getStatus());
            metadata.put("retrievalType", hit.bm25Score > 0D ? "hybrid-bm25-vector" : "qwen-vector-gte-rerank");
            metadata.put("chunkId", hit.chunkId);
            metadata.put("sourceId", hit.sourceId);
            metadata.put("documentId", hit.documentId);
            metadata.put("versionId", hit.versionId);
            metadata.put("relativePath", hit.relativePath);
            metadata.put("contentHash", hit.contentHash);
            metadata.put("classification", hit.classification);
            metadata.put("vectorScore", hit.vectorScore);
            metadata.put("bm25Score", hit.bm25Score);
            metadata.put("hybridScore", hit.hybridScore);
            metadata.put("retrievalScore", hit.hybridScore > 0D ? hit.hybridScore : hit.vectorScore);
            metadata.put("rerankScore", Double.isFinite(hit.rerankScore) ? hit.rerankScore : null);
            metadata.put("evidenceConfidence", Double.isFinite(hit.rerankScore) ? hit.rerankScore : hit.vectorScore);
            metadata.put("embeddingProvider", result.getRetrievalProvider());
            metadata.put("embeddingModel", QwenKnowledgeVectorStore.MODEL);
            metadata.put("embeddingDimension", 1024);
            metadata.put("rerankerModel", "gte-rerank-v2");
            metadata.put("rerankerStatus", result.getRerankerStatus());
            putRewriteMetadata(metadata, rewritten);
            output.add(DiagnosisEvidenceVO.builder()
                    .id("knowledge-" + hit.versionId + "-" + hit.chunkId)
                    .modality("knowledge")
                    .title("管理员知识：" + hit.chunkId)
                    .summary(hit.bm25Score > 0D ? "经 BM25 关键词与 Qwen 向量混合召回，再由 GTE reranker 排序的当前知识版本。" : "经 Qwen 1024 维向量召回并由 GTE reranker 排序的当前知识版本。")
                    .score(Double.isFinite(hit.rerankScore) ? hit.rerankScore : hit.hybridScore > 0D ? hit.hybridScore : hit.vectorScore)
                    .source(DiagnosisEvidenceVO.Source.builder().type("knowledge").name(hit.sourceName)
                            .sourceId(hit.sourceId).documentId(hit.documentId).versionId(hit.versionId)
                            .chunkId(hit.chunkId).relativePath(hit.relativePath).contentHash(hit.contentHash)
                            .vectorScore(hit.vectorScore).retrievalScore(hit.hybridScore > 0D ? hit.hybridScore : hit.vectorScore)
                            .rerankScore(Double.isFinite(hit.rerankScore) ? hit.rerankScore : null)
                            .embeddingModel(QwenKnowledgeVectorStore.MODEL).rerankerModel("gte-rerank-v2")
                            .classification(hit.classification).sourceMode("REAL").status(result.getStatus())
                            .document(hit.documentTitle).section(request.getGrowthStage()).fieldId(request.getFieldId()).build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().textSnippet(hit.content.substring(0, Math.min(240, hit.content.length()))).build())
                    .metadata(metadata).build());
        }
        return output;
    }

    private List<DiagnosisEvidenceVO> structuredEvidence(AgriculturalStructuredKnowledgeService.LookupResult result,
                                                          DiagnosisAnalyzeRequest request,
                                                          KnowledgeQueryRewriter.RewrittenQuery rewritten) {
        if (result == null || result.hits().isEmpty()) return new ArrayList<>();
        List<DiagnosisEvidenceVO> output = new ArrayList<>();
        for (AgriculturalStructuredKnowledgeService.StructuredHit hit : result.hits()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", "REAL"); metadata.put("knowledgeStatus", result.status()); metadata.put("retrievalType", "structured");
            metadata.put("structured", true); metadata.put("ruleId", hit.ruleId()); metadata.put("cropCode", hit.cropCode());
            metadata.put("stageCode", hit.stageCode()); metadata.put("knowledgeType", hit.knowledgeType()); metadata.put("knowledgeKey", hit.key());
            metadata.put("sourceRef", hit.sourceRef()); metadata.put("thresholdValue", hit.thresholdValue()); metadata.put("unit", hit.unit());
            putRewriteMetadata(metadata, rewritten);
            output.add(DiagnosisEvidenceVO.builder().id("structured-" + hit.ruleId()).modality("knowledge")
                    .title("结构化农业知识：" + hit.key()).summary("按作物、物候期和知识类型命中关系型规则。")
                    .score(Math.max(0D, Math.min(1D, hit.score()))).source(DiagnosisEvidenceVO.Source.builder()
                            .type("knowledge").name("Agricultural structured knowledge").sourceId("agri-stage-knowledge")
                            .documentId("agri-stage-knowledge").chunkId(hit.ruleId()).classification("STRUCTURED")
                            .sourceMode("REAL").status(result.status()).document("agri_stage_knowledge").section(hit.stageCode())
                            .fieldId(request.getFieldId()).build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().textSnippet(hit.content()).build()).metadata(metadata).build());
        }
        return output;
    }

    private void putRewriteMetadata(Map<String, Object> metadata, KnowledgeQueryRewriter.RewrittenQuery rewritten) {
        if (rewritten == null) return;
        metadata.put("originalQuery", rewritten.originalQuery()); metadata.put("rewrittenQuery", rewritten.query());
        metadata.put("queryType", rewritten.queryType()); metadata.put("dynamicTopK", rewritten.topK());
        metadata.put("referenceExpanded", rewritten.referenceExpanded()); metadata.put("cropLabel", rewritten.cropLabel());
        metadata.put("stageLabel", rewritten.stageLabel());
    }

    private boolean flag(Map<String, Object> context, String key) {
        if (context == null) return false;
        Object value = context.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean hasAnyCropTag(String classification, String content) {
        String source = (classification == null ? "" : classification) + "\n" + (content == null ? "" : content);
        if (source.toLowerCase(Locale.ROOT).matches("(?s).*crop\\s*[:=\\-].*")) return true;
        String normalized = classification == null ? "" : classification.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("plum") || normalized.equals("citrus") || normalized.equals("rice") || normalized.equals("rice_late");
    }

    private Map<String, Double> bm25Scores(List<Chunk> available, String query) {
        List<KnowledgeBm25.ScoredDocument> ranked = new KnowledgeBm25().rank(
                available.stream().map(chunk -> new KnowledgeBm25.Document(chunk.id, chunk.text)).toList(), query, candidateCount);
        double max = ranked.stream().mapToDouble(KnowledgeBm25.ScoredDocument::score).max().orElse(0D);
        Map<String, Double> scores = new LinkedHashMap<>();
        ranked.forEach(item -> scores.put(item.document().id(), max <= 0D ? 0D : item.score() / max));
        return scores;
    }

    private List<DiagnosisEvidenceVO> limit(List<DiagnosisEvidenceVO> evidence, int limit) {
        if (evidence == null || evidence.size() <= limit) return evidence == null ? new ArrayList<>() : evidence;
        return new ArrayList<>(evidence.subList(0, limit));
    }

    private boolean rerank(String query, List<Scored> candidates) {
        if (embeddingProvider == null) return false;
        List<Scored> missing = candidates.stream().filter(value -> !cache.containsKey(key(value.chunk))).collect(Collectors.toList());
        List<EmbeddingProvider.EmbeddingResult> generated = embeddingProvider.embedTexts(missing.stream().map(value -> value.chunk.text).collect(Collectors.toList()));
        for (int i = 0; i < Math.min(missing.size(), generated.size()); i++) cache.put(key(missing.get(i).chunk), generated.get(i));
        if (!generated.isEmpty()) saveCache();
        EmbeddingProvider.EmbeddingResult queryVector = embeddingProvider.embedText(query);
        if (queryVector.getVector().size() != 512) return false;
        for (Scored candidate : candidates) {
            EmbeddingProvider.EmbeddingResult vector = cache.get(key(candidate.chunk));
            if (vector == null || vector.getVector().size() != 512) return false;
            candidate.semantic = cosine(queryVector.getVector(), vector.getVector());
        }
        return true;
    }

    private double cosine(List<Float> a, List<Float> b) { double sum=0,aa=0,bb=0; for(int i=0;i<a.size();i++){sum+=a.get(i)*b.get(i);aa+=a.get(i)*a.get(i);bb+=b.get(i)*b.get(i);} return aa==0||bb==0?0:sum/Math.sqrt(aa*bb); }
    private String key(Chunk chunk) { return chunk.id + ":" + hash(chunk.text) + ":bge-small-zh-v1.5:v1"; }
    private void loadCache() {
        try {
            Path file = Path.of(cacheFile);
            if (!Files.isRegularFile(file)) return;
            Map<String, List<Float>> stored = new ObjectMapper().readValue(file.toFile(), new TypeReference<Map<String, List<Float>>>() {});
            stored.forEach((key, vector) -> cache.put(key, new EmbeddingProvider.EmbeddingResult(vector, "LOCAL", "BAAI/bge-small-zh-v1.5", vector.size(), 0)));
        } catch (Exception error) { log.warn("Knowledge embedding cache ignored: {}", error.getClass().getSimpleName()); }
    }
    private void saveCache() {
        try {
            Path file = Path.of(cacheFile); if (file.getParent() != null) Files.createDirectories(file.getParent());
            Map<String, List<Float>> stored = new LinkedHashMap<>(); cache.forEach((key, value) -> stored.put(key, value.getVector()));
            new ObjectMapper().writeValue(file.toFile(), stored);
        } catch (Exception error) { log.warn("Knowledge embedding cache write failed: {}", error.getClass().getSimpleName()); }
    }
    private String hash(String value) { try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format("%02x",b)); return out.substring(0,16); } catch(Exception error){ return String.valueOf(value.hashCode()); } }
    public static final class RetrievalResult { private final String status; private final List<DiagnosisEvidenceVO> evidence; public RetrievalResult(String status,List<DiagnosisEvidenceVO> evidence){this.status=status;this.evidence=evidence;} public String getStatus(){return status;} public List<DiagnosisEvidenceVO> getEvidence(){return evidence;} }
    private static class Chunk {
        final String id,text,sourceId,sourceName,documentId,documentTitle,versionId,contentHash,cropCode,classification;
        Chunk(String id,String text){this(id,text,null,null,null,null,null,null,null);}
        Chunk(String id,String text,String sourceId,String sourceName,String documentId,String documentTitle,String versionId,String contentHash){this(id,text,sourceId,sourceName,documentId,documentTitle,versionId,contentHash,null);}
        Chunk(String id,String text,String sourceId,String sourceName,String documentId,String documentTitle,String versionId,String contentHash,String classification){
            this.id=id;this.text=text;this.sourceId=sourceId;this.sourceName=sourceName;this.documentId=documentId;this.documentTitle=documentTitle;this.versionId=versionId;this.contentHash=contentHash;this.classification=classification;this.cropCode=taggedCrop(classification, text);
        }
        private static String taggedCrop(String classification, String value){
            String source = (classification == null ? "" : classification) + "\n" + (value == null ? "" : value);
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:crop|作物)\\s*[:=：-]\\s*([a-z][a-z_]*|脆李|巫山脆李|柑橘|水稻|晚稻|再生稻)").matcher(source);
            if(!m.find()) return null;
            return switch(m.group(1).toLowerCase(Locale.ROOT)){case "脆李","巫山脆李"->"plum";case "柑橘"->"citrus";case "水稻"->"rice";case "晚稻","再生稻"->"rice_late";default->m.group(1).toLowerCase(Locale.ROOT);};
        }
    }
    private static class Scored { final Chunk chunk; final double lexical; double semantic; Scored(Chunk chunk,double lexical){this.chunk=chunk;this.lexical=lexical;} double score(double lw,double sw,double aw,boolean semanticReady){return Math.max(0,Math.min(1,lw*lexical+(semanticReady?sw*semantic:0)+aw));} }
}

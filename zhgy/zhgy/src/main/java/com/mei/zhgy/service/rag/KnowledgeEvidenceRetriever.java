package com.mei.zhgy.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.TextEmbeddingProvider;
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
import java.util.stream.Stream;

@Slf4j
@Component
public class KnowledgeEvidenceRetriever implements DiagnosisRetriever {
    @Value("${diagnosis.rag.knowledge-resource:knowledge/orchard_management.txt}") private String resourcePath;
    @Value("${farmap.ai.retrieval.knowledge.candidate-count:8}") private int candidateCount = 8;
    @Value("${farmap.ai.retrieval.knowledge.top-k:3}") private int topK = 3;
    @Value("${farmap.ai.retrieval.knowledge.lexical-weight:0.35}") private double lexicalWeight = .35;
    @Value("${farmap.ai.retrieval.knowledge.semantic-weight:0.55}") private double semanticWeight = .55;
    @Value("${farmap.ai.retrieval.knowledge.authority-weight:0.10}") private double authorityWeight = .10;
    @Value("${farmap.ai.retrieval.knowledge.cache-file:.cache/knowledge-embeddings.json}") private String cacheFile = ".cache/knowledge-embeddings.json";
    private final TextEmbeddingProvider embeddingProvider;
    private List<Chunk> chunks = Collections.emptyList();
    private final Map<String, EmbeddingProvider.EmbeddingResult> cache = new LinkedHashMap<>();

    public KnowledgeEvidenceRetriever() { this.embeddingProvider = null; }
    @Autowired public KnowledgeEvidenceRetriever(TextEmbeddingProvider embeddingProvider) { this.embeddingProvider = embeddingProvider; }

    @PostConstruct public void loadKnowledge() {
        try {
            String text = StreamUtils.copyToString(new ClassPathResource(resourcePath).getInputStream(), StandardCharsets.UTF_8);
            String[] paragraphs = text.split("\\n\\s*\\n");
            List<Chunk> loaded = new ArrayList<>();
            for (int i = 0; i < paragraphs.length; i++) {
                String content = paragraphs[i].replaceAll("\\s+", " ").trim();
                if (!content.isEmpty()) loaded.add(new Chunk("orchard-" + (i + 1), content));
            }
            chunks = loaded;
            loadCache();
        } catch (Exception error) { log.warn("Knowledge resource unavailable: {}", error.getClass().getSimpleName()); }
    }

    @Override public String modality() { return "knowledge"; }

    @Override public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (chunks.isEmpty()) return Collections.emptyList();
        String query = Stream.of(request.getCrop(), request.getGrowthStage(), request.getQuery(), "黄化")
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.joining(" "));
        List<Scored> candidates = chunks.stream().map(chunk -> new Scored(chunk, lexical(chunk.text, query)))
                .sorted(Comparator.comparingDouble((Scored value) -> value.lexical).reversed())
                .limit(candidateCount).collect(Collectors.toList());
        boolean semantic = rerank(query, candidates);
        candidates.sort(Comparator.comparingDouble((Scored value) -> value.score(lexicalWeight, semanticWeight, authorityWeight, semantic)).reversed());
        List<DiagnosisEvidenceVO> output = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, candidates.size()); i++) {
            Scored item = candidates.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", semantic ? "LOCAL" : "FALLBACK");
            metadata.put("retrievalType", semantic ? "hybrid" : "lexical");
            metadata.put("chunkId", item.chunk.id);
            metadata.put("contentHash", hash(item.chunk.text));
            metadata.put("candidateCount", candidates.size());
            metadata.put("lexicalScore", item.lexical);
            if (semantic) metadata.put("semanticScore", item.semantic);
            metadata.put("embeddingModel", semantic ? "BAAI/bge-small-zh-v1.5" : "not-invoked");
            metadata.put("embeddingProvider", semantic ? "LOCAL" : "FALLBACK");
            metadata.put("embeddingDimension", semantic ? 512 : 0);
            output.add(DiagnosisEvidenceVO.builder().id("knowledge_" + (i + 1)).modality("knowledge")
                    .title("农业知识：" + item.chunk.id)
                    .summary("知识段落经" + (semantic ? " BGE 混合排序" : "关键词召回") + "后入选。")
                    .score(item.score(lexicalWeight, semanticWeight, authorityWeight, semantic))
                    .source(DiagnosisEvidenceVO.Source.builder().type("knowledge").name("Agricultural knowledge base")
                            .document("orchard_management.txt").section(request.getGrowthStage()).fieldId(request.getFieldId()).build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().textSnippet(item.chunk.text.substring(0, Math.min(240, item.chunk.text.length()))).build())
                    .metadata(metadata).build());
        }
        return output;
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

    private double lexical(String text, String query) {
        String lower = text.toLowerCase(Locale.ROOT);
        long hits = Stream.of(query.split("\\s+|(?<=\\p{IsHan})|(?=\\p{IsHan})"))
                .filter(word -> word.length() > 1 && lower.contains(word.toLowerCase(Locale.ROOT))).count();
        return Math.min(1D, hits / 5D);
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
    private static class Chunk { final String id,text; Chunk(String id,String text){this.id=id;this.text=text;} }
    private static class Scored { final Chunk chunk; final double lexical; double semantic; Scored(Chunk chunk,double lexical){this.chunk=chunk;this.lexical=lexical;} double score(double lw,double sw,double aw,boolean semanticReady){return Math.max(0,Math.min(1,lw*lexical+(semanticReady?sw*semantic:0)+aw));} }
}

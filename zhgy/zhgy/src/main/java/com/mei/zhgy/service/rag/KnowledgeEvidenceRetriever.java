package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 轻量知识检索器：先在现有 classpath 农业知识文件中做段落级关键词召回。
 * 后续可在不改变 Evidence Contract 的前提下替换为 Embedding/Milvus 检索。
 */
@Slf4j
@Component
public class KnowledgeEvidenceRetriever implements DiagnosisRetriever {
    @Value("${diagnosis.rag.knowledge-resource:knowledge/orchard_management.txt}")
    private String resourcePath;

    private String knowledgeText = "";

    @PostConstruct
    public void loadKnowledge() {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            knowledgeText = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            log.info("农业知识资源加载完成，字符数: {}", knowledgeText.length());
        } catch (Exception exception) {
            log.warn("农业知识资源加载失败: {}", exception.getMessage());
            knowledgeText = "";
        }
    }

    @Override
    public String modality() {
        return "knowledge";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (knowledgeText.isEmpty()) {
            return Collections.emptyList();
        }

        String snippet = selectSnippet(request);
        if (snippet.isEmpty()) {
            return Collections.emptyList();
        }

        return Collections.singletonList(DiagnosisEvidenceVO.builder()
                .id("knowledge_1")
                .modality("knowledge")
                .title("农业知识：物候期水分管理")
                .summary("知识库段落被关键词召回，用于约束诊断结论与建议。")
                .score(0.79)
                .source(DiagnosisEvidenceVO.Source.builder()
                        .type("knowledge")
                        .name("Agricultural knowledge base")
                        .document("orchard_management.txt")
                        .section(request.getGrowthStage() == null ? "果树管理" : request.getGrowthStage())
                        .fieldId(request.getFieldId())
                        .build())
                .preview(DiagnosisEvidenceVO.Preview.builder().textSnippet(snippet).build())
                .metadata(new LinkedHashMap<>(Collections.singletonMap("sourceMode", "classpath-retrieval")))
                .build());
    }

    private String selectSnippet(DiagnosisAnalyzeRequest request) {
        String query = Stream.of(request.getCrop(), request.getGrowthStage(), request.getQuery(), "黄化", "水分管理")
                .filter(value -> value != null && !value.trim().isEmpty())
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);

        String selected = Stream.of(knowledgeText.split("\\n\\s*\\n"))
                .filter(paragraph -> containsAnyKeyword(paragraph.toLowerCase(Locale.ROOT), query))
                .findFirst()
                .orElse(knowledgeText);
        return selected.replaceAll("\\s+", " ").trim().substring(0, Math.min(240, selected.replaceAll("\\s+", " ").trim().length()));
    }

    private boolean containsAnyKeyword(String paragraph, String query) {
        return paragraph.contains("膨大") || paragraph.contains("水分") || paragraph.contains("黄化") || paragraph.contains("病害") || paragraph.contains("果实");
    }
}


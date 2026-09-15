package com.mei.zhgy.service.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.PhenologyService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in live regression for structured hit rate and crop isolation. */
class KnowledgeGoldenMysqlEvaluationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CropResolver crops = new CropResolver();
    private final KnowledgeQueryRewriter rewriter = new KnowledgeQueryRewriter(crops, new PhenologyService(crops));

    @Test
    void evaluatesStructuredHitRateAndCrossCropIsolation() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("FARMAP_KNOWLEDGE_GOLDEN_EVAL")),
                "set FARMAP_KNOWLEDGE_GOLDEN_EVAL=true to run against the configured MySQL knowledge tables");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        try (var connection = dataSource.getConnection()) {
            // Probe before constructing the evaluator so unavailable local DBs skip cleanly.
        } catch (Exception error) {
            Assumptions.assumeTrue(false, "configured MySQL is not available for the golden evaluation");
            return;
        }

        AgriculturalStructuredKnowledgeService service = new AgriculturalStructuredKnowledgeService(dataSource, crops);
        List<GoldenCase> cases = load();
        int expectedStructured = 0;
        int structuredHits = 0;
        int expectedKeyHits = 0;
        int wrongCropHits = 0;
        int allHits = 0;
        for (GoldenCase item : cases) {
            DiagnosisAnalyzeRequest request = new DiagnosisAnalyzeRequest();
            request.setCrop(item.crop());
            request.setGrowthStage(item.stage());
            request.setQuery(item.query());
            KnowledgeQueryRewriter.RewrittenQuery rewritten = rewriter.rewrite(request);
            AgriculturalStructuredKnowledgeService.LookupResult result = service.lookup(request, rewritten);
            String cropCode = crops.resolve(item.crop()).code();
            if (item.expectedStructured()) {
                expectedStructured++;
                if (!result.hits().isEmpty()) structuredHits++;
                if (result.hits().stream().anyMatch(hit -> keyMatches(item.expectedTerms(), hit.key()))) expectedKeyHits++;
            }
            allHits += result.hits().size();
            wrongCropHits += (int) result.hits().stream().filter(hit -> !cropCode.equals(hit.cropCode())).count();
        }
        System.out.printf("knowledgeGolden structuredHitRate=%.4f keyHitRate=%.4f crossCropRate=%.4f cases=%d%n",
                ratio(structuredHits, expectedStructured), ratio(expectedKeyHits, expectedStructured),
                ratio(wrongCropHits, allHits), cases.size());
        assertEquals(expectedStructured, structuredHits, "structured hit rate must cover every structured golden case");
        assertTrue(ratio(expectedKeyHits, expectedStructured) >= .90D,
                "expected knowledge-key hit rate must remain at least 0.90");
        assertEquals(0, wrongCropHits, "structured lookup must not return another crop's rule");
    }

    private List<GoldenCase> load() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/knowledge/knowledge-golden-set-v1.json")) {
            if (input == null) throw new IOException("golden set missing");
            return mapper.readValue(input, new TypeReference<>() { });
        }
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1D : (double) numerator / denominator;
    }

    private boolean keyMatches(List<String> expectedTerms, String key) {
        String normalizedKey = normalize(key);
        return expectedTerms.stream().map(this::normalize)
                .anyMatch(term -> term.contains(normalizedKey) || normalizedKey.contains(term));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s_\\-—–/·（）()，,。:：;；]+", "");
    }

    private record GoldenCase(String id, String crop, String stage, String query, String expectedQueryType,
                              List<String> expectedTerms, String expectedConclusion, boolean expectedStructured) { }
}

package com.mei.zhgy.service.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.ZhgyApplication;
import com.mei.zhgy.service.agriculture.CropResolver;
import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in A/B evaluation for the existing 50-case golden set. It requires the
 * configured MySQL, Milvus, and Bailian embedding services; ordinary offline
 * test runs return immediately without starting the application.
 */
class QwenKnowledgeRrfGoldenEvaluationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CropResolver crops = new CropResolver();

    @Test
    void comparesWeightedAndRrfOnExistingKnowledgeGoldenSet() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("FARMAP_KNOWLEDGE_RRF_GOLDEN_EVAL")),
                "set FARMAP_KNOWLEDGE_RRF_GOLDEN_EVAL=true to run the live weighted/RRF comparison");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ZhgyApplication.class)
                .web(WebApplicationType.NONE).run()) {
            QwenKnowledgeVectorStore configured = context.getBean(QwenKnowledgeVectorStore.class);
            DataSource dataSource = context.getBeanProvider(DataSource.class).getIfAvailable();
            MilvusServiceClient milvus = context.getBeanProvider(MilvusServiceClient.class).getIfAvailable();
            KnowledgeEmbeddingProvider embedding = context.getBean(KnowledgeEmbeddingProvider.class);
            KnowledgeReranker reranker = context.getBean(KnowledgeReranker.class);
            Assumptions.assumeTrue(dataSource != null && milvus != null && configured.isConfigured(),
                    "Qwen production provider, MySQL, or Milvus is unavailable");

            Map<String, Object> health = configured.health();
            Assumptions.assumeTrue("READY".equals(health.get("status")),
                    "Qwen collection is unavailable: " + health.get("reason"));
            QwenKnowledgeVectorStore weighted = newStore(dataSource, milvus, embedding, reranker, health, "weighted");
            QwenKnowledgeVectorStore rrf = newStore(dataSource, milvus, embedding, reranker, health, "rrf");
            List<GoldenCase> golden = load();
            Evaluation weightedResult = evaluate("weighted", weighted, golden);
            Evaluation rrfResult = evaluate("rrf", rrf, golden);
            printComparison(weightedResult, rrfResult);

            assertTrue(rrfResult.recallAt5 >= weightedResult.recallAt5,
                    "RRF Recall@5 must not be lower than weighted");
            assertTrue(rrfResult.mrr >= weightedResult.mrr, "RRF MRR must not be lower than weighted");
            assertTrue(rrfResult.ndcgAt5 >= weightedResult.ndcgAt5, "RRF nDCG@5 must not be lower than weighted");
            assertTrue(rrfResult.crossCropRate == 0D && weightedResult.crossCropRate == 0D,
                    "cross-crop rate must remain zero");
        }
    }

    private QwenKnowledgeVectorStore newStore(DataSource dataSource, MilvusServiceClient milvus,
                                               KnowledgeEmbeddingProvider embedding, KnowledgeReranker reranker,
                                               Map<String, Object> health, String mode) {
        return new QwenKnowledgeVectorStore(dataSource, milvus, embedding, reranker,
                integer(health, "dimension", 1024), integer(health, "nlist", 128),
                integer(health, "candidateTopN", 20), integer(health, "finalTopK", 5),
                number(health, "minScore", 0.45D), mode, 60D, 1D, 1D, 30, 30, 8);
    }

    private Evaluation evaluate(String mode, QwenKnowledgeVectorStore store, List<GoldenCase> golden) {
        List<Long> latencies = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (GoldenCase item : golden) {
            long started = System.nanoTime();
            QwenKnowledgeVectorStore.SearchResult result = store.searchResult(item.query(), null, 5, false,
                    crops.resolve(item.crop()).code());
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            latencies.add(elapsed);
            rows.add(new Row(item.id(), result, item));
        }
        return Evaluation.from(mode, rows, latencies);
    }

    private void printComparison(Evaluation weighted, Evaluation rrf) {
        System.out.printf(Locale.ROOT,
                "knowledgeGoldenAB weighted Recall@5=%.4f MRR=%.4f nDCG@5=%.4f crossCrop=%.4f avgMs=%.1f p95Ms=%d%n",
                weighted.recallAt5, weighted.mrr, weighted.ndcgAt5, weighted.crossCropRate,
                weighted.averageMs, weighted.p95Ms);
        System.out.printf(Locale.ROOT,
                "knowledgeGoldenAB rrf      Recall@5=%.4f MRR=%.4f nDCG@5=%.4f crossCrop=%.4f avgMs=%.1f p95Ms=%d%n",
                rrf.recallAt5, rrf.mrr, rrf.ndcgAt5, rrf.crossCropRate, rrf.averageMs, rrf.p95Ms);
        for (int i = 0; i < weighted.rows.size(); i++) {
            Row left = weighted.rows.get(i), right = rrf.rows.get(i);
            System.out.printf("knowledgeGoldenAB %s weighted=%s rrf=%s%n", left.id,
                    left.topIds(), right.topIds());
        }
    }

    private List<GoldenCase> load() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/knowledge/knowledge-golden-set-v1.json")) {
            if (input == null) throw new IOException("knowledge golden set missing");
            return mapper.readValue(input, new TypeReference<>() { });
        }
    }

    private int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private double number(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record GoldenCase(String id, String crop, String stage, String query, String expectedQueryType,
                              List<String> expectedTerms, String expectedConclusion, boolean expectedStructured) { }

    private record Row(String id, QwenKnowledgeVectorStore.SearchResult result, GoldenCase golden) {
        List<String> topIds() { return result.getHits().stream().map(hit -> hit.chunkId).toList(); }

        int firstRelevantRank() {
            for (int i = 0; i < result.getHits().size(); i++) {
                if (containsExpected(result.getHits().get(i), golden.expectedTerms)) return i + 1;
            }
            return 0;
        }

        int wrongCropHits() {
            String expected = golden.crop == null ? "" : golden.crop;
            return (int) result.getHits().stream().filter(hit -> hasOppositeCrop(hit, expected)).count();
        }

        private boolean containsExpected(QwenKnowledgeVectorStore.KnowledgeVectorHit hit, List<String> terms) {
            String haystack = normalize(hit.content) + normalize(hit.classification) + normalize(hit.sourceName);
            return terms != null && terms.stream().map(this::normalize)
                    .anyMatch(term -> !term.isBlank() && haystack.contains(term));
        }

        private boolean hasOppositeCrop(QwenKnowledgeVectorStore.KnowledgeVectorHit hit, String expected) {
            String text = (hit.classification == null ? "" : hit.classification) + "\n"
                    + (hit.content == null ? "" : hit.content);
            String normalized = text.toLowerCase(Locale.ROOT).replace("_", "-");
            String wanted = expected.toLowerCase(Locale.ROOT);
            if (wanted.contains("脆李") || wanted.contains("plum")) return normalized.contains("crop:citrus") || normalized.contains("crop:rice");
            if (wanted.contains("柑橘") || wanted.contains("citrus") || wanted.contains("沃柑") || wanted.contains("默科特")) return normalized.contains("crop:plum") || normalized.contains("crop:rice");
            if (wanted.contains("稻") || wanted.contains("rice")) return normalized.contains("crop:plum") || normalized.contains("crop:citrus");
            return false;
        }

        private String normalize(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-—–/·（）()，,。:：;；]+", "");
        }
    }

    private record Evaluation(String mode, List<Row> rows, List<Long> latencies, double recallAt5,
                               double mrr, double ndcgAt5, double crossCropRate, double averageMs, long p95Ms) {
        static Evaluation from(String mode, List<Row> rows, List<Long> latencies) {
            int found = 0;
            double reciprocal = 0D;
            double ndcg = 0D;
            int wrong = 0;
            int returned = 0;
            for (Row row : rows) {
                int rank = row.firstRelevantRank();
                if (rank > 0) {
                    found++;
                    reciprocal += 1D / rank;
                    ndcg += 1D / (Math.log(rank + 1D) / Math.log(2D));
                }
                wrong += row.wrongCropHits();
                returned += row.result.getHits().size();
            }
            List<Long> ordered = latencies.stream().sorted(Comparator.naturalOrder()).toList();
            long p95 = ordered.isEmpty() ? 0L : ordered.get(Math.min(ordered.size() - 1, (int) Math.ceil(ordered.size() * .95D) - 1));
            double average = latencies.stream().mapToLong(Long::longValue).average().orElse(0D);
            return new Evaluation(mode, rows, latencies, ratio(found, rows.size()), ratio(reciprocal, rows.size()),
                    ratio(ndcg, rows.size()), ratio(wrong, returned), average, p95);
        }

        private static double ratio(double numerator, int denominator) {
            return denominator == 0 ? 1D : numerator / denominator;
        }
    }
}

package com.mei.zhgy.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only provenance graph contract. It is deliberately not a semantic ontology. */
public record KnowledgeGraphResponse(
        String status,
        Instant generatedAt,
        List<Node> nodes,
        List<Edge> edges,
        Summary summary,
        Coverage coverage,
        Collections collections,
        Pipeline pipeline,
        Retrieval retrieval) {

    public record Node(String id, String type, String label, String status, Map<String, Object> metadata) {}

    public record Edge(String id, String source, String target, String type) {}

    public record Summary(
            Long sourceCount,
            Long documentCount,
            Long versionCount,
            Long chunkCount,
            Long productionChunkCount,
            Long productionVectorCount,
            Long evidenceCount,
            Long diagnosisLinkedCount,
            Long expertReviewCount,
            Long historicalCandidateCount) {}

    public record Coverage(
            Long totalSourceFiles,
            Long readyDocuments,
            Long unsupportedFiles,
            Long corruptNoTextFiles,
            Long registeredFiles,
            String status,
            String note) {}

    public record Collections(VectorCollection productionKnowledge, VectorCollection historicalImage) {}

    public record VectorCollection(
            String collection,
            String status,
            Long rowCount,
            String embeddingModel,
            Integer dimension,
            String metric,
            String note) {}

    public record Pipeline(String status, List<Stage> stages) {}

    public record Stage(String key, String label, String status, String detail) {}

    public record Retrieval(
            String status,
            Integer candidateTopN,
            Integer finalTopK,
            String embeddingProvider,
            String embeddingModel,
            String rerankerModel,
            String rerankerStatus,
            String note) {}
}

package com.mei.zhgy.service.historical;

import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.LocalClipEmbeddingProvider;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ingests only confirmed expert cases; no synthetic B-07 data is ever created. */
@Service
public class HistoricalCaseIngestionService {
    public static final String COLLECTION = "farmap_image_vectors_new";
    private final LocalClipEmbeddingProvider clip;
    private final MilvusServiceClient milvus;
    private final Set<String> ingestedKeys = Collections.synchronizedSet(new HashSet<>());

    @Autowired
    public HistoricalCaseIngestionService(LocalClipEmbeddingProvider clip, @Autowired(required = false) MilvusServiceClient milvus) {
        this.clip = clip; this.milvus = milvus;
    }

    public IngestionReport ingest(List<Map<String, Object>> cases, boolean execute) {
        int candidates = cases == null ? 0 : cases.size(), ready = 0, inserted = 0;
        List<String> skipped = new ArrayList<>();
        if (cases == null) return new IngestionReport(candidates, ready, inserted, skipped, execute ? "EXECUTED" : "DRY_RUN");
        for (Map<String, Object> item : cases) {
            if (!confirmed(item)) { skipped.add(String.valueOf(item.get("caseId"))); continue; }
            String caseId = String.valueOf(item.get("caseId")); String image = String.valueOf(item.get("imageUrl"));
            String key = caseId + ":" + sha(image);
            if (ingestedKeys.contains(key)) { skipped.add(caseId + ":IDEMPOTENT"); continue; }
            EmbeddingProvider.EmbeddingResult embedding = clip.embedImage(image);
            if (embedding.getDimension() != 512 || embedding.getVector().size() != 512) { skipped.add(caseId + ":VECTOR_DIMENSION_MISMATCH"); continue; }
            ready++;
            if (execute && milvus != null) {
                try {
                    List<InsertParam.Field> fields = Arrays.asList(new InsertParam.Field("request_id", Collections.singletonList(caseId)), new InsertParam.Field("vector", Collections.singletonList(embedding.getVector())));
                    R<MutationResult> result = milvus.insert(InsertParam.newBuilder().withCollectionName(COLLECTION).withFields(fields).build());
                    if (result.getStatus() == R.Status.Success.getCode()) { inserted++; ingestedKeys.add(key); } else skipped.add(caseId + ":MILVUS");
                } catch (Exception error) { skipped.add(caseId + ":MILVUS"); }
            }
        }
        return new IngestionReport(candidates, ready, inserted, skipped, execute ? "EXECUTED" : "DRY_RUN");
    }

    private boolean confirmed(Map<String,Object> item) { return item != null && "CONFIRMED".equalsIgnoreCase(String.valueOf(item.get("expertStatus"))) && item.get("caseId") != null && item.get("imageUrl") != null && item.get("expertDiagnosis") != null && item.get("outcome") != null; }
    private String sha(String value) { try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte x:digest)b.append(String.format("%02x",x)); return b.toString(); } catch(Exception e){return value;} }
    public static class IngestionReport {
        public final int candidates, ready, inserted; public final List<String> skipped; public final String mode;
        public IngestionReport(int candidates,int ready,int inserted,List<String> skipped,String mode){this.candidates=candidates;this.ready=ready;this.inserted=inserted;this.skipped=skipped;this.mode=mode;}
        public Map<String,Object> asMap(){Map<String,Object> m=new LinkedHashMap<>();m.put("candidates",candidates);m.put("ready",ready);m.put("inserted",inserted);m.put("skipped",skipped);m.put("mode",mode);m.put("status","READY");return m;}
    }
}

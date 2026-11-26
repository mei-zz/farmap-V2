package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.WorkspaceAdminAccess;
import com.mei.zhgy.service.agent.AgentAccessException;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.diagnosis.DiagnosisRecord;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import com.mei.zhgy.service.historical.HistoricalCaseIngestionService;
import com.mei.zhgy.service.historical.HistoricalCaseSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/historical-cases")
public class HistoricalCaseController {
    private final HistoricalCaseIngestionService ingestion;
    private final HistoricalCaseSearchService search;
    private final HistoricalCaseCandidateService candidates;
    private final DiagnosisPersistenceService diagnoses;
    private final FarmFieldService fields;
    private final WorkspaceAdminAccess admin;
    public HistoricalCaseController(HistoricalCaseIngestionService ingestion, HistoricalCaseSearchService search,
                                    HistoricalCaseCandidateService candidates, DiagnosisPersistenceService diagnoses,
                                    FarmFieldService fields, WorkspaceAdminAccess admin) {
        this.ingestion = ingestion; this.search = search; this.candidates = candidates;
        this.diagnoses = diagnoses; this.fields = fields; this.admin = admin;
    }
    @PostMapping("/ingest")
    public Result<Map<String,Object>> ingest(@RequestBody(required = false) List<Map<String,Object>> cases, @RequestParam(defaultValue = "false") boolean execute) {
        admin.requireAdmin();
        return Result.success(ingestion.ingest(cases, execute).asMap());
    }

    @GetMapping("/library")
    public Result<Map<String, Object>> library(@RequestParam Integer farmId, @RequestParam(required = false) String fieldId) {
        requireUser();
        Long userId = BaseContext.getCurrentId();
        fields.requireFarm(farmId, userId);
        if (fieldId != null) {
            if (fieldId.isBlank() || !farmId.equals(fields.get(fieldId, userId).getFarmId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "地块与农场不匹配");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> candidate : candidates.list(userId, "ELIGIBLE")) {
            Object owner = candidate.get("ownerUserId");
            Object diagnosisId = candidate.get("diagnosisId");
            Object candidateId = candidate.get("candidateId");
            if (owner == null || !userId.toString().equals(owner.toString()) || diagnosisId == null || candidateId == null || candidate.get("reviewId") == null) continue;
            DiagnosisRecord record = diagnoses.findRecord(diagnosisId.toString(), userId).orElse(null);
            if (record == null || !userId.equals(record.getUserId()) || !farmId.equals(record.getFarmId())
                    || (fieldId != null && !fieldId.equals(record.getFieldId())) || !"COMPLETED".equalsIgnoreCase(record.getStatus())
                    || record.getSourceRunId() == null || record.getSourceRunId().isBlank() || record.getPayload() == null
                    || !diagnosisId.toString().equals(record.getDiagnosisId())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", candidateId.toString()); item.put("diagnosisId", record.getDiagnosisId());
            String title = record.getPayload().getDiagnosis() == null ? null : record.getPayload().getDiagnosis().getTitle();
            item.put("title", title == null || title.isBlank() ? "诊断案例" : title);
            if (record.getSummary() != null) item.put("summary", record.getSummary());
            if (record.getFieldId() != null) item.put("fieldId", record.getFieldId());
            if (candidate.get("updatedAt") != null) item.put("updatedAt", candidate.get("updatedAt"));
            item.put("status", candidate.get("status")); items.add(item);
        }
        return Result.success(Map.of("items", items));
    }

    @PostMapping("/search")
    public Result<Map<String, Object>> search(@RequestBody(required = false) SearchRequest request) {
        requireUser();
        SearchRequest safe = request == null ? new SearchRequest() : request;
        return Result.success(search.search(safe.getImageUrls(), safe.getTopK()).asMap());
    }
    private void requireUser() { if (BaseContext.getCurrentId() == null) throw AgentAccessException.unauthenticated(); }
    public static class SearchRequest {
        private List<String> imageUrls; private Integer topK;
        public List<String> getImageUrls() { return imageUrls; }
        public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }
    }
}

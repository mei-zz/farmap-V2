package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.agent.AgentAccessException;
import com.mei.zhgy.service.historical.HistoricalCaseIngestionService;
import com.mei.zhgy.service.historical.HistoricalCaseSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/historical-cases")
public class HistoricalCaseController {
    private final HistoricalCaseIngestionService ingestion;
    private final HistoricalCaseSearchService search;
    public HistoricalCaseController(HistoricalCaseIngestionService ingestion, HistoricalCaseSearchService search) {
        this.ingestion = ingestion;
        this.search = search;
    }
    @PostMapping("/ingest")
    public Result<Map<String,Object>> ingest(@RequestBody(required = false) List<Map<String,Object>> cases, @RequestParam(defaultValue = "false") boolean execute) {
        return Result.success(ingestion.ingest(cases, execute).asMap());
    }

    @PostMapping("/search")
    public Result<Map<String, Object>> search(@RequestBody(required = false) SearchRequest request) {
        requireUser();
        SearchRequest safe = request == null ? new SearchRequest() : request;
        return Result.success(search.search(safe.getImageUrls(), safe.getTopK()).asMap());
    }

    private void requireUser() {
        if (BaseContext.getCurrentId() == null) throw AgentAccessException.unauthenticated();
    }

    public static class SearchRequest {
        private List<String> imageUrls;
        private Integer topK;

        public List<String> getImageUrls() { return imageUrls; }
        public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }
    }
}

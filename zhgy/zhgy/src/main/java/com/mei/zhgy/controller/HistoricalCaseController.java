package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.historical.HistoricalCaseIngestionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/historical-cases")
public class HistoricalCaseController {
    private final HistoricalCaseIngestionService ingestion;
    public HistoricalCaseController(HistoricalCaseIngestionService ingestion) { this.ingestion = ingestion; }
    @PostMapping("/ingest")
    public Result<Map<String,Object>> ingest(@RequestBody(required = false) List<Map<String,Object>> cases, @RequestParam(defaultValue = "false") boolean execute) {
        return Result.success(ingestion.ingest(cases, execute).asMap());
    }
}

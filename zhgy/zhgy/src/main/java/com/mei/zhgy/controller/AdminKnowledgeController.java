package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.knowledge.KnowledgeIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit admin/developer boundary for curated knowledge ingestion. */
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {
    private final KnowledgeIngestionService ingestion;
    public AdminKnowledgeController(KnowledgeIngestionService ingestion) { this.ingestion = ingestion; }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public Result<Map<String, Object>> importDocument(@RequestParam String sourceName,
                                                       @RequestParam(required = false) String title,
                                                       @RequestPart("file") MultipartFile file) throws Exception {
        if (file == null) throw KnowledgeBackendException.validation("file 不能为空");
        return Result.success(ingestion.importDocument(sourceName, title, file.getOriginalFilename(), file.getBytes(), actor()));
    }

    @PostMapping(value = "/import-corpus", consumes = "multipart/form-data")
    public Result<Map<String, Object>> importCorpusDocument(@RequestParam String sourceName,
                                                             @RequestParam String relativePath,
                                                             @RequestParam(required = false) String title,
                                                             @RequestPart("file") MultipartFile file) throws Exception {
        if (file == null) throw KnowledgeBackendException.validation("file 不能为空");
        return Result.success(ingestion.importCorpusDocument(sourceName, title, relativePath, file.getBytes(), actor()));
    }

    @GetMapping("/ingestions/{ingestionId}")
    public Result<Map<String, Object>> get(@org.springframework.web.bind.annotation.PathVariable String ingestionId) {
        return Result.success(ingestion.getIngestion(ingestionId));
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() { return Result.success(ingestion.health()); }

    private Long actor() { Long value = BaseContext.getCurrentId(); if (value == null) throw KnowledgeBackendException.forbidden(null); return value; }
    @ExceptionHandler(KnowledgeBackendException.class)
    public ResponseEntity<Map<String, Object>> error(KnowledgeBackendException error) { Map<String, Object> body = new LinkedHashMap<>(); body.put("code", error.getCode()); body.put("message", error.getMessage()); body.put("timestamp", Instant.now().toString()); return ResponseEntity.status(status(error.getCode())).body(body); }
    private HttpStatus status(String code) { if ("FORBIDDEN".equals(code)) return HttpStatus.FORBIDDEN; if ("VALIDATION_FAILED".equals(code)) return HttpStatus.UNPROCESSABLE_ENTITY; if ("KNOWLEDGE_PERSISTENCE_UNAVAILABLE".equals(code)) return HttpStatus.SERVICE_UNAVAILABLE; return HttpStatus.CONFLICT; }
}

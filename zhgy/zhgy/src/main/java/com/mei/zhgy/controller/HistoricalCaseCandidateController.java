package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.expert.ExpertReviewWriteException;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read and idempotent evaluate boundary for historical case candidates. */
@RestController
@RequestMapping("/api/historical-case-candidates")
public class HistoricalCaseCandidateController {
    private final HistoricalCaseCandidateService candidates;
    public HistoricalCaseCandidateController(HistoricalCaseCandidateService candidates) { this.candidates = candidates; }

    @GetMapping("/{candidateId}")
    public Result<Map<String, Object>> get(@PathVariable String candidateId) { return Result.success(candidates.get(candidateId, actor())); }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String status) { return Result.success(candidates.list(actor(), status)); }

    @GetMapping("/by-review/{reviewId}")
    public Result<Map<String, Object>> byReview(@PathVariable String reviewId) { return Result.success(candidates.findByReview(reviewId, actor())); }

    /** Body form is convenient for integrations; eligibility is still server computed. */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> input) {
        Object reviewId = input == null ? null : input.get("reviewId");
        if (reviewId == null || String.valueOf(reviewId).isBlank()) throw ExpertReviewWriteException.validation("reviewId 不能为空");
        return Result.success(candidates.evaluateForCandidate(String.valueOf(reviewId), actor()));
    }

    private Long actor() { Long value = BaseContext.getCurrentId(); if (value == null) throw ExpertReviewWriteException.forbidden(null); return value; }

    @ExceptionHandler(ExpertReviewWriteException.class)
    public ResponseEntity<Map<String, Object>> error(ExpertReviewWriteException exception) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("code", exception.getCode()); body.put("message", exception.getMessage()); if (exception.getReviewId() != null) body.put("reviewId", exception.getReviewId()); if (exception.getCandidateId() != null) body.put("candidateId", exception.getCandidateId()); body.put("timestamp", Instant.now().toString());
        HttpStatus status = "CANDIDATE_NOT_FOUND".equals(exception.getCode()) || "REVIEW_NOT_FOUND".equals(exception.getCode()) ? HttpStatus.NOT_FOUND : "FORBIDDEN".equals(exception.getCode()) ? HttpStatus.FORBIDDEN : "VALIDATION_FAILED".equals(exception.getCode()) ? HttpStatus.UNPROCESSABLE_ENTITY : "EXPERT_REVIEW_PERSISTENCE_UNAVAILABLE".equals(exception.getCode()) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(body);
    }
}

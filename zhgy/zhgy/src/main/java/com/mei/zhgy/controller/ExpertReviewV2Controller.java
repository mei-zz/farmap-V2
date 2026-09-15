package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.expert.ExpertReviewV2Service;
import com.mei.zhgy.service.expert.ExpertReviewWriteException;
import com.mei.zhgy.service.historical.HistoricalCaseCandidateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structured Expert Review V2 and candidate command boundary. */
@RestController
@RequestMapping("/api/expert-reviews")
public class ExpertReviewV2Controller {
    private final ExpertReviewV2Service reviews;
    private final HistoricalCaseCandidateService candidates;

    public ExpertReviewV2Controller(ExpertReviewV2Service reviews, HistoricalCaseCandidateService candidates) {
        this.reviews = reviews; this.candidates = candidates;
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> input) {
        return Result.success(reviews.create(input, actor()));
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String diagnosisId) {
        return Result.success(reviews.list(actor(), diagnosisId));
    }

    @GetMapping("/{reviewId}")
    public Result<Map<String, Object>> get(@PathVariable String reviewId) {
        return Result.success(reviews.get(reviewId, actor()));
    }

    @PutMapping("/{reviewId}")
    public Result<Map<String, Object>> replace(@PathVariable String reviewId, @RequestBody Map<String, Object> input) {
        return Result.success(reviews.update(reviewId, input, actor()));
    }

    @PatchMapping("/{reviewId}")
    public Result<Map<String, Object>> update(@PathVariable String reviewId, @RequestBody Map<String, Object> input) {
        return Result.success(reviews.update(reviewId, input, actor()));
    }

    @PostMapping("/{reviewId}/candidate")
    public Result<Map<String, Object>> candidate(@PathVariable String reviewId) {
        return Result.success(candidates.evaluateForCandidate(reviewId, actor()));
    }

    private Long actor() {
        Long value = BaseContext.getCurrentId();
        if (value == null) throw ExpertReviewWriteException.forbidden(null);
        return value;
    }

    @ExceptionHandler(ExpertReviewWriteException.class)
    public ResponseEntity<Map<String, Object>> error(ExpertReviewWriteException exception) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("code", exception.getCode()); body.put("message", exception.getMessage()); if (exception.getReviewId() != null) body.put("reviewId", exception.getReviewId()); if (exception.getCandidateId() != null) body.put("candidateId", exception.getCandidateId()); body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status(exception.getCode())).body(body);
    }

    private HttpStatus status(String code) {
        if ("REVIEW_NOT_FOUND".equals(code) || "CANDIDATE_NOT_FOUND".equals(code)) return HttpStatus.NOT_FOUND;
        if ("FORBIDDEN".equals(code)) return HttpStatus.FORBIDDEN;
        if ("VALIDATION_FAILED".equals(code)) return HttpStatus.UNPROCESSABLE_ENTITY;
        if ("EXPERT_REVIEW_PERSISTENCE_UNAVAILABLE".equals(code)) return HttpStatus.SERVICE_UNAVAILABLE;
        return HttpStatus.CONFLICT;
    }
}

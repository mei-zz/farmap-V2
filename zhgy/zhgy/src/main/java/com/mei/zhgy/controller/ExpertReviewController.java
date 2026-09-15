package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.expert.ExpertReviewService;
import com.mei.zhgy.service.expert.ExpertReviewWriteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/expert-review")
public class ExpertReviewController {
    private final ExpertReviewService reviews;
    public ExpertReviewController(ExpertReviewService reviews) { this.reviews = reviews; }
    @GetMapping("/{diagnosisId}") public Result<Map<String,Object>> get(@PathVariable String diagnosisId) { Map<String,Object> value=reviews.get(diagnosisId); return Result.success(value == null ? new LinkedHashMap<>() : value); }
    @GetMapping public Result<?> list() { return Result.success(reviews.list()); }
    @PostMapping("/{diagnosisId}") public Result<Map<String,Object>> save(@PathVariable String diagnosisId, @RequestBody Map<String,Object> input) { return Result.success(reviews.save(diagnosisId, input, "current-user")); }

    @ExceptionHandler(ExpertReviewWriteException.class)
    public ResponseEntity<Map<String,Object>> error(ExpertReviewWriteException exception) {
        Map<String,Object> body = new LinkedHashMap<>(); body.put("code", exception.getCode()); body.put("message", exception.getMessage());
        return ResponseEntity.status("EXPERT_REVIEW_PERSISTENCE_UNAVAILABLE".equals(exception.getCode()) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT).body(body);
    }
}

package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.expert.ExpertReviewService;
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
}

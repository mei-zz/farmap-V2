package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.maturity.FarmMaturityService;
import com.mei.zhgy.service.maturity.CameraBindingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class MaturityAnalysisController {
    private final FarmMaturityService service;
    private final CameraBindingService bindings;
    public MaturityAnalysisController(FarmMaturityService service, CameraBindingService bindings) { this.service = service; this.bindings = bindings; }
    private Long actor() { Long id = BaseContext.getCurrentId(); if (id == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required"); return id; }
    @GetMapping("/api/maturity/farms/{farmId}/runs")
    public Result<List<Map<String,Object>>> runs(@PathVariable Integer farmId) { return Result.success(service.runs(farmId, actor())); }
    @GetMapping("/api/maturity/farms/{farmId}/latest")
    public Result<Map<String,Object>> latest(@PathVariable Integer farmId) { return Result.success(service.latest(farmId, actor())); }
    @GetMapping("/api/maturity/runs/{runId}")
    public Result<Map<String,Object>> run(@PathVariable String runId) { return Result.success(service.run(runId, actor())); }
    public record BindingRequest(String cameraId, String fieldId, String areaId, String areaName) {}
    @GetMapping("/api/farms/{farmId}/camera-bindings")
    public Result<List<Map<String,Object>>> bindings(@PathVariable Integer farmId) { return Result.success(bindings.list(farmId, actor())); }
    @PutMapping("/api/farms/{farmId}/camera-bindings")
    public Result<Map<String,Object>> bind(@PathVariable Integer farmId, @RequestBody BindingRequest body) { return Result.success(bindings.bind(farmId, actor(), body.cameraId(), body.fieldId(), body.areaId(), body.areaName())); }
    @ExceptionHandler(FieldAccessException.class)
    public ResponseEntity<Result<Object>> handle(FieldAccessException e) { return ResponseEntity.status(e.getHttpStatus()).body(Result.error(e.getCode() + ": " + e.getMessage())); }
}

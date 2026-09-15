package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldContext;
import com.mei.zhgy.service.agriculture.FieldFacts;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
public class FarmFieldController {
    private final FarmFieldService service;
    private final FieldFactsService factsService;
    @org.springframework.beans.factory.annotation.Autowired
    public FarmFieldController(FarmFieldService service, @org.springframework.beans.factory.annotation.Autowired(required = false) FieldFactsService factsService) { this.service = service; this.factsService = factsService; }

    @GetMapping("/api/farms/{farmId}/fields")
    public ResponseEntity<Result<List<FarmField>>> list(@PathVariable Integer farmId) { return ok(() -> Result.success(service.list(farmId, actor()))); }

    @PostMapping("/api/farms/{farmId}/fields")
    public ResponseEntity<Result<FarmField>> create(@PathVariable Integer farmId, @RequestBody CreateFieldRequest request) {
        return ok(() -> Result.success(service.create(farmId, actor(), new FarmFieldService.CreateFieldCommand(request.name, request.crop, request.area, request.growthStage, request.latitude, request.longitude))));
    }

    @GetMapping("/api/fields/{fieldId}")
    public ResponseEntity<Result<FarmField>> get(@PathVariable String fieldId) { return ok(() -> Result.success(service.get(fieldId, actor()))); }

    @GetMapping("/api/fields/{fieldId}/context")
    public ResponseEntity<Result<FieldContext>> context(@PathVariable String fieldId) { return ok(() -> Result.success(service.context(fieldId, actor()))); }

    @GetMapping("/api/fields/{fieldId}/facts")
    public ResponseEntity<Result<FieldFacts>> facts(@PathVariable String fieldId) {
        return ok(() -> {
            if (factsService == null) throw new FieldAccessException(503, "FACTS_UNAVAILABLE", "field facts service unavailable");
            return Result.success(factsService.forField(fieldId, actor()));
        });
    }

    private Long actor() { Long value = BaseContext.getCurrentId(); if (value == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required"); return value; }
    private <T> ResponseEntity<Result<T>> ok(java.util.function.Supplier<Result<T>> operation) { try { return ResponseEntity.ok(operation.get()); } catch (FieldAccessException error) { return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage())); } }

    @Data
    public static class CreateFieldRequest { private String name; private String crop; private BigDecimal area; private String growthStage; private Double latitude; private Double longitude; }
}

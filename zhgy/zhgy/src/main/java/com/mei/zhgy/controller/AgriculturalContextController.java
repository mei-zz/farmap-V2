package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.AgriculturalContextService;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.vo.AgriculturalContextResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * REAL, owner-scoped read contracts for existing agricultural source data.
 * Legacy farmType fallbacks are deliberately not used on these routes.
 */
@RestController
@RequestMapping("/api/farms/{farmId}")
public class AgriculturalContextController {
    private final AgriculturalContextService service;

    public AgriculturalContextController(AgriculturalContextService service) {
        this.service = service;
    }

    @GetMapping("/agricultural-context")
    public ResponseEntity<Result<AgriculturalContextResponse>> context(
            @PathVariable Integer farmId,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer temperatureLimit,
            @RequestParam(required = false) Integer diagnosisLimit,
            @RequestParam(required = false) String fieldId) {
        return execute(() -> service.get(farmId, actor(), month, temperatureLimit, diagnosisLimit, fieldId));
    }

    @GetMapping("/seasonal-context")
    public ResponseEntity<Result<AgriculturalContextResponse.SeasonalAgriculturalContext> > seasonal(
            @PathVariable Integer farmId,
            @RequestParam(required = false) Integer month) {
        return execute(() -> service.seasonal(farmId, actor(), month));
    }

    @GetMapping("/historical-temperature")
    public ResponseEntity<Result<AgriculturalContextResponse.HistoricalTemperatureSeries>> historicalTemperature(
            @PathVariable Integer farmId,
            @RequestParam(required = false) Integer limit) {
        return execute(() -> service.historicalTemperature(farmId, actor(), limit));
    }

    @GetMapping("/guidance-context")
    public ResponseEntity<Result<AgriculturalContextResponse.FarmGuidanceContext>> guidance(
            @PathVariable Integer farmId,
            @RequestParam(required = false) Integer month) {
        return execute(() -> service.guidance(farmId, actor(), month));
    }

    @GetMapping("/crop-observation-summary")
    public ResponseEntity<Result<AgriculturalContextResponse.CropObservationSummary>> crops(
            @PathVariable Integer farmId) {
        return execute(() -> service.cropObservations(farmId, actor()));
    }

    @GetMapping("/diagnoses")
    public ResponseEntity<Result<AgriculturalContextResponse.DiagnosisListContext>> diagnoses(
            @PathVariable Integer farmId,
            @RequestParam(required = false) String fieldId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return execute(() -> service.diagnoses(farmId, actor(), fieldId, status, limit));
    }

    private Long actor() {
        Long actorId = BaseContext.getCurrentId();
        if (actorId == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required");
        return actorId;
    }

    private <T> ResponseEntity<Result<T>> execute(Supplier<T> operation) {
        try {
            return ResponseEntity.ok(Result.success(operation.get()));
        } catch (FieldAccessException error) {
            return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage()));
        }
    }
}

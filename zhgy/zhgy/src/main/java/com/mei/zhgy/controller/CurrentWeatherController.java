package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.CurrentWeatherService;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.vo.CurrentWeatherResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/** Owner-scoped live weather route backed by the existing provider. */
@RestController
@RequestMapping("/api/farms/{farmId}")
public class CurrentWeatherController {
    private final CurrentWeatherService service;

    public CurrentWeatherController(CurrentWeatherService service) {
        this.service = service;
    }

    @GetMapping("/current-weather")
    public ResponseEntity<Result<CurrentWeatherResponse>> current(@PathVariable Integer farmId) {
        return execute(() -> service.get(farmId, actor()));
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

package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.map.FarmMapData;
import com.mei.zhgy.service.map.FarmMapDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FarmMapDataController {
    private final FarmMapDataService service;

    public FarmMapDataController(FarmMapDataService service) {
        this.service = service;
    }

    @GetMapping("/api/farms/{farmId}/map-data")
    public ResponseEntity<Result<FarmMapData>> list(@PathVariable Integer farmId) {
        try {
            Long actorId = BaseContext.getCurrentId();
            if (actorId == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required");
            return ResponseEntity.ok(Result.success(service.list(farmId, actorId)));
        } catch (FieldAccessException error) {
            return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage()));
        }
    }
}

package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.camera.FarmCamera;
import com.mei.zhgy.service.camera.FarmCameraService;
import com.mei.zhgy.service.field.FieldAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class FarmCameraController {
    private final FarmCameraService service;

    public FarmCameraController(FarmCameraService service) { this.service = service; }

    @GetMapping("/api/farms/{farmId}/cameras")
    public ResponseEntity<Result<List<FarmCamera>>> list(@PathVariable Integer farmId) {
        try {
            Long actorId = BaseContext.getCurrentId();
            if (actorId == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required");
            return ResponseEntity.ok(Result.success(service.list(farmId, actorId)));
        } catch (FieldAccessException error) {
            return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage()));
        }
    }

    @GetMapping("/api/cameras/{cameraId}")
    public ResponseEntity<Result<FarmCamera>> get(@PathVariable String cameraId) {
        try {
            Long actorId = BaseContext.getCurrentId();
            if (actorId == null) throw new FieldAccessException(401, "UNAUTHENTICATED", "authentication required");
            return ResponseEntity.ok(Result.success(service.get(cameraId, actorId)));
        } catch (FieldAccessException error) {
            return ResponseEntity.status(error.getHttpStatus()).body(Result.error(error.getCode() + ": " + error.getMessage()));
        }
    }
}

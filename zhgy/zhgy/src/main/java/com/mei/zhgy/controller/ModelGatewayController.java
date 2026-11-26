package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.WorkspaceAdminAccess;
import com.mei.zhgy.service.ai.BailianModelClient;
import com.mei.zhgy.service.ai.ModelAvailabilityRegistry;
import com.mei.zhgy.service.ai.ModelAvailabilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 模型网关诊断接口；只返回能力与状态元数据，不返回 API Key 或模型正文。 */
@Slf4j
@RestController
@RequestMapping("/ai-model")
public class ModelGatewayController {
    private final ModelAvailabilityRegistry availabilityRegistry;
    private final BailianModelClient bailianModelClient;
    private final ModelAvailabilityService modelAvailabilityService;
    private final WorkspaceAdminAccess workspaceAdminAccess;

    public ModelGatewayController(ModelAvailabilityRegistry availabilityRegistry,
                                  BailianModelClient bailianModelClient,
                                  ModelAvailabilityService modelAvailabilityService,
                                  WorkspaceAdminAccess workspaceAdminAccess) {
        this.availabilityRegistry = availabilityRegistry;
        this.bailianModelClient = bailianModelClient;
        this.modelAvailabilityService = modelAvailabilityService;
        this.workspaceAdminAccess = workspaceAdminAccess;
    }

    @GetMapping("/availability")
    public Result<Map<String, Object>> availability() {
        workspaceAdminAccess.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        if (bailianModelClient.isOpencodeEnabled()) {
            String model = bailianModelClient.getOpencodeModel();
            result.put("provider", "opencode");
            result.put("configured", bailianModelClient.isChatConfigured());
            result.put("models", List.of(Map.of("model", model, "role", "DEFAULT_MODEL",
                    "status", availabilityRegistry.status(model))));
            return Result.success(result);
        }
        result.put("provider", "alibaba-bailian");
        result.put("configured", bailianModelClient.isConfigured());
        result.put("region", bailianModelClient.getRegion());
        result.put("workspaceId", bailianModelClient.getWorkspaceId());
        result.put("models", availabilityRegistry.snapshot());
        return Result.success(result);
    }

    @PostMapping("/availability/probe")
    public Result<List<Map<String, Object>>> probe() {
        workspaceAdminAccess.requireAdmin();
        List<Map<String, Object>> results = modelAvailabilityService.probeAll();
        log.info("Model availability probe completed for {} models", results.size());
        return Result.success(results);
    }
}

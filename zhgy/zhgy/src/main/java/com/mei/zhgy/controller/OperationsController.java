package com.mei.zhgy.controller;

import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.operations.OperationsTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsTaskService tasks;
    public OperationsController(OperationsTaskService tasks) { this.tasks = tasks; }

    @GetMapping("/tasks")
    public Result<Map<String, Object>> list() {
        List<Map<String, Object>> items = tasks.listTasks();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        return Result.success(body);
    }
}

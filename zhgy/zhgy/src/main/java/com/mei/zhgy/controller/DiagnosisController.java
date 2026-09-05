package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.rag.DiagnosisRagService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 结构化多模态诊断入口；旧 /ai-model 接口保持不变。 */
@RestController
@RequestMapping("/diagnosis")
@Slf4j
public class DiagnosisController {
    private final DiagnosisRagService diagnosisRagService;

    @Autowired
    public DiagnosisController(DiagnosisRagService diagnosisRagService) {
        this.diagnosisRagService = diagnosisRagService;
    }

    @PostMapping("/analyze")
    public Result<DiagnosisAnalysisResponse> analyze(@RequestBody DiagnosisAnalyzeRequest request) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("用户未登录");
        }
        try {
            return Result.success(diagnosisRagService.analyze(request, userId));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn("多模态诊断不可用: {}", exception.getMessage());
            return Result.error("实时分析暂不可用: " + exception.getMessage());
        } catch (Exception exception) {
            log.error("多模态诊断失败", exception);
            return Result.error("实时分析暂不可用");
        }
    }
}


package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceException;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reads a persisted Diagnosis snapshot without re-running multimodal RAG. */
@RestController
@RequestMapping({"/api/diagnoses", "/diagnoses"})
public class DiagnosisQueryController {
    private final DiagnosisPersistenceService diagnosisPersistenceService;

    @Autowired
    public DiagnosisQueryController(DiagnosisPersistenceService diagnosisPersistenceService) {
        this.diagnosisPersistenceService = diagnosisPersistenceService;
    }

    @GetMapping("/{diagnosisId}")
    public ResponseEntity<Result<DiagnosisAnalysisResponse>> get(@PathVariable String diagnosisId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error("用户未登录"));
        }
        try {
            return diagnosisPersistenceService.find(diagnosisId, userId)
                    .map(payload -> ResponseEntity.ok(Result.success(payload)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error("诊断不存在")));
        } catch (DiagnosisPersistenceException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error("诊断查询暂不可用"));
        }
    }
}

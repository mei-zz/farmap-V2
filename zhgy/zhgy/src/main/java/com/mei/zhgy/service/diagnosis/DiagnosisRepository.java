package com.mei.zhgy.service.diagnosis;

import java.util.Optional;

public interface DiagnosisRepository {
    DiagnosisRecord save(DiagnosisRecord record);
    Optional<DiagnosisRecord> find(String diagnosisId, Long userId);
}

package com.mei.zhgy.service;

import com.mei.zhgy.entity.CropDiagnosis;

public interface CropDiagnosisService {
    
    /**
     * 保存作物诊断结果
     * @param cropDiagnosis 作物诊断实体
     * @return 是否保存成功
     */
    boolean saveCropDiagnosis(CropDiagnosis cropDiagnosis);
}
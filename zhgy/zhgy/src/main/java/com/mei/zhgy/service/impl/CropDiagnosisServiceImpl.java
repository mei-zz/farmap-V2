package com.mei.zhgy.service.impl;

import com.mei.zhgy.entity.CropDiagnosis;
import com.mei.zhgy.mapper.CropDiagnosisMapper;
import com.mei.zhgy.service.CropDiagnosisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CropDiagnosisServiceImpl implements CropDiagnosisService {
    
    @Autowired
    private CropDiagnosisMapper cropDiagnosisMapper;
    
    @Override
    public boolean saveCropDiagnosis(CropDiagnosis cropDiagnosis) {
        try {
            int result = cropDiagnosisMapper.insert(cropDiagnosis);
            return result > 0;
        } catch (Exception e) {
            log.error("保存作物诊断结果失败", e);
            return false;
        }
    }
}
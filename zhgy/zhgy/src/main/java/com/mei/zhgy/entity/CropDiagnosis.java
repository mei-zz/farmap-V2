package com.mei.zhgy.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CropDiagnosis implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer id;
    private String imgUrl;
    private LocalDateTime diagnosisTime;
    private String species;
    private String speciesConfidence;
    private String imageIntegrity;
    private String imageClarity;
    private String lightingConditions;
    private String shootingSuggestions;
    private String growthStage;
    private String treeStructure;
    private String branchGrowth;
    private String overallTreeVigor;
    private String leafColor;
    private String leafArea;
    private String leafSpotRatio;
    private String leafStatusSummary;
    private String fruitLoad;
    private String fruitSize;
    private String fruitColor;
    private String abnormalFruitRatio;
    private String nitrogenStatus;
    private String phosphorusStatus;
    private String potassiumStatus;
    private String micronutrients;
    private String suspectedDisease;
    private String spotDescription;
    private String pestSigns;
    private String diseaseSeverity;
    private String estimatedLeaves;
    private String estimatedFruits;
    private String leafFruitRatio;
    private String isReasonable;
    private String fertilizerSuggestion;
    private String diseaseTreatment;
    private String vigorImprovement;
    private String supplementaryNotes;
}
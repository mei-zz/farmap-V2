package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.CropDiagnosis;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface CropDiagnosisMapper {
    
    /**
     * 插入作物诊断记录
     * @param cropDiagnosis 作物诊断实体
     * @return 插入记录数
     */
    @Insert({
        "<script>",
        "INSERT INTO crop_diagnosis (",
        "img_url, diagnosis_time, species, species_confidence, image_integrity, ",
        "image_clarity, lighting_conditions, shooting_suggestions, growth_stage, ",
        "tree_structure, branch_growth, overall_tree_vigor, leaf_color, leaf_area, ",
        "leaf_spot_ratio, leaf_status_summary, fruit_load, fruit_size, fruit_color, ",
        "abnormal_fruit_ratio, nitrogen_status, phosphorus_status, potassium_status, ",
        "micronutrients, suspected_disease, spot_description, pest_signs, disease_severity, ",
        "estimated_leaves, estimated_fruits, leaf_fruit_ratio, is_reasonable, ",
        "fertilizer_suggestion, disease_treatment, vigor_improvement, supplementary_notes",
        ") VALUES (",
        "#{imgUrl}, #{diagnosisTime}, #{species}, #{speciesConfidence}, #{imageIntegrity}, ",
        "#{imageClarity}, #{lightingConditions}, #{shootingSuggestions}, #{growthStage}, ",
        "#{treeStructure}, #{branchGrowth}, #{overallTreeVigor}, #{leafColor}, #{leafArea}, ",
        "#{leafSpotRatio}, #{leafStatusSummary}, #{fruitLoad}, #{fruitSize}, #{fruitColor}, ",
        "#{abnormalFruitRatio}, #{nitrogenStatus}, #{phosphorusStatus}, #{potassiumStatus}, ",
        "#{micronutrients}, #{suspectedDisease}, #{spotDescription}, #{pestSigns}, #{diseaseSeverity}, ",
        "#{estimatedLeaves}, #{estimatedFruits}, #{leafFruitRatio}, #{isReasonable}, ",
        "#{fertilizerSuggestion}, #{diseaseTreatment}, #{vigorImprovement}, #{supplementaryNotes}",
        ")",
        "</script>"
    })
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CropDiagnosis cropDiagnosis);
}
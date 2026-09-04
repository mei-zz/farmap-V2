export interface PlantValidation {
  consistent: boolean;
  message: string;
  details: string;
  confidence: number;
}

export interface TreeId {
  种类: string;
  置信度: string;
}

export interface ImageQuality {
  图像完整性: string;
  图像清晰度: string;
  光照条件: string;
  拍摄建议: string;
}

export interface GrowthDiagnosis {
  冠层结构: string;
  枝条形态: string;
  新稍生长: string;
  花芽生长: string;
  长势综合判断: string;
}

export interface LeafDiagnosis {
  叶色: string;
  叶面积大小: string;
  叶面病斑比例: string;
  叶片状态总结: string;
}

export interface FruitDiagnosis {
  挂果量: string;
  果实大小: string;
  果实色泽: string;
  异常果实比例: string;
}

export interface NutritionDiagnosis {
  氮素状态: string;
  磷素状态: string;
  钾素状态: string;
  中微量元素: string;
}

export interface PestDiagnosis {
  疑似病害: string;
  病斑描述: string;
  虫害迹象: string;
  病害严重度: string;
}

export interface RatioAssessment {
  可见叶片数估计: string;
  可见果实数估计: string;
  果叶比估计: string;
  是否合理: string;
}

export interface Recommendations {
  施肥建议: string;
  病害处理建议: string;
  树势提升建议: string;
  补充说明: string;
}

export interface AnalysisResult {
  树种识别: TreeId;
  图像质量诊断: ImageQuality;
  当前生长阶段: string;
  长势诊断: GrowthDiagnosis;
  叶部状态诊断: LeafDiagnosis;
  果实状态诊断: FruitDiagnosis;
  营养状况诊断: NutritionDiagnosis;
  病虫害诊断: PestDiagnosis;
  果叶比与树体评估: RatioAssessment;
  综合建议: Recommendations;
  回答置信度: string;
}

export interface Validation {
  plant_type: string;
  quality: string;
  count: string;
}

export interface PlantAnalysisData {
  plant_validation: PlantValidation;
  analysis_result: AnalysisResult;
  validation: Validation;
}

interface PendingCase {
  requestId: string;
  uploadTime: string;
  imageCount: number;
  generateTime: string;
  revisionCount: number;
}
interface CasesStore {
  total: number;
  list: PendingCase[];
  pageNum: number;
  pageSize: number;
  size: number;
  startRow: number;
  endRow: number;
  pages: number;
  prePage: number;
  nextPage: number;
  isFirstPage: boolean;
  isLastPage: boolean;
  hasPreviousPage: boolean;
  hasNextPage: boolean;
  navigatePages: number;
  navigatepageNums: number[];
  navigateFirstPage: number;
  navigateLastPage: number;
}
interface CasesStoreResult {
  code: number;
  data: CasesStore;
  msg: null;
}

interface CaseContent {
  userRequestInfo: {
    requestId: string;
    imageUrls: string;
    uploadTime: string;
    status: 1;
  };
  initialResultInfo: {
    jsonData: string;
    modelVersion: string;
  };
  revisionRecords: [];
  onClear: () => void;
}
interface CaseContentResult {
  code: number;
  data: CaseContent;
  msg: null;
}

export interface PlantValidation {
  consistent: boolean;
  message: string;
  details: string;
  confidence?: number;
}

export interface TreeIdentification {
  种类: string;
  置信度: string;
}

export interface ImageQualityDiagnosis {
  图像完整性: string;
  图像清晰度: string;
  光照条件: string;
  拍摄建议: string;
}

export interface GrowthPotentialDiagnosis {
  冠层结构: string;
  枝条形态: string;
  新稍生长: string;
  花芽生长: string;
  长势综合判断: string;
}

export interface LeafStatusDiagnosis {
  叶色: string;
  叶面积大小: string;
  叶面病斑比例: string;
  叶片状态总结: string;
}

export interface FruitStatusDiagnosis {
  挂果量: string;
  果实大小: string;
  果实色泽: string;
  异常果实比例: string;
}

export interface NutritionStatusDiagnosis {
  氮素状态: string;
  磷素状态: string;
  钾素状态: string;
  中微量元素: string;
}

export interface DiseasePestDiagnosis {
  疑似病害: string;
  病斑描述: string;
  虫害迹象: string;
  病害严重度: string;
}

export interface FruitLeafRatioAssessment {
  可见叶片数估计: string;
  可见果实数估计: string;
  果叶比估计: string;
  是否合理: string;
}

export interface ComprehensiveAdvice {
  施肥建议: string;
  病害处理建议: string;
  树势提升建议: string;
  补充说明: string;
}

export interface AnalysisResult {
  element: "input" | "view";
  树种识别: TreeIdentification;
  图像质量诊断: ImageQualityDiagnosis;
  当前生长阶段: string;
  长势诊断: GrowthPotentialDiagnosis;
  叶部状态诊断: LeafStatusDiagnosis;
  果实状态诊断: FruitStatusDiagnosis;
  营养状况诊断: NutritionStatusDiagnosis;
  病虫害诊断: DiseasePestDiagnosis;
  果叶比与树体评估: FruitLeafRatioAssessment;
  综合建议: ComprehensiveAdvice;
  回答置信度: string;
}

export interface ValidationSummary {
  plant_type: string;
  quality: string;
  count: string;
}

export interface PlantData {
  plant_validation: PlantValidation;
  // Optional because in the error case (consistent=false), these fields might be missing
  analysis_result?: AnalysisResult;
  validation?: ValidationSummary;
}

export interface PlantAnalysisEditorProps {
  jsonData: string;
  unSubmittable?: boolean;
  element: "input" | "view";
  submitLoading: boolean;
  submitResult: string;
  onSubmit: (newJsonString: string) => void;
  onParseEnd: (status: boolean) => void;
}

import type { DemoEvidence } from "./types";
import cameraLeafAsset from "@/assets/demo/diagnosis-camera-leaf.png";
import cameraHealthyAsset from "@/assets/demo/diagnosis-camera-healthy.png";
import cameraChlorosisAsset from "@/assets/demo/diagnosis-camera-chlorosis.png";
import historicalCaseAsset from "@/assets/demo/diagnosis-historical-case.png";

export const demoLeafAssets = [cameraLeafAsset, cameraHealthyAsset, cameraChlorosisAsset];

export const demoEvidence: DemoEvidence[] = [
  {
    id: "ev-camera-01",
    category: "监控图像",
    title: "叶片出现明显黄化",
    detail: "Camera-03 连续两次采集到 A-12 东侧冠层叶片黄化与叶脉间失绿，符合营养吸收异常的视觉特征。",
    source: "Camera-03 · 监控图像",
    observedAt: "2026-09-04 09:32",
    weight: "high",
    claim: "叶片症状符合营养吸收障碍的典型表现",
    asset: cameraLeafAsset,
  },
  {
    id: "ev-weather-01",
    category: "气象数据",
    title: "近 14 天累计降雨 58 mm",
    detail: "降雨集中在过去 72 小时，未来 24 小时仍有 58–76 mm 降雨概率，根区排水压力持续。",
    source: "Weather Tool · 云阳站",
    observedAt: "2026-08-20 – 09-03",
    weight: "high",
    claim: "持续降雨可能导致根区缺氧并影响养分吸收",
  },
  {
    id: "ev-soil-01",
    category: "土壤数据",
    title: "土壤湿度持续偏高 84%",
    detail: "A-12 土壤传感器读数高于同坡度地块均值，且与近三日降雨存在时间关联。",
    source: "Soil Sensor · A12-S01",
    observedAt: "2026-09-03",
    weight: "high",
    claim: "A-12 当前湿度高于相邻地块，支持积水风险判断",
  },
  {
    id: "ev-knowledge-01",
    category: "农业知识",
    title: "柑橘根域排水技术规程",
    detail: "知识库中的农艺规程指出，果实膨大期根域长期高湿会降低根系活力并诱发叶片黄化。",
    source: "Knowledge Retriever · 农业知识库",
    observedAt: "第 5.3 节",
    weight: "medium",
    claim: "物候阶段与高湿环境共同放大营养吸收异常风险",
  },
  {
    id: "ev-case-01",
    category: "历史案例",
    title: "B-07 历史案例相似",
    detail: "B-07 地块历史诊断中曾出现相似的叶片黄化、湿度升高与降雨组合，复核结果为排水问题。",
    source: "Case Retriever · B-07",
    observedAt: "2025-07-12",
    weight: "medium",
    claim: "相似历史案例为当前诊断提供可追溯参考",
    asset: historicalCaseAsset,
  },
  {
    id: "ev-space-01",
    category: "空间证据",
    title: "A-12 位于相对低洼区域",
    detail: "GIS 空间分析显示 A-12 与周边地块存在轻微高程差，积水风险高于相邻地块。",
    source: "GIS Tool · 地形分析",
    observedAt: "2026-09-04 09:31",
    weight: "medium",
    claim: "空间位置与排水风险存在一致性",
  },
];

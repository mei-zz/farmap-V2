import { createRoot } from "react-dom/client";
import { AgentUiBlocks } from "../src/features/copilot/AgentUiBlocks";
import { parseUiBlock, type DraftTaskPayload } from "../src/features/copilot/uiBlocks";

declare global {
  interface Window {
    cardTestDrafts: DraftTaskPayload[];
    cardTestEvidence: string[];
    cardTestPrompts: string[];
  }
}

window.cardTestDrafts = [];
window.cardTestEvidence = [];
window.cardTestPrompts = [];

const stamp = "2026-11-27T00:00:00.000Z";
const block = (blockId: string, componentType: string, title: string, data: Record<string, unknown>, actions: unknown[] = []): Record<string, unknown> => ({
  blockId,
  runId: "domain-card-test",
  componentType,
  schemaVersion: 1,
  title,
  status: "ready",
  data,
  actions,
  evidenceIds: ["E1"],
  provenance: [{ label: "测试数据", reference: "E1" }],
  createdAt: stamp,
  updatedAt: stamp,
});

const rawBlocks = [
  block("phenology", "PlumPhenologyTimeline", "脆李物候时间线", {
    fieldId: "field-1",
    phenologyTimeline: {
      fieldName: "一号地块",
      crop: "脆李",
      date: "2026-11-27",
      current: "成熟期",
      next: "采收期",
      daysToNext: 12,
      registered: "成熟期",
      seasonal: "采收期",
      review: true,
      basis: "中熟青脆李基准历（海拔500–600m） + 海拔850m推迟8天",
      expectedRegisteredStage: "ripening",
      calibrationTarget: "harvest",
      accumulatedTemperature: 2450,
      thermalTarget: 2800,
    },
  }),
  block("phenology-expansion", "PlumPhenologyTimeline", "膨果期物候别名", {
    fieldId: "field-2",
    phenologyTimeline: {
      fieldName: "二号地块",
      crop: "脆李",
      date: "2026-06-15",
      current: "膨果",
      next: "转色成熟期",
      daysToNext: 20,
      registered: "膨果",
      seasonal: "膨果",
      basis: "测试前端物候别名归一。",
    },
  }),
  block("spray", "SprayWeatherWindowCard", "未来农事作业天气", {
    source: "Open-Meteo",
    rainHorizonHours: 6,
    useRestrictionsVerified: false,
    weatherWindows: [
      { startsAt: "2026-11-27T09:00:00+08:00", endsAt: "2026-11-27T10:00:00+08:00", score: 82, windSpeedKph: 7.5, precipitationMm: 0.2 },
      { startsAt: "2026-11-27T10:00:00+08:00", endsAt: "2026-11-27T11:00:00+08:00", score: 58, windSpeedKph: 12, precipitationMm: 0.6 },
    ],
    workDays: [
      { date: "2026-11-27", spray: { level: "suitable", score: 82, reason: "风速较低" }, irrigation: { level: "caution", score: 55 }, harvest: { level: "unsuitable", score: 20 } },
      { date: "2026-11-28", spray: { level: "caution", score: 60, reason: "午后阵风" }, irrigation: { level: "suitable", score: 88 }, harvest: { level: "caution", score: 50 } },
      { date: "2026-11-29", spray: { level: "unsuitable", score: 25, reason: "降雨概率高" }, irrigation: { level: "unsuitable", score: 30 }, harvest: { level: "suitable", score: 90 } },
    ],
    operationWindows: [
      { startsAt: "2026-11-27T09:00:00+08:00", spray: "SUITABLE", fertigation: "CAUTION", harvest: "UNSUITABLE" },
      { startsAt: "2026-11-28T09:00:00+08:00", spray: "CAUTION", fertigation: "SUITABLE", harvest: "CAUTION" },
      { startsAt: "2026-11-29T09:00:00+08:00", spray: "UNSUITABLE", fertigation: "UNSUITABLE", harvest: "SUITABLE" },
    ],
  }),
  block("pest", "PestComparisonCard", "病虫害现场对照", {
    siteImages: [
      { id: "site-1", label: "现场病果", url: "/uploads/domain-card-site.jpg", source: "巡园记录" },
      { id: "site-2", label: "病果近照", url: "/uploads/domain-card-close.jpg", source: "巡园记录" },
    ],
    atlasFigures: [
      { refId: "ref-1", label: "褐腐病图谱 A", imageUrl: "https://example.com/ref-1.jpg", match: "0.86" },
      { refId: "ref-2", label: "褐腐病图谱 B", imageUrl: "https://example.com/ref-2.jpg", match: "0.81" },
    ],
    diagnosisLabel: "褐腐病鉴别特征",
    differentialFeatures: [{ label: "果面褐色水渍状病斑", present: true, detail: "现场可见" }, { label: "表面灰黄色霉层" }],
    recommendation: { title: "推荐处置", detail: "先清理病果并隔离，再按登记标签复核用药。" },
    phiDays: 14,
    phiWarning: "距计划采收 10 天，当前药剂不得使用。",
    blockedByPhi: true,
  }),
  block("chart", "EChartsAnalyticsCard", "气象与积温趋势", {
    series: [
      { label: "气温", unit: "°C", type: "line", axis: "left", points: [{ label: "11-27", value: 12 }, { label: "11-28", value: 15 }, { label: "11-29", value: 9 }] },
      { label: "降水量", unit: "mm", type: "bar", axis: "right", points: [{ label: "11-27", value: 0 }, { label: "11-28", value: 4.2 }, { label: "11-29", value: 18.5 }] },
    ],
    thresholds: [{ label: "施药上限", value: 25, unit: "°C", axis: "left" }],
  }),
  block("growth", "CropGrowthChart", "作物生长趋势", {
    series: [{
      label: "株高", unit: "cm", points: [
        { label: "06-01", value: 82 }, { label: "06-08", value: 91 }, { label: "06-15", value: 103 },
      ],
    }],
  }),
  block("soil", "SoilTrendChart", "土壤墒情趋势", {
    series: [{
      label: "含水率", unit: "%", points: [
        { label: "06-01", value: 24 }, { label: "06-08", value: 21 }, { label: "06-15", value: 23 },
      ],
    }],
  }),
  block("analytics-series", "FarmAnalyticsCard", "农场地块长势", {
    series: [{
      label: "平均长势指数", points: [
        { label: "A-01", value: 78 }, { label: "A-02", value: 84 }, { label: "B-01", value: 69 },
      ],
    }],
  }),
  block("analytics-table", "FarmAnalyticsCard", "农场地块档案", {
    items: [
      { label: "地块", value: "A-01" }, { label: "作物", value: "脆李" }, { label: "面积", value: 3.2, unit: "ha" },
    ],
  }),
  block("weather-trend", "WeatherTrendChart", "降雨概率预报", {
    series: [{
      label: "降雨概率", unit: "%", points: [
        { label: "11-27", value: 10 }, { label: "11-28", value: 35 }, { label: "11-29", value: 70 },
      ],
    }],
  }),
  block("parcel-compare", "ParcelCompareChart", "地块长势对比", {
    series: [
      { label: "A-01", points: [{ label: "06-01", value: 72 }, { label: "06-15", value: 78 }] },
      { label: "A-02", points: [{ label: "06-01", value: 80 }, { label: "06-15", value: 84 }] },
    ],
  }),
  block("chart-empty", "EChartsAnalyticsCard", "无数据图表", {}),
  block("directory", "FarmDirectoryCard", "农场列表", {
    farms: [{ id: 1, name: "云雾山示范园" }],
  }),
  block("parcel", "ParcelOverviewCard", "当前地块", {
    fieldId: "field-1",
    items: [{ label: "地块", value: "A-01" }, { label: "作物", value: "脆李" }, { label: "面积", value: 3.2, unit: "ha" }],
  }),
  block("matrix", "ComparisonMatrixCard", "防治方案对比", {
    fieldId: "field-1",
    comparisonMatrix: {
      note: "按当前地块与安全间隔期比较。",
      options: [
        { id: "bio", label: "生物防治", summary: "低残留", recommended: true, values: {} },
        { id: "chemical", label: "化学低毒防治", values: {} },
      ],
      rows: [
        { id: "condition", label: "适用条件", values: { bio: "发病初期", chemical: "发病中期" } },
        { id: "cost", label: "成本", values: { bio: "较高", chemical: "中等" } },
        { id: "phi", label: "安全间隔期", values: { bio: "3 天", chemical: "14 天" } },
      ],
    },
  }),
  block("checklist", "ActionChecklistCard", "当前阶段农事清单", {
    fieldId: "field-1",
    actionChecklist: {
      note: "勾选需要转为任务的步骤。",
      items: [
        { id: "clear", title: "清理病果", detail: "带出园外无害化处理", priority: "urgent", prerequisites: ["佩戴手套"] },
        { id: "inspect", title: "复查树冠", detail: "记录病斑扩展", priority: "routine", scheduledFor: "2026-11-28",
          draftTask: { fieldId: "field-2", title: "复查树冠", detail: "记录病斑扩展", scheduledFor: "2026-11-28" } },
        { id: "prevent", title: "检查排水沟", priority: "preventive", prerequisites: ["降雨前完成"] },
      ],
    },
  }),
  block("memory", "MemoryProposalCard", "保存果园资料", {
    memoryProposal: { proposalId: "proposal-plum-123", displayText: "果园品种是脆李", scope: "FARM", status: "PENDING" },
  }),
];

rawBlocks.find((entry) => entry.blockId === "phenology")!.actions = [{
  type: "draft-task",
  label: "物候校准草稿",
  draftTask: { fieldId: "field-1", title: "物候校准草稿", detail: "核对登记物候与推算物候。", scheduledFor: "2026-11-30" },
}];
rawBlocks.find((entry) => entry.blockId === "spray")!.actions = [{ type: "open-evidence-drawer", label: "查看气象证据", evidenceId: "E1" }];

const blocks = rawBlocks.flatMap((entry) => {
  const parsed = parseUiBlock(entry);
  return parsed ? [parsed] : [];
});

createRoot(document.getElementById("root")!).render(
  <AgentUiBlocks
    blocks={blocks}
    onPrompt={(prompt) => window.cardTestPrompts.push(prompt)}
    onDraftTask={(payload) => window.cardTestDrafts.push(payload)}
    onOpenEvidence={(evidenceId) => window.cardTestEvidence.push(evidenceId)}
  />,
);

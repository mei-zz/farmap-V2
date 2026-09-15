import { ArrowRightOutlined, EnvironmentOutlined, MessageOutlined } from "@ant-design/icons";
import { Button, Flex, Tag, Typography } from "antd";
import type { OverviewMode, OverviewRecommendation } from "@/types/overview";
import RecommendationEvidence from "./RecommendationEvidence";
import RecommendationStatus from "./RecommendationStatus";

const priorityLabels: Record<string, string> = {
  "高优先级": "高",
  "中优先级": "中",
  "低优先级": "低",
};

export default function RecommendationCard({
  item,
  mode,
  onPrimaryAction,
  onAskAI,
}: {
  item: OverviewRecommendation;
  mode: OverviewMode;
  onPrimaryAction: (item: OverviewRecommendation) => void;
  onAskAI?: (item: OverviewRecommendation) => void;
}) {
  const relatedSignal = item.relatedRiskTitle ?? item.relatedSignalTitle;
  const relationLabel = item.relatedRiskId ? "关联风险" : item.relatedSignalId ? "关联信号" : undefined;

  return (
    <article className={`fm-recommendation-center__card fm-recommendation-center__card--${item.tone}`}>
      <div className="fm-recommendation-center__card-main">
        <Flex justify="space-between" align="flex-start" gap={10}>
          <div className="fm-recommendation-center__card-heading">
            <div className="fm-recommendation-center__meta">
              <Tag className="fm-recommendation-center__priority">{priorityLabels[item.priority] ?? item.priority}</Tag>
              <span>{item.relatedSignalType === "OPERATIONAL" ? "运营建议" : "风险建议"}</span>
            </div>
            <Typography.Title level={4}>{item.title}</Typography.Title>
          </div>
          <RecommendationStatus state={item.decisionState} mode={mode} />
        </Flex>

        {(item.fieldName || item.crop) && (
          <div className="fm-recommendation-center__field"><EnvironmentOutlined /> {item.fieldName ?? "当前农场"}{item.crop ? ` · ${item.crop}` : ""}{item.fieldArea !== undefined ? ` · ${item.fieldArea} ha` : ""}</div>
        )}

        <p className="fm-recommendation-center__action">{item.detail}</p>

        {relatedSignal && (
          <div className="fm-recommendation-center__related"><span>{relationLabel}</span><b>{relatedSignal}</b>{item.relatedRiskConfidenceLabel && <small>关联风险 {item.relatedRiskConfidenceLabel}</small>}</div>
        )}

        {item.impact && <div className="fm-recommendation-center__impact"><span>{item.impact.label ?? "影响范围"}</span><b>{item.impact.value}</b></div>}

        <RecommendationEvidence item={item} mode={mode} />
        {item.freshness?.updatedAt && <div className="fm-recommendation-center__freshness">建议基于 {item.freshness.updatedAt} 数据生成{item.freshness.stale ? " · 更新时间较旧" : ""}</div>}
      </div>
      <Flex justify="flex-end" align="center" gap={8} className="fm-recommendation-center__actions">
        <Button type="primary" size="small" onClick={() => onPrimaryAction(item)}>{item.primaryAction.label} <ArrowRightOutlined /></Button>
        {item.secondaryActions?.some((action) => action.type === "ASK_AI") && onAskAI && <Button type="text" size="small" icon={<MessageOutlined />} onClick={() => onAskAI(item)}>Ask AI</Button>}
      </Flex>
    </article>
  );
}

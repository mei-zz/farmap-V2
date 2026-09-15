import { ArrowRightOutlined, MessageOutlined } from "@ant-design/icons";
import { Button, Flex, Typography } from "antd";
import type { OverviewDecisionItem, OverviewMode } from "@/types/overview";
import DecisionEvidence from "./DecisionEvidence";
import DecisionPriorityBadge from "./DecisionPriorityBadge";

const statusLabels: Record<string, string> = {
  OPEN: "待处理",
  NEW: "新发现",
  NEEDS_REVIEW: "需要复核",
  PENDING_APPROVAL: "待审批",
  IN_PROGRESS: "进行中",
  OVERDUE: "已逾期",
  COMPLETED: "已完成",
};

const kindLabels: Record<OverviewDecisionItem["kind"], string> = {
  RISK: "风险",
  APPROVAL: "审批",
  OPERATION: "农事任务",
  REVIEW: "专家复核",
  DATA_QUALITY: "数据质量",
};

type DecisionCardProps = {
  item: OverviewDecisionItem;
  mode: OverviewMode;
  onPrimaryAction: (item: OverviewDecisionItem) => void;
  onAskAI?: (item: OverviewDecisionItem) => void;
};

export default function DecisionCard({ item, mode, onPrimaryAction, onAskAI }: DecisionCardProps) {
  const statusLabel = statusLabels[item.status] ?? item.status;
  const typeLabel = item.typeLabel ?? kindLabels[item.kind];
  const signalLabel = item.confidenceLabel?.includes("置信度") ? "置信度" : "AI 信号";

  return (
    <article className={`fm-decision-card fm-decision-card--${item.priority.toLowerCase()}`}>
      <Flex justify="space-between" align="center" gap={8} className="fm-decision-card__meta">
        <Flex align="center" gap={6}>
          <DecisionPriorityBadge priority={item.priority} />
          <span className="fm-decision-card__type">{typeLabel}</span>
        </Flex>
        <span className="fm-decision-card__status">{statusLabel}</span>
      </Flex>

      <Typography.Title level={5} className="fm-decision-card__title">
        {item.title}
      </Typography.Title>

      {(item.fieldName || item.crop) && (
        <div className="fm-decision-card__context">
          {item.fieldName && <span>{item.fieldName}</span>}
          {item.crop && <span>{item.crop}</span>}
          {item.fieldArea !== undefined && <span>{item.fieldArea} ha</span>}
        </div>
      )}

      <p className="fm-decision-card__summary">{item.summary}</p>

      {(item.impact?.value || item.confidenceLabel) && (
        <div className="fm-decision-card__facts">
          {item.impact?.value && <span><small>{item.impact.label ?? "影响"}</small><b>{item.impact.value}</b></span>}
          {item.confidenceLabel && <span><small>{signalLabel}</small><b>{item.confidenceLabel}</b></span>}
        </div>
      )}

      <DecisionEvidence item={item} mode={mode} />

      {item.recommendedAction && (
        <div className="fm-decision-card__recommendation">
          <small>建议下一步</small>
          <span>{item.recommendedAction}</span>
        </div>
      )}

      <Flex justify="space-between" align="center" gap={8} className="fm-decision-card__actions">
        <Button type="primary" size="small" onClick={() => onPrimaryAction(item)}>
          {item.nextAction.label} <ArrowRightOutlined />
        </Button>
        {item.secondaryActions?.some((action) => action.type === "ASK_AI") && onAskAI && (
          <Button type="text" size="small" icon={<MessageOutlined />} onClick={() => onAskAI(item)}>
            Ask AI
          </Button>
        )}
      </Flex>
    </article>
  );
}

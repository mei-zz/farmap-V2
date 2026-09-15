import { ArrowRightOutlined, ClockCircleOutlined, EnvironmentOutlined } from "@ant-design/icons";
import { Button, Flex } from "antd";
import type { OverviewMode, OverviewOperationTask } from "@/types/overview";

const priorityLabels: Record<OverviewOperationTask["priority"], string> = {
  CRITICAL: "高优先级",
  HIGH: "重点关注",
  MEDIUM: "计划内",
  LOW: "例行",
};

const statusLabels: Record<OverviewOperationTask["status"], string> = {
  PENDING: "待处理",
  IN_PROGRESS: "执行中",
  COMPLETED: "已完成",
  OVERDUE: "已逾期",
};

const sourceLabels: Record<NonNullable<OverviewOperationTask["source"]>, string> = {
  AI_RECOMMENDATION: "AI 建议",
  FIELD_MONITORING: "地块监测",
  SEASONAL_GUIDANCE: "季节指导",
  UNLINKED: "来源建议未关联",
};

function displayTaskName(task: OverviewOperationTask) {
  const rawName = task.name.trim();
  const hasInternalId = /\b(?:field|farm|task|diagnosis)-[a-z0-9-]{12,}\b/i.test(rawName)
    || /\b[a-f0-9]{8}-[a-f0-9-]{4}-[a-f0-9-]{4}-[a-f0-9-]{4}-[a-f0-9-]{12}\b/i.test(rawName);
  if (!hasInternalId) return rawName;
  const fieldLabel = task.fieldName ?? "当前地块";
  return rawName.includes("复核") ? `${fieldLabel}风险复核` : `${fieldLabel}农事任务`;
}

export default function OperationStatusItem({
  task,
  mode,
  onOpenTask,
}: {
  task: OverviewOperationTask;
  mode: OverviewMode;
  onOpenTask: (task: OverviewOperationTask) => void;
}) {
  const statusLabel = mode === "DEMO" ? `演示 · ${statusLabels[task.status]}` : statusLabels[task.status];
  const sourceLabel = task.sourceLabel ?? sourceLabels[task.source ?? "UNLINKED"];
  const fieldLabel = task.fieldName ?? task.fieldId;
  const freshnessLabel = task.updatedAt ? `更新时间 ${task.updatedAt}` : `计划时间 ${task.time}`;

  return (
    <article className={`fm-operations-status__item fm-operations-status__item--${task.priority.toLowerCase()}`}>
      <div className="fm-operations-status__item-main">
        <Flex align="center" gap={8} wrap>
          <span className="fm-operations-status__priority">{priorityLabels[task.priority]}</span>
          <span className={`fm-operations-status__state fm-operations-status__state--${task.status.toLowerCase()}`}>{statusLabel}</span>
        </Flex>
        <b className="fm-operations-status__item-title">{displayTaskName(task)}</b>
        <div className="fm-operations-status__item-context">
          {fieldLabel && <span><EnvironmentOutlined /> {fieldLabel}{task.fieldArea !== undefined ? ` · ${task.fieldArea} ha` : ""}</span>}
          <span className="fm-operations-status__item-source">来源 · {sourceLabel}</span>
        </div>
        <span className="fm-operations-status__item-freshness"><ClockCircleOutlined /> {freshnessLabel}</span>
      </div>
      <Button className="fm-operations-status__item-action" type="link" size="small" onClick={() => onOpenTask(task)}>
        查看任务 <ArrowRightOutlined />
      </Button>
    </article>
  );
}

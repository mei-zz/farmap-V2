import { Tag } from "antd";
import type { OverviewPriority } from "@/types/overview";

const priorityLabels: Record<OverviewPriority, string> = {
  CRITICAL: "紧急",
  HIGH: "高优先级",
  MEDIUM: "中优先级",
  LOW: "低优先级",
};

const priorityColors: Record<OverviewPriority, string> = {
  CRITICAL: "error",
  HIGH: "warning",
  MEDIUM: "processing",
  LOW: "default",
};

export default function DecisionPriorityBadge({ priority }: { priority: OverviewPriority }) {
  return (
    <Tag className={`fm-decision-priority fm-decision-priority--${priority.toLowerCase()}`} color={priorityColors[priority]}>
      {priorityLabels[priority]}
    </Tag>
  );
}

import { CheckCircleFilled, ClockCircleOutlined, LoadingOutlined, WarningFilled } from "@ant-design/icons";
import { Tag } from "antd";

export type StatusBadgeTone = "healthy" | "attention" | "risk" | "pending" | "running" | "completed" | "failed";

const defaults: Record<StatusBadgeTone, { label: string; color: string; icon: React.ReactNode }> = {
  healthy: { label: "正常", color: "success", icon: <CheckCircleFilled /> },
  attention: { label: "需关注", color: "warning", icon: <WarningFilled /> },
  risk: { label: "高风险", color: "error", icon: <WarningFilled /> },
  pending: { label: "待运行", color: "default", icon: <ClockCircleOutlined /> },
  running: { label: "运行中", color: "processing", icon: <LoadingOutlined /> },
  completed: { label: "已完成", color: "success", icon: <CheckCircleFilled /> },
  failed: { label: "失败", color: "error", icon: <WarningFilled /> },
};

export default function StatusBadge({ tone, label }: { tone: StatusBadgeTone; label?: string }) {
  const preset = defaults[tone];
  return (
    <Tag className={`fm-status fm-status--${tone}`} color={preset.color} icon={preset.icon}>
      {label ?? preset.label}
    </Tag>
  );
}

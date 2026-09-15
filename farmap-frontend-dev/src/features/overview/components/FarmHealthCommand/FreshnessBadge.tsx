import { ClockCircleOutlined } from "@ant-design/icons";
import type { OverviewFreshness, OverviewMode } from "@/types/overview";

export default function FreshnessBadge({ freshness, mode }: { freshness?: OverviewFreshness; mode: OverviewMode }) {
  const hasTimestamp = Boolean(freshness?.updatedAt);
  const label = mode === "STALE"
    ? "更新时间较旧"
    : mode === "DEMO"
      ? "演示数据"
      : mode === "UNAVAILABLE"
        ? "数据不可用"
        : hasTimestamp
          ? "数据已更新"
          : "更新时间未知";
  const time = mode === "UNAVAILABLE" ? "—" : freshness?.updatedAt ?? "暂无更新时间";

  return (
    <div className={`fm-freshness-badge fm-freshness-badge--${mode.toLowerCase()}`} role="status">
      <ClockCircleOutlined />
      <span><b>{label}</b><small>{time}</small></span>
    </div>
  );
}

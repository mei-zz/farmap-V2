import { ClockCircleOutlined, EnvironmentOutlined, FileSearchOutlined } from "@ant-design/icons";
import { Flex, Tag } from "antd";
import type { OverviewMode, OverviewRiskPriority } from "@/types/overview";
import { normalizeOverviewEvidenceSources } from "@/features/overview/copilot/context";

const priorityLabels: Record<OverviewRiskPriority["priority"], string> = {
  CRITICAL: "紧急",
  HIGH: "高优先级",
  MEDIUM: "中优先级",
  LOW: "低优先级",
};

const statusLabels: Record<string, string> = {
  OPEN: "监测中",
  NEW: "新发现",
  NEEDS_REVIEW: "待复核",
  COMPLETED: "已解决",
};

function formatEvidence(item: OverviewRiskPriority, mode: OverviewMode) {
  const evidence = normalizeOverviewEvidenceSources(item.evidenceSources, mode);
  const sourceText = evidence.labels.length ? evidence.labels.join(" · ") : "证据来源暂不可用";
  if (mode === "UNAVAILABLE") return `${sourceText} · 证据数量暂不可用`;
  if (evidence.historyUnavailable) return `${sourceText} · 历史案例暂不可用`;
  return item.evidenceCount ? `${sourceText} · ${item.evidenceCount} 条证据` : sourceText;
}

function formatFreshness(item: OverviewRiskPriority, mode: OverviewMode) {
  const updatedAt = item.freshness?.updatedAt ?? "暂无更新时间";
  if (mode === "DEMO") return `演示数据 · ${updatedAt}`;
  if (mode === "STALE") return `更新时间较旧 · ${updatedAt}`;
  return `更新于 ${updatedAt}`;
}

export default function RiskPriorityItem({
  item,
  mode,
  selected,
  onSelect,
}: {
  item: OverviewRiskPriority;
  mode: OverviewMode;
  selected: boolean;
  onSelect: (item: OverviewRiskPriority) => void;
}) {
  const statusLabel = statusLabels[item.status] ?? item.status;
  const fieldLabel = item.fieldName ?? "全农场";
  const impactLabel = item.fieldArea !== undefined ? `${item.fieldArea} ha` : item.impact?.value;

  return (
    <button
      type="button"
      className={`fm-risk-priority-item fm-risk-priority-item--${item.priority.toLowerCase()}${selected ? " is-selected" : ""}`}
      aria-pressed={selected}
      onClick={() => onSelect(item)}
    >
      <Flex justify="space-between" align="center" gap={8}>
        <Flex align="center" gap={6}>
          <Tag className="fm-risk-priority-item__priority">{priorityLabels[item.priority]}</Tag>
          <span className="fm-risk-priority-item__category">{item.typeLabel ?? "风险信号"}</span>
        </Flex>
        <span className="fm-risk-priority-item__status">{statusLabel}</span>
      </Flex>
      <strong className="fm-risk-priority-item__title">{item.title}</strong>
      <span className="fm-risk-priority-item__field"><EnvironmentOutlined /> {fieldLabel}{item.crop ? ` · ${item.crop}` : ""}</span>
      <span className="fm-risk-priority-item__summary">{item.summary}</span>
      <div className="fm-risk-priority-item__facts">
        {impactLabel && <span>影响范围 <b>{impactLabel}</b></span>}
        {item.confidenceLabel && <span>AI 信号 <b>{item.confidenceLabel}</b></span>}
      </div>
      <span className="fm-risk-priority-item__evidence"><FileSearchOutlined /> {formatEvidence(item, mode)}</span>
      <span className="fm-risk-priority-item__freshness"><ClockCircleOutlined /> {formatFreshness(item, mode)}</span>
    </button>
  );
}

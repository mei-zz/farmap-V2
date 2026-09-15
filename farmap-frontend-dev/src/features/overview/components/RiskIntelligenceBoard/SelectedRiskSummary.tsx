import { EnvironmentOutlined, FileSearchOutlined } from "@ant-design/icons";
import { Tag } from "antd";
import type { OverviewMode, OverviewRiskPriority } from "@/types/overview";
import { normalizeOverviewEvidenceSources } from "@/features/overview/copilot/context";

export default function SelectedRiskSummary({ item, mode }: { item?: OverviewRiskPriority; mode: OverviewMode }) {
  if (!item) return null;
  const normalizedEvidence = normalizeOverviewEvidenceSources(item.evidenceSources, mode);
  const evidence = normalizedEvidence.labels.join(" · ");

  return (
    <div className="fm-selected-risk-summary">
      <div className="fm-eyebrow">SELECTED RISK</div>
      <div className="fm-selected-risk-summary__title"><strong>{item.title}</strong><Tag>{item.priority}</Tag></div>
      <div className="fm-selected-risk-summary__field"><EnvironmentOutlined /> {item.fieldName ?? "全农场范围"}{item.fieldArea !== undefined ? ` · ${item.fieldArea} ha` : ""}</div>
      <p>{item.summary}</p>
      <div className="fm-selected-risk-summary__evidence"><FileSearchOutlined /> {evidence || "暂无可用证据来源"}{mode === "UNAVAILABLE" ? " · 证据数量暂不可用" : normalizedEvidence.historyUnavailable ? " · 历史案例暂不可用" : item.evidenceCount ? ` · ${item.evidenceCount} 条证据` : ""}</div>
    </div>
  );
}

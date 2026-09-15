import { FileSearchOutlined } from "@ant-design/icons";
import type { OverviewDecisionItem, OverviewMode } from "@/types/overview";
import { normalizeOverviewEvidenceSources } from "@/features/overview/copilot/context";

export default function DecisionEvidence({ item, mode }: { item: OverviewDecisionItem; mode: OverviewMode }) {
  const evidence = normalizeOverviewEvidenceSources(item.evidenceSources, mode);
  if (!item.evidenceCount && !evidence.labels.length && !evidence.historyUnavailable) return null;

  return (
    <div className="fm-decision-evidence" aria-label="决策证据">
      <FileSearchOutlined />
      <span>
        {evidence.labels.length ? evidence.labels.join(" · ") : "已关联证据"}
        {mode === "UNAVAILABLE"
          ? " · 证据数量暂不可用"
          : evidence.historyUnavailable
            ? " · 历史案例暂不可用"
            : item.evidenceCount
              ? ` · ${item.evidenceCount} 条证据`
              : ""}
      </span>
    </div>
  );
}

import { FileSearchOutlined } from "@ant-design/icons";
import type { OverviewMode, OverviewRecommendation } from "@/types/overview";
import { normalizeOverviewEvidenceSources } from "@/features/overview/copilot/context";

export default function RecommendationEvidence({ item, mode }: { item: OverviewRecommendation; mode: OverviewMode }) {
  const evidence = normalizeOverviewEvidenceSources(item.evidenceSources, mode);
  const sourceText = evidence.labels.length ? evidence.labels.join(" · ") : "证据来源暂不可用";
  const countText = mode === "UNAVAILABLE"
    ? "证据数量暂不可用"
    : evidence.historyUnavailable
      ? "历史案例暂不可用"
      : item.evidenceCount !== undefined
        ? `${item.evidenceCount} 条证据`
        : "证据数量暂不可用";

  return (
    <div className="fm-recommendation-center__evidence">
      <FileSearchOutlined />
      <span>支持证据</span>
      <b>{sourceText}</b>
      <small>{countText}</small>
    </div>
  );
}

import { useMemo, useState } from "react";
import { Flex, Typography } from "antd";
import type { OverviewFreshness, OverviewMap, OverviewMode, OverviewRiskPriority } from "@/types/overview";
import FarmRiskMapPanel from "./FarmRiskMapPanel";
import RiskPriorityList from "./RiskPriorityList";
import SelectedRiskSummary from "./SelectedRiskSummary";

const { Text, Title } = Typography;

type RiskIntelligenceBoardProps = {
  risks: OverviewRiskPriority[];
  map: OverviewMap;
  mode: OverviewMode;
  freshness: OverviewFreshness[];
  selectedRiskId?: string;
  onRiskSelect: (risk: OverviewRiskPriority) => void;
};

export default function RiskIntelligenceBoard({ risks, map, mode, freshness, selectedRiskId, onRiskSelect }: RiskIntelligenceBoardProps) {
  const riskItems = useMemo(() => risks.filter((item) => item.signalType === "RISK"), [risks]);
  const visibleRiskItems = mode === "UNAVAILABLE" ? [] : riskItems;
  const [internalSelectedRiskId, setInternalSelectedRiskId] = useState<string | undefined>(visibleRiskItems[0]?.id);
  const activeRiskId = selectedRiskId ?? internalSelectedRiskId;
  const selectedRisk = visibleRiskItems.find((item) => item.id === activeRiskId) ?? visibleRiskItems[0];
  const farmFreshness = freshness.find((item) => item.source === "farm") ?? freshness[0];

  const handleSelect = (risk: OverviewRiskPriority) => {
    setInternalSelectedRiskId(risk.id);
    onRiskSelect(risk);
  };

  return (
    <section className="fm-risk-intelligence-board" aria-labelledby="risk-intelligence-title">
      <Flex className="fm-risk-intelligence-board__header" justify="space-between" align="flex-end" gap={16} wrap>
        <div>
          <div className="fm-eyebrow">RISK INTELLIGENCE</div>
          <Title level={3} id="risk-intelligence-title">风险智能</Title>
          <Text type="secondary">把风险、地块、空间影响和证据放在同一个判断界面。</Text>
        </div>
        <span className="fm-risk-intelligence-board__freshness">{mode === "STALE" ? "更新时间较旧" : mode === "DEMO" ? "演示数据" : mode === "UNAVAILABLE" ? "风险数据不可用" : "数据状态"}{mode !== "UNAVAILABLE" && farmFreshness?.updatedAt ? ` · ${farmFreshness.updatedAt}` : ""}</span>
      </Flex>
      <div className="fm-risk-intelligence-board__body">
        <FarmRiskMapPanel map={map} selectedRisk={selectedRisk} mode={mode} />
        <div className="fm-risk-intelligence-board__list-column">
          <RiskPriorityList items={visibleRiskItems} mode={mode} selectedRiskId={activeRiskId} onSelect={handleSelect} />
          <SelectedRiskSummary item={selectedRisk} mode={mode} />
        </div>
      </div>
    </section>
  );
}

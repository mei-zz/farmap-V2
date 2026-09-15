import { CheckCircleOutlined, InfoCircleOutlined } from "@ant-design/icons";
import { Flex, Typography } from "antd";
import type { OverviewMode, OverviewRiskPriority } from "@/types/overview";
import RiskPriorityItem from "./RiskPriorityItem";

const { Text, Title } = Typography;

export default function RiskPriorityList({
  items,
  mode,
  selectedRiskId,
  onSelect,
}: {
  items: OverviewRiskPriority[];
  mode: OverviewMode;
  selectedRiskId?: string;
  onSelect: (item: OverviewRiskPriority) => void;
}) {
  return (
    <section className="fm-risk-priority-list" aria-labelledby="risk-priority-list-title">
      <Flex justify="space-between" align="flex-end" gap={8}>
        <div>
          <div className="fm-eyebrow">RISK PRIORITIES</div>
          <Flex align="center" gap={8}>
            <Title level={4} id="risk-priority-list-title">风险优先级</Title>
            <span className="fm-risk-priority-list__count">{items.length}</span>
          </Flex>
          <Text type="secondary">按空间影响和风险等级查看当前信号。</Text>
        </div>
      </Flex>

      {items.length === 0 ? (
        <div className="fm-risk-priority-list__state" role="status">
          {mode === "UNAVAILABLE" ? <InfoCircleOutlined /> : <CheckCircleOutlined />}
          <span>{mode === "UNAVAILABLE" ? "风险数据暂不可用" : mode === "STALE" ? "风险列表为空，且数据更新时间较旧" : "当前没有检测到需要关注的风险信号"}</span>
        </div>
      ) : (
        <div className="fm-risk-priority-list__items">
          {items.map((item) => (
            <RiskPriorityItem key={item.id} item={item} mode={mode} selected={item.id === selectedRiskId} onSelect={onSelect} />
          ))}
        </div>
      )}
    </section>
  );
}

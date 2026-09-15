import { CheckCircleOutlined, ClockCircleOutlined, InfoCircleOutlined } from "@ant-design/icons";
import { Flex, Typography } from "antd";
import type { OverviewDecisionItem, OverviewFreshness, OverviewMode } from "@/types/overview";
import DecisionCard from "./DecisionCard";

type DecisionQueueProps = {
  items: OverviewDecisionItem[];
  mode: OverviewMode;
  freshness?: OverviewFreshness[];
  onPrimaryAction: (item: OverviewDecisionItem) => void;
  onAskAI?: (item: OverviewDecisionItem) => void;
};

function getLatestFreshness(freshness: OverviewFreshness[] | undefined) {
  return freshness?.find((item) => item.source === "diagnosis") ?? freshness?.[0];
}

export default function DecisionQueue({ items, mode, freshness, onPrimaryAction, onAskAI }: DecisionQueueProps) {
  const visibleItems = items.slice(0, 3);
  const latestFreshness = getLatestFreshness(freshness);
  const isStale = mode !== "UNAVAILABLE" && (mode === "STALE" || latestFreshness?.mode === "STALE");

  return (
    <section className="fm-decision-queue" aria-labelledby="decision-queue-title">
      <Flex justify="space-between" align="flex-end" gap={16} wrap className="fm-decision-queue__header">
        <div>
          <div className="fm-eyebrow">TODAY / DECISION QUEUE</div>
          <Flex align="center" gap={9} wrap>
            <Typography.Title level={3} id="decision-queue-title">今日优先事项</Typography.Title>
            <span className="fm-decision-queue__count">{items.length}</span>
          </Flex>
          <Typography.Text type="secondary">FarMap Agent 已按优先级整理当前农场最值得关注的事项。</Typography.Text>
        </div>
        {items.length > 3 && <span className="fm-decision-queue__view-all">共 {items.length} 项，当前显示优先级最高的 3 项</span>}
      </Flex>

      {isStale && latestFreshness?.updatedAt && (
        <div className="fm-decision-queue__freshness"><ClockCircleOutlined /> 数据更新于 {latestFreshness.updatedAt}</div>
      )}

      {mode === "UNAVAILABLE" ? (
        <div className="fm-decision-queue__state fm-decision-queue__state--unavailable" role="status">
          <InfoCircleOutlined />
          <div><b>决策数据暂不可用</b><span>当前无法判断农场是否存在待处理事项，请稍后重试。</span></div>
        </div>
      ) : visibleItems.length === 0 ? (
        <div className="fm-decision-queue__state" role="status">
          <CheckCircleOutlined />
          <div><b>今日暂无需要立即处理的事项</b><span>农场运行稳定，新的风险和建议会在出现后显示。</span></div>
        </div>
      ) : (
        <div className="fm-decision-queue__items">
          {visibleItems.map((item) => <DecisionCard key={item.id} item={item} mode={mode} onPrimaryAction={onPrimaryAction} onAskAI={onAskAI} />)}
        </div>
      )}
    </section>
  );
}

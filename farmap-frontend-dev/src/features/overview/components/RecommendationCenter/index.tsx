import { CheckCircleOutlined, InfoCircleOutlined } from "@ant-design/icons";
import { Flex, Typography } from "antd";
import type { OverviewFreshness, OverviewMode, OverviewRecommendation } from "@/types/overview";
import RecommendationCard from "./RecommendationCard";

const { Text, Title } = Typography;

export default function RecommendationCenter({
  recommendations,
  mode,
  freshness,
  onPrimaryAction,
  onAskAI,
}: {
  recommendations: OverviewRecommendation[];
  mode: OverviewMode;
  freshness: OverviewFreshness[];
  onPrimaryAction: (item: OverviewRecommendation) => void;
  onAskAI?: (item: OverviewRecommendation) => void;
}) {
  const visibleRecommendations = recommendations.slice(0, 3);
  const diagnosisFreshness = freshness.find((item) => item.source === "diagnosis") ?? freshness[0];

  return (
    <section className="fm-recommendation-center" aria-labelledby="recommendation-center-title">
      <Flex className="fm-recommendation-center__header" justify="space-between" align="flex-end" gap={16} wrap>
        <div>
          <div className="fm-eyebrow">AI RECOMMENDATIONS</div>
          <Flex align="center" gap={8}>
            <Title level={3} id="recommendation-center-title">AI 建议</Title>
            {recommendations.length > 0 && <span className="fm-recommendation-center__count">{recommendations.length}</span>}
          </Flex>
          <Text type="secondary">基于风险、农场信号和 Evidence 形成可复核的农业处理方案。{recommendations.length > visibleRecommendations.length ? " 当前展示 3 条高价值建议。" : ""}</Text>
        </div>
        {mode !== "UNAVAILABLE" && diagnosisFreshness?.updatedAt && <span className="fm-recommendation-center__freshness-badge">{mode === "STALE" ? "建议基于较旧数据" : mode === "DEMO" ? "演示建议" : "建议更新时间"} · {diagnosisFreshness.updatedAt}</span>}
      </Flex>

      {mode === "UNAVAILABLE" ? (
        <div className="fm-recommendation-center__state" role="status"><InfoCircleOutlined /><span>AI 建议暂不可用</span></div>
      ) : visibleRecommendations.length === 0 ? (
        <div className="fm-recommendation-center__state" role="status"><CheckCircleOutlined /><span>当前暂无新的 AI 建议</span></div>
      ) : (
        <div className="fm-recommendation-center__items">
          {visibleRecommendations.map((item) => <RecommendationCard key={item.id} item={item} mode={mode} onPrimaryAction={onPrimaryAction} onAskAI={onAskAI} />)}
        </div>
      )}
    </section>
  );
}

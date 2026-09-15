import { Flex, Progress, Typography } from "antd";
import type { OverviewHealth, OverviewHealthStatus } from "@/types/overview";
import StatusBadge, { type StatusBadgeTone } from "@/components/commercial/StatusBadge";

const { Text, Title } = Typography;

const statusMeta: Record<OverviewHealthStatus, { color: string; tone: StatusBadgeTone }> = {
  HEALTHY: { color: "#2b8d5b", tone: "healthy" },
  WATCH: { color: "#bf812c", tone: "attention" },
  RISK: { color: "#d84d51", tone: "risk" },
  EMPTY: { color: "#8b9890", tone: "pending" },
  UNAVAILABLE: { color: "#8b9890", tone: "pending" },
};

export default function HealthScoreCard({ health }: { health: OverviewHealth }) {
  const score = Math.max(0, Math.min(100, Math.round(health.score)));
  const meta = statusMeta[health.status];
  const trendLabel = `${health.trend >= 0 ? "↑" : "↓"} ${health.trend >= 0 ? "+" : ""}${health.trend}%`;

  return (
    <div className={`fm-health-score fm-health-score--${health.status.toLowerCase()}`}>
      <div className="fm-health-score__ring" aria-label={`农场健康度 ${score} 分`}>
        <Progress
          type="circle"
          percent={score}
          size={108}
          strokeWidth={8}
          strokeColor={meta.color}
          railColor="#e8efe9"
          format={() => <span className="fm-health-score__value"><strong>{score}</strong><small>/100</small></span>}
        />
      </div>
      <div className="fm-health-score__content">
        <div className="fm-eyebrow">OVERALL FARM HEALTH</div>
        <Flex align="center" gap={9} wrap>
          <Title level={4}>农场总体健康度</Title>
          <StatusBadge tone={meta.tone} label={health.statusLabel} />
        </Flex>
        <Text className="fm-health-score__summary">{health.summary}</Text>
        <div className="fm-health-score__trend"><span>较上一周期</span><b>{trendLabel}</b></div>
      </div>
    </div>
  );
}

import { AlertOutlined, CheckCircleOutlined, ClockCircleOutlined, DeploymentUnitOutlined, EnvironmentOutlined } from "@ant-design/icons";
import { Flex, Typography } from "antd";
import type { OverviewFreshness, OverviewHealth, OverviewMode, OverviewOperations } from "@/types/overview";
import FreshnessBadge from "./FreshnessBadge";
import HealthIndicator from "./HealthIndicator";
import HealthScoreCard from "./HealthScoreCard";

const { Text, Title } = Typography;

type FarmHealthCommandProps = {
  health: OverviewHealth;
  operations: OverviewOperations;
  freshness: OverviewFreshness[];
  mode: OverviewMode;
};

function StateMessage({ type, reason }: { type: "EMPTY" | "UNAVAILABLE"; reason?: string }) {
  const unavailable = type === "UNAVAILABLE";
  return (
    <div className={`fm-farm-health-command__state fm-farm-health-command__state--${type.toLowerCase()}`} role="status">
      {unavailable ? <AlertOutlined /> : <CheckCircleOutlined />}
      <div>
        <b>{unavailable ? "健康状态暂不可用" : "暂无健康状态数据"}</b>
        <span>{reason ?? (unavailable ? "当前无法确认农场整体状态。" : "接入农场数据后，这里会汇总健康度、风险覆盖和数据时效。")}</span>
      </div>
    </div>
  );
}

export default function FarmHealthCommand({ health, operations, freshness, mode }: FarmHealthCommandProps) {
  const farmFreshness = freshness.find((item) => item.source === "farm") ?? freshness[0];
  const unavailable = mode === "UNAVAILABLE" || health.status === "UNAVAILABLE";
  const empty = !unavailable && health.status === "EMPTY";
  const activeTaskCount = operations.pendingExecution + operations.inProgress;
  const riskTone = health.status === "RISK" ? "risk" : health.riskFieldCount > 0 ? "attention" : "healthy";
  const affectedTone = health.affectedArea > 0 ? "attention" : "healthy";
  const freshnessDetail = mode === "STALE"
    ? "请先核对最新农情"
    : mode === "DEMO"
      ? "当前为演示数据"
      : farmFreshness?.updatedAt
        ? "可追溯更新时间"
        : "无法确认数据时效";

  return (
    <section className={`fm-farm-health-command fm-farm-health-command--${health.status.toLowerCase()}`} aria-labelledby="farm-health-command-title">
      <Flex className="fm-farm-health-command__header" justify="space-between" align="flex-end" gap={16} wrap>
        <div>
          <div className="fm-eyebrow">FARM HEALTH / COMMAND</div>
          <Title level={3} id="farm-health-command-title">农场健康指挥台</Title>
          <Text type="secondary">看清整体健康、风险覆盖和数据可信度，再决定是否需要介入。</Text>
        </div>
        <FreshnessBadge freshness={farmFreshness} mode={mode} />
      </Flex>

      {unavailable ? <StateMessage type="UNAVAILABLE" reason={health.reason} /> : empty ? <StateMessage type="EMPTY" reason={health.reason} /> : (
        <div className="fm-farm-health-command__body">
          <HealthScoreCard health={health} />
          <div className="fm-health-indicators">
            <HealthIndicator
              label="风险覆盖"
              value={`${health.riskFieldCount} 个地块`}
              detail={health.fieldCount ? `占全部地块 ${Math.round((health.riskFieldCount / health.fieldCount) * 100)}%` : "暂无地块数据"}
              icon={<AlertOutlined />}
              tone={riskTone}
            />
            <HealthIndicator
              label="受影响面积"
              value={`${health.affectedArea.toFixed(1)} ha`}
              detail={health.totalArea ? `占农场面积 ${Math.round((health.affectedArea / health.totalArea) * 100)}%` : "暂无面积数据"}
              icon={<EnvironmentOutlined />}
              tone={affectedTone}
            />
            <HealthIndicator
              label="运营状态"
              value={`${activeTaskCount} 项活跃任务`}
              detail={`${operations.pendingApproval} 项待审批 · ${operations.inProgress} 项执行中`}
              icon={<DeploymentUnitOutlined />}
              tone={operations.pendingApproval > 0 ? "attention" : "healthy"}
            />
            <HealthIndicator
              label="数据新鲜度"
              value={farmFreshness?.updatedAt ?? "暂无更新时间"}
              detail={freshnessDetail}
              icon={<ClockCircleOutlined />}
              tone={mode === "STALE" ? "attention" : "neutral"}
            />
          </div>
        </div>
      )}
    </section>
  );
}

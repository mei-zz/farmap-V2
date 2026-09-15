import type { ReactNode } from "react";
import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  CloudOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  EnvironmentOutlined,
  FileSearchOutlined,
  RobotOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import { Button, Card, Flex, Tag, Typography } from "antd";
import { useNavigate } from "react-router";
import DecisionQueue from "./DecisionQueue";
import OperationsStatus from "./OperationsStatus";
import RiskIntelligenceBoard from "./RiskIntelligenceBoard";
import type {
  OverviewDecisionItem,
  OverviewOperationTask,
  OverviewRiskPriority,
  OverviewViewModel,
} from "@/types/overview";

const { Text, Title } = Typography;

type HomepageCommandCenterProps = {
  overview: OverviewViewModel;
  monitor: ReactNode;
  onStartDiagnosis: () => void;
  onOpenDiagnosis: (diagnosisId?: string) => void;
  onOpenAgentRuns: () => void;
  onOpenDigitalFarm: () => void;
  onOpenOperations: () => void;
  onDecisionPrimaryAction: (item: OverviewDecisionItem) => void;
  onDecisionAskAI: (item: OverviewDecisionItem) => void;
  onRiskSelect: (risk: OverviewRiskPriority) => void;
  selectedRiskId?: string;
  onOperationOpen: (task: OverviewOperationTask) => void;
};

function formatActivityTime(value?: string) {
  if (!value || value === "—") return "刚刚";
  const date = new Date(value);
  if (!Number.isNaN(date.getTime())) {
    return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit" }).format(date);
  }
  return value.replace("T", " ").slice(0, 16);
}

function cleanTaskTitle(value: string, fieldName?: string) {
  const cleaned = value
    .replace(/\b(?:field|farm|task|diagnosis)-[a-z0-9-]{12,}\b/gi, "")
    .replace(/\b[a-f0-9]{8}-[a-f0-9-]{4}-[a-f0-9-]{4}-[a-f0-9-]{4}-[a-f0-9-]{12}\b/gi, "")
    .replace(/\s{2,}/g, " ")
    .replace(/\s*[·:：-]\s*$/g, "")
    .trim();
  if (cleaned && cleaned !== "复核") return fieldName ? `${cleaned} · ${fieldName}` : cleaned;
  return fieldName ? `${fieldName}风险复核` : "地块农事任务";
}

function statusLabel(status: OverviewOperationTask["status"]) {
  return status === "IN_PROGRESS" ? "执行中" : status === "OVERDUE" ? "已逾期" : status === "COMPLETED" ? "已完成" : "待处理";
}

type AgentActivityEvent = { key: string; title: string; detail: string; time?: string; state: string };

function availabilitySummary(overview: OverviewViewModel) {
  const statuses = overview.agricultural ? Object.values(overview.agricultural.availability) : [];
  const unavailable = statuses.filter((status) => ["UNAVAILABLE", "CONFIGURED_UNAVAILABLE", "NOT_CONFIGURED"].includes(status));
  const ready = statuses.filter((status) => status === "READY");
  if (overview.mode === "DEMO") return { label: "演示数据", tone: "default" as const };
  if (overview.mode === "UNAVAILABLE") return { label: "数据加载中", tone: "warning" as const };
  if (unavailable.length) return { label: `${unavailable.length} 项数据源待处理`, tone: "warning" as const };
  if (statuses.length) return { label: `${ready.length}/${statuses.length} 项数据就绪`, tone: "success" as const };
  return { label: "数据状态正常", tone: "success" as const };
}

function CommandSummary({ overview, onStartDiagnosis, onOpenDigitalFarm, onOpenOperations }: Pick<HomepageCommandCenterProps, "overview" | "onStartDiagnosis" | "onOpenDigitalFarm" | "onOpenOperations">) {
  const riskCount = overview.riskPriorities.filter((item) => item.signalType === "RISK").length;
  const pendingConfirmation = overview.operations.pendingApproval + overview.recommendations.filter((item) => item.decisionState === "PENDING_APPROVAL").length;
  const activeOperations = overview.operations.pendingExecution + overview.operations.inProgress + overview.operations.overdue;
  const health = availabilitySummary(overview);

  return (
    <section className="fm-command-summary" aria-labelledby="command-summary-title">
      <Flex className="fm-command-summary__header" justify="space-between" align="flex-start" gap={20} wrap>
        <div>
          <div className="fm-eyebrow">FARMMAP AGENT / COMMAND CENTER</div>
          <Title level={3} id="command-summary-title">今天的农场指挥台</Title>
          <Text type="secondary">Agent 持续汇总当前农场信号，把异常、调查和待执行事项排成下一步行动。</Text>
        </div>
        <Flex align="center" gap={8} wrap>
          <Tag className="fm-command-summary__farm"><EnvironmentOutlined /> {overview.farm.name}</Tag>
          <Tag color={health.tone} className="fm-command-summary__health"><DatabaseOutlined /> {health.label}</Tag>
        </Flex>
      </Flex>
      <div className="fm-command-summary__metrics">
        <div className={`fm-command-metric${riskCount ? " fm-command-metric--attention" : ""}`}>
          <span className="fm-command-metric__icon"><ThunderboltOutlined /></span>
          <span><b>{riskCount}</b><small>需要关注</small></span>
          <em>{riskCount ? "高优先级信号" : "暂无高优先级信号"}</em>
        </div>
        <div className="fm-command-metric">
          <span className="fm-command-metric__icon"><RobotOutlined /></span>
          <span><b>{overview.recentDiagnoses.length}</b><small>已完成调查</small></span>
          <em>已保存诊断</em>
        </div>
        <div className="fm-command-metric">
          <span className="fm-command-metric__icon"><ClockCircleOutlined /></span>
          <span><b>{pendingConfirmation}</b><small>等待确认</small></span>
          <em>{pendingConfirmation ? "需要人工确认" : "暂无待确认"}</em>
        </div>
        <div className="fm-command-metric">
          <span className="fm-command-metric__icon"><CheckCircleOutlined /></span>
          <span><b>{activeOperations}</b><small>活跃任务</small></span>
          <em>{activeOperations ? "待处理或执行中" : "当前无活跃任务"}</em>
        </div>
      </div>
      <Flex className="fm-command-summary__actions" align="center" gap={8} wrap>
        <Button type="primary" onClick={onStartDiagnosis}>发起 AI 调查</Button>
        <Button onClick={onOpenDigitalFarm}>查看数字农场</Button>
        <Button type="link" onClick={onOpenOperations}>查看待处理任务 <ArrowRightOutlined /></Button>
      </Flex>
    </section>
  );
}

function AgentActivity({ overview, onOpenAgentRuns, onStartDiagnosis }: Pick<HomepageCommandCenterProps, "overview" | "onOpenAgentRuns" | "onStartDiagnosis">) {
  const events = [
    ...(overview.mode !== "UNAVAILABLE" ? [{ key: "context", title: `读取 ${overview.farm.name} 农场上下文`, detail: `${overview.farm.fieldCount} 个地块 · 当前天气 · 农场数据`, time: overview.freshness.find((item) => item.source === "farm")?.updatedAt, state: "已完成" }] : []),
    ...overview.recentDiagnoses.slice(0, 2).map((diagnosis) => ({ key: diagnosis.id, title: `完成 ${diagnosis.fieldName} 诊断`, detail: diagnosis.confidence !== undefined ? `${diagnosis.statusLabel} · 置信度 ${diagnosis.confidence}%` : diagnosis.statusLabel, time: diagnosis.observedAt, state: "已完成" })),
    ...overview.operations.tasks.slice(0, 2).map((task) => ({ key: task.id, title: `记录农事任务 · ${cleanTaskTitle(task.name, task.fieldName)}`, detail: `${task.sourceLabel ?? "农场运营"} · ${statusLabel(task.status)}`, time: task.updatedAt ?? task.time, state: statusLabel(task.status) })),
  ] satisfies AgentActivityEvent[];
  const visibleEvents = events.slice(0, 4);

  return (
    <section className="fm-agent-activity" aria-labelledby="agent-activity-title">
      <Flex className="fm-overview-section-header" justify="space-between" align="flex-end" gap={16} wrap>
        <div><div className="fm-eyebrow">AGENT ACTIVITY</div><Title level={3} id="agent-activity-title">FarMap Agent 动态</Title><Text type="secondary">查看 Agent 最近读取、调查和记录的真实活动。</Text></div>
        <Button type="link" onClick={onOpenAgentRuns}>查看全部 Agent Runs <ArrowRightOutlined /></Button>
      </Flex>
      {visibleEvents.length ? (
        <div className="fm-agent-activity__list">
          {visibleEvents.map((event) => <div className="fm-agent-activity__item" key={event.key}>
            <span className="fm-agent-activity__mark"><CheckCircleOutlined /></span>
            <span className="fm-agent-activity__main"><b>{event.title}</b><small>{event.detail}</small></span>
            <span className="fm-agent-activity__state">{event.state}</span>
            <time>{formatActivityTime(event.time)}</time>
          </div>)}
        </div>
      ) : (
        <div className="fm-compact-empty" role="status"><RobotOutlined /><span>Agent 当前没有新的可展示活动</span><Button type="link" size="small" onClick={onStartDiagnosis}>开始一次 AI 调查 <ArrowRightOutlined /></Button></div>
      )}
    </section>
  );
}

function FarmSnapshot({ overview, onOpenDiagnosis, onOpenDigitalFarm }: Pick<HomepageCommandCenterProps, "overview" | "onOpenDiagnosis" | "onOpenDigitalFarm">) {
  const weatherReady = overview.weather.status === "READY";
  const observations = overview.agricultural?.cropObservations.totalObservations;
  const historical = overview.agricultural?.historicalTemperature.observationCount;
  const latestDiagnosis = overview.recentDiagnoses[0];
  const ndviTrend = overview.trends.find((trend) => trend.id === "ndvi");
  const hasNdvi = Boolean(ndviTrend && (ndviTrend.points.length > 0 || ndviTrend.currentValue !== "—"));

  return (
    <Card variant="borderless" className="fm-farm-snapshot-card" title={<span><span className="fm-eyebrow">FARM SNAPSHOT</span><b>农场快照</b></span>} extra={<Button type="link" size="small" onClick={onOpenDigitalFarm}>详情 <ArrowRightOutlined /></Button>}>
      <div className="fm-farm-snapshot__identity"><EnvironmentOutlined /><span><b>{overview.farm.name}</b><small>{overview.farm.location} · {overview.farm.fieldCount} 个地块 · {overview.farm.acreage.toFixed(1)} ha</small></span></div>
      <div className="fm-farm-snapshot__metrics">
        <div><CloudOutlined /><small>当前天气</small><b>{weatherReady ? overview.weather.temperature : "—"}</b><span>{weatherReady ? overview.weather.summary : overview.weather.reason ?? "暂不可用"}</span></div>
        <div><FileSearchOutlined /><small>有效观测</small><b>{observations ?? "—"}</b><span>{observations !== undefined ? "条真实农情记录" : "暂无可验证数据"}</span></div>
        <div><ClockCircleOutlined /><small>历史记录</small><b>{historical ?? "—"}</b><span>{historical !== undefined ? "条温度观测" : "暂无可验证数据"}</span></div>
        <div><RobotOutlined /><small>最近诊断</small><b>{overview.recentDiagnoses.length}</b><span>{latestDiagnosis ? <button type="button" onClick={() => onOpenDiagnosis(latestDiagnosis.id)}>{latestDiagnosis.fieldName} · 查看详情</button> : "暂无诊断快照"}</span></div>
      </div>
      {hasNdvi && ndviTrend && <div className="fm-farm-snapshot__ndvi"><span><b>{ndviTrend.currentValue}</b><small>{ndviTrend.label}</small></span><span>{ndviTrend.changeLabel}</span><span>近 1 个月有真实趋势数据</span></div>}
      <div className="fm-farm-snapshot__footnote"><DatabaseOutlined /> 详情数据已下沉到数字农场；首页仅保留影响下一步行动的摘要。</div>
    </Card>
  );
}

export default function HomepageCommandCenter({
  overview,
  monitor,
  onStartDiagnosis,
  onOpenDiagnosis,
  onOpenAgentRuns,
  onOpenDigitalFarm,
  onOpenOperations,
  onDecisionPrimaryAction,
  onDecisionAskAI,
  onRiskSelect,
  selectedRiskId,
  onOperationOpen,
}: HomepageCommandCenterProps) {
  const navigate = useNavigate();
  const diagnosisCount = overview.recentDiagnoses.length;

  return (
    <div className="fm-homepage-command-center">
      <CommandSummary overview={overview} onStartDiagnosis={onStartDiagnosis} onOpenDigitalFarm={onOpenDigitalFarm} onOpenOperations={onOpenOperations} />
      <DecisionQueue items={overview.decisionQueue} mode={overview.mode} freshness={overview.freshness} onPrimaryAction={onDecisionPrimaryAction} onAskAI={onDecisionAskAI} />
      <AgentActivity overview={overview} onOpenAgentRuns={onOpenAgentRuns} onStartDiagnosis={onStartDiagnosis} />
      <RiskIntelligenceBoard risks={overview.riskPriorities} map={overview.map} mode={overview.mode} freshness={overview.freshness} selectedRiskId={selectedRiskId} onRiskSelect={onRiskSelect} />
      <OperationsStatus operations={overview.operations} mode={overview.mode} freshness={overview.freshness} onOpenTask={onOperationOpen} />
      <section className="fm-overview-snapshot-area" aria-labelledby="farm-snapshot-area-title">
        <Flex className="fm-overview-section-header" justify="space-between" align="flex-end" gap={16} wrap>
          <div><div className="fm-eyebrow">FARM SNAPSHOT / MONITORING</div><Title level={3} id="farm-snapshot-area-title">农场快照与监测</Title><Text type="secondary">保留天气、农情摘要和多路实时摄像头；详细数据请进入对应专业页面。</Text></div>
          <span className="fm-overview-section-header__meta">{diagnosisCount ? `${diagnosisCount} 条近期诊断` : "暂无近期诊断"}</span>
        </Flex>
        <div className="fm-overview-snapshot-area__grid">
          <FarmSnapshot overview={overview} onOpenDiagnosis={onOpenDiagnosis} onOpenDigitalFarm={onOpenDigitalFarm} />
          <div className="fm-overview-monitor-wrap">{monitor}</div>
        </div>
      </section>
      <div className="fm-overview-footer-note"><EnvironmentOutlined /> {overview.farm.name} · {overview.farm.location} · 数据更新于 {overview.farm.lastUpdated}<Button type="link" size="small" onClick={() => navigate("/farm/map")}>打开农场地图</Button></div>
    </div>
  );
}

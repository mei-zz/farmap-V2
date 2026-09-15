import { ArrowRightOutlined, CalendarOutlined, CheckCircleOutlined, ClockCircleOutlined, EnvironmentOutlined, InfoCircleOutlined, RobotOutlined, ThunderboltOutlined, WarningOutlined } from "@ant-design/icons";
import { Button, Card, Empty, Flex, Space, Tag, Typography } from "antd";
import type { OperationsTaskViewModel, OperationsViewModel } from "@/types/operations";

const { Text, Title } = Typography;

type OperationsCenterProps = {
  viewModel: OperationsViewModel;
  onSelectTask: (task: OperationsTaskViewModel) => void;
  onAskAi: (task: OperationsTaskViewModel) => void;
  onOpenDiagnosis: (diagnosisId: string) => void;
  onOpenAgent: (runId: string) => void;
};

function modeLabel(mode: OperationsViewModel["mode"]): string {
  return mode === "DEMO" ? "DEMO TASKS" : mode === "REAL" ? "REAL OPERATIONS" : mode;
}

function modeColor(mode: OperationsViewModel["mode"]): string {
  return mode === "DEMO" ? "gold" : mode === "REAL" ? "blue" : mode === "UNAVAILABLE" ? "red" : "default";
}

function priorityColor(priority: OperationsTaskViewModel["priority"]): string {
  return priority === "HIGH" ? "red" : priority === "MEDIUM" ? "orange" : priority === "LOW" ? "blue" : "default";
}

function taskIcon(task: OperationsTaskViewModel) {
  if (task.priority === "HIGH") return <WarningOutlined />;
  if (task.source === "AI") return <RobotOutlined />;
  return <CalendarOutlined />;
}

export function OperationsHeader({ viewModel }: { viewModel: OperationsViewModel }) {
  const { summary } = viewModel;
  return (
    <div className="fm-operations-command-header">
      <Flex justify="space-between" align="flex-start" gap={18} wrap>
        <div>
          <div className="fm-eyebrow">AGRICULTURAL EXECUTION WORKSPACE</div>
          <Title level={2}>农业运营中心</Title>
          <Text type="secondary">把 AI 建议、人工请求和现场农事任务放在同一个可追踪的执行空间。</Text>
        </div>
        <Space wrap>
          <Tag color={modeColor(viewModel.mode)}>{modeLabel(viewModel.mode)}</Tag>
          {viewModel.freshness.updatedAt && <Tag icon={<ClockCircleOutlined />}>{viewModel.freshness.stale ? "数据较旧" : `更新于 ${viewModel.freshness.updatedAt}`}</Tag>}
        </Space>
      </Flex>
      <div className="fm-operations-command-header__context"><span><b>当前农场</b>{viewModel.farm.name}</span><span><b>运营态势</b>{summary.active} 项记录</span><span><b>执行边界</b>只展示已有真实任务状态</span></div>
    </div>
  );
}

export function OperationsOverview({ viewModel }: { viewModel: OperationsViewModel }) {
  const metrics = [
    { label: "待处理", value: viewModel.mode === "UNAVAILABLE" ? "—" : viewModel.summary.pending, icon: <ClockCircleOutlined />, tone: "pending" },
    { label: "执行中", value: viewModel.mode === "UNAVAILABLE" || viewModel.summary.inProgress === undefined ? "—" : viewModel.summary.inProgress, icon: <ThunderboltOutlined />, tone: "active" },
    { label: "已逾期", value: viewModel.mode === "UNAVAILABLE" || viewModel.summary.overdue === undefined ? "—" : viewModel.summary.overdue, icon: <WarningOutlined />, tone: "overdue" },
  ];
  return <Card bordered={false} className="fm-operations-card fm-operations-overview" title={<Flex justify="space-between" align="center"><span>当前运营态势</span><Text type="secondary">Operations overview</Text></Flex>}><div className="fm-operations-overview__metrics">{metrics.map((metric) => <div className={`fm-operations-overview__metric fm-operations-overview__metric--${metric.tone}`} key={metric.label}>{metric.icon}<div><b>{metric.value}</b><span>{metric.label}</span></div></div>)}</div><Text type="secondary">当前页面只呈现 API 返回的任务状态；没有后端字段的负责人、截止时间、进度和执行结果不会被前端补造。</Text></Card>;
}

function TaskQueueItem({ task, selected, onSelect }: { task: OperationsTaskViewModel; selected: boolean; onSelect: () => void }) {
  return <button type="button" className={`fm-operations-task-item${selected ? " is-selected" : ""}`} onClick={onSelect} aria-pressed={selected}>
    <div className="fm-operations-task-item__top"><span className="fm-operations-task-item__icon">{taskIcon(task)}</span><Tag color={priorityColor(task.priority)}>{task.priorityLabel}</Tag><Tag>{task.statusLabel}</Tag></div>
    <b>{task.title}</b>
    <div className="fm-operations-task-item__meta">{task.fieldId && <span><EnvironmentOutlined /> {task.fieldId}</span>}<span>来源 · {task.sourceLabel}</span></div>
    {task.createdAt && <small><ClockCircleOutlined /> {task.createdAt}</small>}
  </button>;
}

export function OperationsQueue({ viewModel, onSelectTask }: { viewModel: OperationsViewModel; onSelectTask: (task: OperationsTaskViewModel) => void }) {
  return <Card bordered={false} className="fm-operations-card fm-operations-queue" title={<Flex justify="space-between" align="center"><span>任务队列</span><Text type="secondary">{viewModel.tasks.length} 项</Text></Flex>}>
    {viewModel.mode === "UNAVAILABLE" ? <div className="fm-operations-state fm-operations-state--unavailable"><InfoCircleOutlined /><div><b>任务服务暂不可用</b><span>{viewModel.unavailableReason ?? "当前无法确认实时任务状态。"}</span></div></div> : viewModel.tasks.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={viewModel.mode === "EMPTY" ? "当前没有真实农事任务" : "当前没有演示任务"} /> : <div className="fm-operations-task-list">{viewModel.tasks.map((task) => <TaskQueueItem key={task.key} task={task} selected={viewModel.selectedTask?.key === task.key} onSelect={() => onSelectTask(task)} />)}</div>}
  </Card>;
}

function TraceLink({ label, value, onClick }: { label: string; value: string; onClick?: () => void }) {
  return <div className="fm-operations-trace-row"><span>{label}</span>{onClick ? <Button type="link" size="small" onClick={onClick}>{value}<ArrowRightOutlined /></Button> : <b>{value}</b>}</div>;
}

export function OperationsTaskDetail({ viewModel, onAskAi, onOpenDiagnosis, onOpenAgent }: { viewModel: OperationsViewModel; onAskAi: (task: OperationsTaskViewModel) => void; onOpenDiagnosis: (diagnosisId: string) => void; onOpenAgent: (runId: string) => void }) {
  const task = viewModel.selectedTask;
  if (!task) return <Card bordered={false} className="fm-operations-card fm-operations-detail"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择一个任务查看上下文" /></Card>;
  return <Card bordered={false} className="fm-operations-card fm-operations-detail" title={<Flex justify="space-between" align="center"><span>任务详情</span><Tag color={task.isDemo ? "gold" : "blue"}>{task.isDemo ? "DEMO TASK" : "REAL TASK"}</Tag></Flex>}>
    <div className="fm-operations-detail__title"><div className="fm-eyebrow">AGRICULTURAL TASK</div><Title level={3}>{task.title}</Title><Space wrap><Tag color={priorityColor(task.priority)}>{task.priorityLabel}</Tag><Tag>{task.statusLabel}</Tag><Tag>{task.sourceLabel}</Tag></Space></div>
    <div className="fm-operations-detail__field"><EnvironmentOutlined /><div><small>执行地块</small><b>{task.fieldName ?? task.fieldId ?? "任务未返回地块"}</b>{task.fieldId && <Text type="secondary">Field ID · {task.fieldId}</Text>}</div></div>
    <div className="fm-operations-detail__why"><b>为什么创建这项任务？</b><p>{task.goal ?? (task.isDemo ? "当前为演示任务，未返回真实诊断或 Agent 关联。" : "后端没有返回任务创建原因。请以任务标题和来源字段为准。")}</p></div>
    <div className="fm-operations-detail__section"><b>来源与追踪</b><div className="fm-operations-trace"><TraceLink label="来源" value={task.sourceLabel} />{task.traceability.diagnosisId && <TraceLink label="关联诊断" value={task.traceability.diagnosisId} onClick={() => onOpenDiagnosis(task.traceability.diagnosisId as string)} />}{task.traceability.agentRunId && <TraceLink label="AI 分析" value={task.traceability.agentRunId} onClick={() => onOpenAgent(task.traceability.agentRunId as string)} />}{task.traceability.evidenceCount !== undefined && <TraceLink label="Evidence" value={`${task.traceability.evidenceCount} 条关联 Evidence`} />}{task.approvedAt && <TraceLink label="批准时间" value={task.approvedAt} />}</div></div>
    <div className="fm-operations-detail__section"><b>执行结果</b><div className="fm-operations-result"><InfoCircleOutlined /><span>{task.outcome?.summary ?? "No outcome recorded yet"}</span></div>{task.assignee && <Text type="secondary">负责人 · {task.assignee}</Text>}{task.startedAt && <Text type="secondary">开始时间 · {task.startedAt}</Text>}{task.completedAt && <Text type="secondary">完成时间 · {task.completedAt}</Text>}{task.cancelledReason && <Text type="secondary">取消原因 · {task.cancelledReason}</Text>}</div>
    <div className="fm-operations-detail__actions"><Button icon={<RobotOutlined />} onClick={() => onAskAi(task)}>Ask AI 解释此任务</Button><Text type="secondary">只读工作区 · 不执行任务状态变更</Text></div>
  </Card>;
}

export default function OperationsCenter({ viewModel, onSelectTask, onAskAi, onOpenDiagnosis, onOpenAgent }: OperationsCenterProps) {
  return <div className="fm-operations-center"><OperationsHeader viewModel={viewModel} /><OperationsOverview viewModel={viewModel} /><div className="fm-operations-center__workspace"><OperationsQueue viewModel={viewModel} onSelectTask={onSelectTask} /><OperationsTaskDetail viewModel={viewModel} onAskAi={onAskAi} onOpenDiagnosis={onOpenDiagnosis} onOpenAgent={onOpenAgent} /></div><div className="fm-operations-center__notice"><CheckCircleOutlined /> Operations 当前处于只读产品化阶段；不会从此页面创建、批准、开始、完成、取消或重新分配任务。</div></div>;
}

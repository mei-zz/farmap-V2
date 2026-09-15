import { AlertOutlined, CheckCircleOutlined, ClockCircleOutlined, InfoCircleOutlined } from "@ant-design/icons";
import { Flex, Typography } from "antd";
import type { OverviewFreshness, OverviewMode, OverviewOperationTask, OverviewOperations } from "@/types/overview";
import OperationStatusItem from "./OperationStatusItem";

const { Text, Title } = Typography;

const priorityRank: Record<OverviewOperationTask["priority"], number> = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
};

function StateMessage({ unavailable }: { unavailable: boolean }) {
  if (unavailable) {
    return <div className="fm-operations-status__state-message" role="status"><InfoCircleOutlined /><div><b>执行数据暂不可用</b><span>当前无法确认农事行动状态，请前往 Operations 查看实时任务。</span></div></div>;
  }
  return <div className="fm-operations-status__state-message" role="status"><CheckCircleOutlined /><div><b>当前暂无已记录的农事行动</b><span>当建议被记录为任务后，执行状态会在这里汇总。</span></div></div>;
}

export default function OperationsStatus({
  operations,
  mode,
  freshness,
  onOpenTask,
}: {
  operations: OverviewOperations;
  mode: OverviewMode;
  freshness: OverviewFreshness[];
  onOpenTask: (task: OverviewOperationTask) => void;
}) {
  const operationFreshness = freshness.find((item) => item.source === "operations") ?? freshness[0];
  const visibleTasks = [...operations.tasks]
    .sort((a, b) => priorityRank[a.priority] - priorityRank[b.priority])
    .slice(0, 3);
  const unavailable = mode === "UNAVAILABLE" || operations.status === "UNAVAILABLE";
  const hasTasks = visibleTasks.length > 0;

  return (
    <section className={`fm-operations-status fm-operations-status--${mode.toLowerCase()}`} aria-labelledby="operations-status-title">
      <Flex className="fm-operations-status__header" justify="space-between" align="flex-end" gap={16} wrap>
        <div>
          <div className="fm-eyebrow">OPERATIONS STATUS</div>
          <Flex align="center" gap={8}>
            <Title level={3} id="operations-status-title">运营执行态势</Title>
            {mode === "DEMO" && <span className="fm-operations-status__count">演示</span>}
          </Flex>
          <Text type="secondary">查看建议之后的农业行动状态，明确哪些事项仍需要现场处理。</Text>
        </div>
        {mode !== "UNAVAILABLE" && operationFreshness?.updatedAt && <span className="fm-operations-status__freshness">{mode === "STALE" ? "执行记录较旧" : mode === "DEMO" ? "演示记录" : "执行记录更新时间"} · {operationFreshness.updatedAt}</span>}
      </Flex>

      <div className="fm-operations-status__metrics" aria-label="运营状态汇总">
        <div className="fm-operations-status__metric"><ClockCircleOutlined /><span><b>{unavailable ? "—" : operations.pendingExecution}</b> 待处理</span></div>
        <div className="fm-operations-status__metric"><AlertOutlined /><span><b>{unavailable ? "—" : operations.inProgress}</b> 执行中</span></div>
        <div className="fm-operations-status__metric"><CheckCircleOutlined /><span><b>{unavailable ? "—" : operations.overdue}</b> 已逾期</span></div>
      </div>

      {unavailable || !hasTasks ? <StateMessage unavailable={unavailable} /> : (
        <div className="fm-operations-status__items">
          {visibleTasks.map((task) => <OperationStatusItem key={task.id} task={task} mode={mode} onOpenTask={onOpenTask} />)}
        </div>
      )}

      <div className="fm-operations-status__footnote">
        {mode === "DEMO" ? "当前演示任务未连接实时执行状态，也未与 AI 建议建立可靠 ID 关系。" : mode === "STALE" ? "请先核对更新时间，再据此安排现场行动。" : "状态来自 Operations 任务记录；未提供的负责人、进度和时间线不会被推断。"}
      </div>
    </section>
  );
}

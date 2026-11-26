import { useState, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import { Alert, Button, Empty, Space, Tabs, Tag } from "antd";
import { RobotOutlined } from "@ant-design/icons";
import PlanningRecommendations from "@/features/pro-workspaces/PlanningRecommendations";
import PlanningCalendar from "@/features/pro-workspaces/PlanningCalendar";
import type {
  OperationsTaskViewModel,
  OperationsViewModel,
} from "@/types/operations";

type OperationsCenterProps = {
  viewModel: OperationsViewModel;
  guidanceSection?: ReactNode;
  onSelectTask: (task: OperationsTaskViewModel) => void;
  onAskAi: (task: OperationsTaskViewModel) => void;
  onOpenDiagnosis: (diagnosisId: string) => void;
  onOpenAgent: (runId: string) => void;
  onOpenAnalysis?: (runId: string, fieldId?: string) => void;
  onPlanInAgent?: () => void;
};

type Filter = "all" | "pending" | "active" | "completed";
const filters: { key: Filter; label: string }[] = [
  { key: "all", label: "全部" },
  { key: "pending", label: "待执行" },
  { key: "active", label: "执行中" },
  { key: "completed", label: "已完成" },
];

function matches(task: OperationsTaskViewModel, filter: Filter) {
  if (filter === "pending")
    return task.status === "OPEN" || task.status === "ASSIGNED";
  if (filter === "active") return task.status === "IN_PROGRESS";
  if (filter === "completed") return task.status === "COMPLETED";
  return true;
}

function TaskDetail({
  task,
  onAskAi,
  onOpenDiagnosis,
  onOpenAgent,
  onOpenAnalysis,
}: {
  task: OperationsTaskViewModel;
  onAskAi: OperationsCenterProps["onAskAi"];
  onOpenDiagnosis: OperationsCenterProps["onOpenDiagnosis"];
  onOpenAgent: OperationsCenterProps["onOpenAgent"];
  onOpenAnalysis?: OperationsCenterProps["onOpenAnalysis"];
}) {
  return (
    <aside className="fm-plan-detail">
      <div className="fm-plan-detail__heading">
        <div>
          <small>任务详情</small>
          <h3>{task.title}</h3>
        </div>
        <Tag>{task.statusLabel}</Tag>
      </div>
      <dl className="fm-plan-detail__facts">
        <div>
          <dt>来源</dt>
          <dd>{task.sourceLabel}</dd>
        </div>
        <div>
          <dt>地块</dt>
          <dd>
            {task.fieldName && task.fieldName !== `${task.fieldId} 地块`
              ? task.fieldName
              : "未提供地块名称"}
          </dd>
        </div>
        {task.assignee && (
          <div>
            <dt>负责人</dt>
            <dd>{task.assignee}</dd>
          </div>
        )}
        {task.scheduledFor && <div><dt>计划日期</dt><dd>{task.scheduledFor}</dd></div>}
        {task.startedAt && (
          <div>
            <dt>开始时间</dt>
            <dd>{task.startedAt}</dd>
          </div>
        )}
        {task.completedAt && (
          <div>
            <dt>完成时间</dt>
            <dd>{task.completedAt}</dd>
          </div>
        )}
      </dl>
      {task.goal && (
        <section>
          <h4>任务目标</h4>
          <p>{task.goal}</p>
        </section>
      )}
      {task.outcome?.summary && (
        <section>
          <h4>执行结果</h4>
          <p>{task.outcome.summary}</p>
        </section>
      )}
      {task.cancelledReason && (
        <section>
          <h4>取消原因</h4>
          <p>{task.cancelledReason}</p>
        </section>
      )}
      <details className="fm-plan-provenance">
        <summary>来源与追踪</summary>
        {task.fieldId && <p>地块编号：{task.fieldId}</p>}
        {task.taskId && <p>任务编号：{task.taskId}</p>}
        {task.traceability.evidenceCount !== undefined && (
          <p>关联依据：{task.traceability.evidenceCount} 条</p>
        )}
        {task.approvedAt && <p>批准时间：{task.approvedAt}</p>}
        {task.traceability.diagnosisId && (
          <Button
            type="link"
            onClick={() =>
              onOpenDiagnosis(task.traceability.diagnosisId as string)
            }
          >
            查看关联诊断
          </Button>
        )}
        {task.traceability.agentRunId && (
          <Button
            type="link"
            onClick={() => onOpenAgent(task.traceability.agentRunId as string)}
          >
            查看关联分析
          </Button>
        )}
        {task.traceability.analysisRunId && onOpenAnalysis && (
          <Button type="link" onClick={() => onOpenAnalysis(task.traceability.analysisRunId!, task.fieldId)}>查看关联观测报告</Button>
        )}
      </details>
      <Button icon={<RobotOutlined />} onClick={() => onAskAi(task)}>
        在 Agent 中打开任务
      </Button>
    </aside>
  );
}

export default function OperationsCenter({
  viewModel,
  guidanceSection,
  onSelectTask,
  onAskAi,
  onOpenDiagnosis,
  onOpenAgent,
  onOpenAnalysis,
  onPlanInAgent,
}: OperationsCenterProps) {
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState<Filter>("all");
  const requestedView = params.get("view") || "board";
  const activeView = [
    "board",
    "calendar",
    "records",
    "recommendations",
  ].includes(requestedView)
    ? requestedView
    : "board";
  const completed = viewModel.tasks.filter(
    (task) => task.status === "COMPLETED",
  );
  const visible = viewModel.tasks.filter((task) => matches(task, filter));
  const switchView = (view: string) => {
    const next = new URLSearchParams(params);
    next.set("view", view);
    setParams(next);
  };
  const board = (
    <section className="fm-plan-board">
      <div className="fm-plan-filters" aria-label="任务状态筛选">
        {filters.map((item) => (
          <button
            key={item.key}
            type="button"
            className={filter === item.key ? "is-active" : ""}
            aria-pressed={filter === item.key}
            onClick={() => setFilter(item.key)}
          >
            {item.label}
            <span>
              {viewModel.tasks.filter((task) => matches(task, item.key)).length}
            </span>
          </button>
        ))}
      </div>
      {viewModel.mode === "UNAVAILABLE" ? (
        <Alert
          showIcon
          type="warning"
          title="任务暂不可用"
          description={viewModel.unavailableReason}
        />
      ) : visible.length === 0 ? (
        <Empty
          className="fm-plan-empty"
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            viewModel.tasks.length
              ? "当前状态下没有任务"
              : "当前农场暂无农事任务"
          }
        />
      ) : (
        <div
          className={`fm-plan-board__content${viewModel.selectedTask ? " has-detail" : ""}`}
        >
          <div className="fm-plan-list">
            {visible.map((task) => (
              <button
                key={task.key}
                type="button"
                className={`fm-plan-row${viewModel.selectedTask?.key === task.key ? " is-selected" : ""}`}
                onClick={() => onSelectTask(task)}
              >
                <span>
                  <b>{task.title}</b>
                  <small>
                    {task.sourceLabel}
                    {task.createdAt ? ` · ${task.createdAt}` : ""}
                  </small>
                </span>
                <Tag>{task.statusLabel}</Tag>
              </button>
            ))}
          </div>
          {viewModel.selectedTask && (
            <TaskDetail
              task={viewModel.selectedTask}
              onAskAi={onAskAi}
              onOpenDiagnosis={onOpenDiagnosis}
              onOpenAgent={onOpenAgent}
              onOpenAnalysis={onOpenAnalysis}
            />
          )}
        </div>
      )}
    </section>
  );
  return (
    <div className="fm-operations-center">
      <header className="fm-pro-heading">
        <div>
          <span>FARM OPERATIONS</span>
          <h1>农事计划</h1>
          <p>查看已经安排的农事、计划日期和执行记录。</p>
        </div>
        {onPlanInAgent && <Button type="primary" icon={<RobotOutlined />} onClick={onPlanInAgent}>在 Agent 中制定计划</Button>}
      </header>
      <Tabs
        activeKey={activeView}
        onChange={switchView}
        items={[
          { key: "board", label: "农事看板", children: board },
          {
            key: "recommendations",
            label: "农事建议",
            children: (
              <>
                <PlanningRecommendations farmId={viewModel.farm.id} />
                {guidanceSection}
              </>
            ),
          },
          {
            key: "calendar",
            label: "农事日历",
            children: (
              <PlanningCalendar
                tasks={viewModel.tasks}
                onSelect={(task) => {
                  onSelectTask(task);
                  switchView("board");
                }}
              />
            ),
          },
          {
            key: "records",
            label: "执行记录",
            children: completed.length ? (
              <div className="fm-plan-records">
                {completed.map((task) => (
                  <div className="fm-pro-source" key={task.key}>
                    <div>
                      <b>{task.title}</b>
                      {task.outcome?.summary && <p>{task.outcome.summary}</p>}
                    </div>
                    <span>{task.completedAt || task.statusLabel}</span>
                  </div>
                ))}
              </div>
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无已完成任务"
              />
            ),
          },
        ]}
      />
      <Space />
    </div>
  );
}

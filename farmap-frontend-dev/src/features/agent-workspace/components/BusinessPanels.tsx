import {
  ArrowRightOutlined,
  CheckCircleFilled,
  CheckOutlined,
  ClockCircleOutlined,
  FileSearchOutlined,
  PauseCircleOutlined,
  RobotOutlined,
  StopOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { Button, Card, Empty, Flex, Space, Tag, Typography } from "antd";
import type {
  AgentProgressStatus,
  AgentWorkspaceAction,
  AgentWorkspaceFinding,
  AgentWorkspaceStep,
  AgentWorkspaceViewModel,
} from "@/types/agent-workspace";

const { Text, Title } = Typography;

type HeaderProps = {
  viewModel: AgentWorkspaceViewModel;
  onBackDiagnosis: () => void;
  onStart: () => void;
  onCancel: () => void;
  onRetry: () => void;
  onAskAi: () => void;
};

function modeLabel(mode: AgentWorkspaceViewModel["mode"]): string {
  return mode === "REAL" ? "真实 Agent" : mode === "DEMO" ? "流程演示" : mode;
}

function statusColor(status: AgentWorkspaceViewModel["status"]): string {
  return status === "FAILED" ? "red" : status === "WAITING_APPROVAL" ? "orange" : status === "COMPLETED" ? "green" : status === "CANCELLED" ? "default" : "blue";
}

export function AgentWorkspaceHeader({ viewModel, onBackDiagnosis, onStart, onCancel, onRetry, onAskAi }: HeaderProps) {
  return (
    <div className="fm-agent-command-header">
      <Flex justify="space-between" align="flex-start" gap={18} wrap>
        <div>
          <Button type="link" icon={<ArrowRightOutlined rotate={180} />} onClick={onBackDiagnosis} className="fm-agent-back-link">返回调查入口</Button>
          <div className="fm-eyebrow">多模态农业智能体</div>
          <Title level={2}>农业 Agent 调查台</Title>
          <Text type="secondary">围绕一个农业问题，自动读取数据、检索知识、融合证据并形成建议。</Text>
        </div>
        <Space wrap>
          <Tag color={viewModel.mode === "DEMO" ? "gold" : "blue"}>{modeLabel(viewModel.mode)}</Tag>
          <Tag color={statusColor(viewModel.status)} icon={viewModel.status === "ANALYZING" ? <PauseCircleOutlined /> : viewModel.status === "COMPLETED" ? <CheckCircleFilled /> : viewModel.status === "FAILED" ? <WarningOutlined /> : <ClockCircleOutlined />}>{viewModel.statusLabel}</Tag>
          {viewModel.canCancel && <Button icon={<StopOutlined />} onClick={onCancel}>取消分析</Button>}
          {viewModel.canRetry && <Button onClick={onRetry}>重新分析</Button>}
          {viewModel.status === "READY" && <Button type="primary" icon={<RobotOutlined />} onClick={onStart}>开始分析</Button>}
          {viewModel.status === "COMPLETED" && viewModel.mode === "DEMO" && <Button type="primary" icon={<RobotOutlined />} onClick={onStart}>重新分析</Button>}
          <Button onClick={onAskAi}>追问本次分析</Button>
        </Space>
      </Flex>
      <div className="fm-agent-command-header__context">
        <span><b>农场</b>{viewModel.farm.name}</span>
        <span><b>当前问题</b>{viewModel.diagnosis?.title ?? viewModel.goal}</span>
        <span><b>目标</b>{viewModel.goal}</span>
        <span><b>更新时间</b>{viewModel.freshness.updatedAt}</span>
      </div>
      {viewModel.statusDetail && <div className="fm-agent-command-header__status"><ClockCircleOutlined /> {viewModel.statusDetail}</div>}
    </div>
  );
}

export function MultimodalOverviewPanel({ viewModel }: { viewModel: AgentWorkspaceViewModel }) {
  const available = viewModel.dataSources.filter((source) => source.status === "AVAILABLE");
  const knowledgeCount = viewModel.evidence.filter((item) => item.category.includes("知识")).length;
  const caseCount = viewModel.evidence.filter((item) => item.category.includes("案例") || item.category.includes("历史")).length;
  return (
    <Card bordered={false} className="fm-agent-business-card fm-agent-multimodal-overview" title={<Flex justify="space-between" align="center"><span>多模态调查概览</span><Tag color={viewModel.status === "COMPLETED" ? "green" : "blue"}>{available.length}/{viewModel.dataSources.length} 类数据已接入</Tag></Flex>}>
      <div className="fm-agent-multimodal-overview__lead">
        <div><b>{viewModel.status === "COMPLETED" ? "证据融合已完成" : "Agent 正在组织调查"}</b><span>现场图像、地块、天气、农业知识和历史案例进入同一条可追溯链路。</span></div>
        <div className="fm-agent-multimodal-overview__metrics"><span><b>{viewModel.images.length}</b>张图像</span><span><b>{knowledgeCount}</b>条知识命中</span><span><b>{caseCount}</b>条相似案例</span><span><b>{viewModel.evidence.length}</b>条总证据</span></div>
      </div>
      {viewModel.images.length > 0 && <div className="fm-agent-multimodal-overview__images">{viewModel.images.slice(0, 5).map((url, index) => <div key={`${url}-${index}`}><img src={url} alt={`本次调查现场图像 ${index + 1}`} /><span>视觉证据 {index + 1}</span></div>)}</div>}
      <div className="fm-agent-multimodal-overview__sources">
        {viewModel.dataSources.map((source) => <div key={source.id} className={`is-${source.status.toLowerCase()}`}><CheckCircleFilled /><span><b>{source.label}</b><small>{source.countLabel}</small></span><Tag color={source.status === "AVAILABLE" ? "green" : source.status === "EMPTY" ? "gold" : undefined}>{source.status === "AVAILABLE" ? "已使用" : source.status === "EMPTY" ? "暂无结果" : "未接入"}</Tag></div>)}
      </div>
    </Card>
  );
}

function progressIcon(status: AgentProgressStatus) {
  if (status === "COMPLETED") return <CheckCircleFilled />;
  if (status === "ACTIVE") return <PauseCircleOutlined />;
  if (status === "FAILED") return <WarningOutlined />;
  if (status === "UNAVAILABLE") return <span className="fm-agent-progress__dash">—</span>;
  return <span className="fm-agent-progress__number">·</span>;
}

function sourceStatusLabel(status?: AgentWorkspaceStep["sourceStatus"]): string | undefined {
  if (status === "READY") return "已接入";
  if (status === "READY_EMPTY") return "暂无结果";
  if (status === "UNAVAILABLE") return "暂不可用";
  if (status === "FAILED") return "失败";
  return undefined;
}

function sourceStatusColor(status?: AgentWorkspaceStep["sourceStatus"]): string | undefined {
  if (status === "READY") return "green";
  if (status === "READY_EMPTY") return "gold";
  if (status === "UNAVAILABLE") return "default";
  if (status === "FAILED") return "red";
  return undefined;
}

export function AnalysisProgress({ steps }: { steps: AgentWorkspaceStep[] }) {
  return (
    <Card bordered={false} className="fm-agent-business-card fm-agent-progress-card" title={<Flex justify="space-between" align="center"><span>Agent 实时执行</span><Text type="secondary">展示工具调用与决策阶段</Text></Flex>}>
      <div className="fm-agent-progress-list">
        {steps.map((step, index) => (
          <div className={`fm-agent-progress-row fm-agent-progress-row--${step.status.toLowerCase()}`} key={step.id}>
            <span className="fm-agent-progress__icon">{progressIcon(step.status)}</span>
            <div className="fm-agent-progress__copy"><small>阶段 {String(index + 1).padStart(2, "0")}</small><b>{step.title}</b><span>{step.description}</span></div>
            <Space size={6} wrap>
              <Tag color={step.status === "COMPLETED" ? "green" : step.status === "ACTIVE" ? "blue" : step.status === "FAILED" ? "red" : step.status === "UNAVAILABLE" ? "default" : undefined}>{step.status === "COMPLETED" ? "已完成" : step.status === "ACTIVE" ? "进行中" : step.status === "FAILED" ? "失败" : step.status === "UNAVAILABLE" ? "未执行" : "等待"}</Tag>
              {sourceStatusLabel(step.sourceStatus) && <Tag color={sourceStatusColor(step.sourceStatus)}>{sourceStatusLabel(step.sourceStatus)}</Tag>}
            </Space>
          </div>
        ))}
      </div>
    </Card>
  );
}

function severityLabel(severity?: AgentWorkspaceFinding["severity"]): string {
  return severity === "high" ? "高关注" : severity === "medium" ? "需关注" : severity === "low" ? "低关注" : "分析发现";
}

export function FindingsPanel({ viewModel }: { viewModel: AgentWorkspaceViewModel }) {
  return (
    <Card bordered={false} className="fm-agent-business-card" title={<Flex justify="space-between" align="center"><span>当前发现</span><Text type="secondary">结构化判断</Text></Flex>}>
      {viewModel.findings.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={viewModel.status === "READY" ? "开始分析后显示结构化发现" : viewModel.status === "ANALYZING" ? "正在形成结构化发现" : "当前没有可用发现"} />
      ) : (
        <div className="fm-agent-findings-list">
          {viewModel.findings.map((finding) => <div className="fm-agent-finding" key={finding.id}><Flex justify="space-between" gap={10}><b>{finding.text}</b><Tag color={finding.severity === "high" ? "red" : finding.severity === "medium" ? "orange" : undefined}>{severityLabel(finding.severity)}</Tag></Flex><div className="fm-agent-finding__evidence"><FileSearchOutlined /> 关联证据 {finding.evidenceIds.length ? `${finding.evidenceIds.length} 条` : "待关联"}</div>{finding.uncertainty && <div className="fm-agent-finding__uncertainty">需要确认 · {finding.uncertainty}</div>}</div>)}
        </div>
      )}
    </Card>
  );
}

function evidenceCategoryLabel(viewModel: AgentWorkspaceViewModel, category: string): string {
  return viewModel.mode === "DEMO" && category === "历史案例" ? "历史案例（演示）" : category;
}

export function EvidenceUsedPanel({ viewModel }: { viewModel: AgentWorkspaceViewModel }) {
  const modalitySummary = Array.from(new Set(viewModel.evidence.map((item) => evidenceCategoryLabel(viewModel, item.category))));
  return (
      <Card bordered={false} className="fm-agent-business-card" title={<Flex justify="space-between" align="center"><span>多模态 RAG 证据</span><Text type="secondary">{viewModel.evidence.length ? `${viewModel.evidence.length} 条真实命中` : "等待检索"}</Text></Flex>}>
      {modalitySummary.length > 0 && <Flex wrap gap={6} className="fm-agent-evidence-modalities">{modalitySummary.map((item) => <Tag key={item}>{item}</Tag>)}</Flex>}
      {viewModel.evidence.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={viewModel.status === "READY" ? "分析开始后显示实际使用的证据" : "Agent 正在检索和融合证据"} /> : <div className="fm-agent-evidence-list">{viewModel.evidence.map((evidence) => <details className="fm-agent-evidence-row" key={evidence.id}><summary><span className="fm-agent-evidence-row__index">{evidenceCategoryLabel(viewModel, evidence.category)}</span><span className="fm-agent-evidence-row__title"><b>{evidence.title}</b><small>{evidence.source} · {evidence.observedAt}{evidence.score !== undefined ? ` · 相关度 ${Math.round(evidence.score * 100)}%` : ""}</small></span><ArrowRightOutlined /></summary><div className="fm-agent-evidence-row__detail"><p>{evidence.detail}</p><Text type="secondary">证据来源：{evidenceCategoryLabel(viewModel, evidence.category)}{evidence.score !== undefined ? ` · 检索分数 ${evidence.score.toFixed(2)}` : ""}</Text></div></details>)}</div>}
    </Card>
  );
}

function actionStateLabel(action: AgentWorkspaceAction): string {
  if (action.approvalState === "REQUIRED") return "等待批准";
  if (action.approvalState === "APPROVED") return action.taskId ? "已创建任务" : "已批准";
  if (action.approvalState === "REJECTED") return "已拒绝";
  if (action.approvalState === "UNAVAILABLE") return "演示不可执行";
  return "建议";
}

export function ActionProposalPanel({ viewModel }: { viewModel: AgentWorkspaceViewModel }) {
  return (
    <Card bordered={false} className="fm-agent-business-card" title={<Flex justify="space-between" align="center"><span>行动提案</span><Text type="secondary">建议动作不等于已创建任务</Text></Flex>}>
      {viewModel.actions.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={viewModel.status === "ANALYZING" ? "分析完成后显示下一步行动" : "当前没有可执行行动"} /> : <div className="fm-agent-actions-list">{viewModel.actions.map((action) => <div className="fm-agent-action-card" key={action.id}><Flex justify="space-between" align="flex-start" gap={12}><div><div className="fm-eyebrow">AGENT 建议</div><Title level={5}>{action.title}</Title></div><Tag color={action.approvalState === "REQUIRED" ? "orange" : action.approvalState === "APPROVED" ? "green" : undefined}>{actionStateLabel(action)}</Tag></Flex><p>{action.detail}</p><div className="fm-agent-action-card__meta"><span><b>作用范围</b>{action.scope}</span><span><b>依据</b>{action.evidenceIds.length ? `${action.evidenceIds.length} 条证据` : "等待证据关联"}</span></div>{action.outcome && <div className="fm-agent-action-card__outcome"><ClockCircleOutlined /> {action.outcome}</div>}</div>)}</div>}
    </Card>
  );
}

type ApprovalProps = {
  viewModel: AgentWorkspaceViewModel;
  busy: boolean;
  error?: string;
  onApprove: () => void;
  onReject: () => void;
};

export function ApprovalPanel({ viewModel, busy, error, onApprove, onReject }: ApprovalProps) {
  const approval = viewModel.approval;
  return (
    <Card bordered={false} className="fm-agent-business-card fm-agent-approval-card" title={<Flex justify="space-between" align="center"><span>人工确认</span><Tag color={approval.state === "REQUIRED" ? "orange" : approval.state === "APPROVED" ? "green" : approval.state === "REJECTED" ? "red" : undefined}>{approval.stateLabel}</Tag></Flex>}>
      {approval.title ? <><div className="fm-agent-approval-card__proposal"><div className="fm-eyebrow">AI 建议动作</div><Title level={5}>{approval.title}</Title><p>{approval.detail}</p></div>{approval.canApprove && <Space wrap><Button type="primary" loading={busy} onClick={onApprove}>批准并创建任务</Button><Button danger loading={busy} onClick={onReject}>拒绝建议</Button></Space>}</> : <div className="fm-agent-approval-card__empty"><CheckOutlined /> {approval.detail ?? "当前没有需要人工确认的写操作。"}</div>}
      {error && <div className="fm-agent-workspace-error"><WarningOutlined /> {error}</div>}
    </Card>
  );
}

export function ExecutionResultPanel({ viewModel, onOpenOperations }: { viewModel: AgentWorkspaceViewModel; onOpenOperations: () => void }) {
  const execution = viewModel.execution;
  return <Card bordered={false} className="fm-agent-business-card" title="执行结果"><div className={`fm-agent-execution fm-agent-execution--${execution.state.toLowerCase()}`}><div><b>{execution.stateLabel}</b><span>{execution.detail}</span></div>{execution.taskId && <><Tag color="green">任务 {execution.taskId}</Tag><Button type="link" onClick={onOpenOperations}>查看农事任务 <ArrowRightOutlined /></Button></>}</div></Card>;
}

export function TechnicalDetailsPanel({ viewModel }: { viewModel: AgentWorkspaceViewModel }) {
  const technical = viewModel.technical;
  return <details className="fm-agent-technical-details"><summary><Space><FileSearchOutlined /><b>技术详情</b><Text type="secondary">专家 / 开发者视图</Text></Space><span>展开</span></summary><div className="fm-agent-technical-details__body">{technical.modeNote && <div className="fm-agent-technical-note">{technical.modeNote}</div>}<div className="fm-agent-technical-grid"><div><small>运行 ID</small><b>{technical.runId ?? "未创建"}</b></div><div><small>模型</small><b>{technical.model ?? "—"}</b></div><div><small>诊断 ID</small><b>{technical.diagnosisId ?? "—"}</b></div><div><small>运行状态</small><b>{technical.rawStatus ?? "—"}</b></div><div><small>工具调用</small><b>{technical.toolCalls.length}</b></div><div><small>证据数量</small><b>{technical.evidenceIds.length}</b></div><div><small>农事任务</small><b>{technical.taskIds.length}</b></div><div><small>轨迹事件</small><b>{technical.traces.length}</b></div></div>{technical.plan && <><h5>执行计划</h5><pre>{JSON.stringify(technical.plan, null, 2)}</pre></>}{technical.toolCalls.length > 0 && <><h5>工具调用</h5><pre>{JSON.stringify(technical.toolCalls, null, 2)}</pre></>}{technical.traces.length > 0 && <><h5>运行轨迹</h5><pre>{JSON.stringify(technical.traces, null, 2)}</pre></>}</div></details>;
}

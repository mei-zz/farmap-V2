import { Button, Card, Divider, Empty, Flex, Input, List, Space, Tag, Typography } from "antd";
import { CheckCircleOutlined, ClockCircleOutlined, FileSearchOutlined, SafetyCertificateOutlined, SendOutlined, TeamOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router";
import type { ExpertReviewRecord, KnowledgeExpertViewModel } from "@/types/knowledge-expert";
import type { KnowledgeGraphNode } from "@/types/knowledge-graph";

const { Text } = Typography;

function statusColor(status: string) {
  if (status === "CONFIRMED") return "green";
  if (status === "CORRECTED") return "orange";
  if (status === "IN_REVIEW") return "blue";
  return "default";
}

function displayValue(value: unknown): string {
  if (value === undefined || value === null || value === "") return "暂无记录";
  if (typeof value === "string") return value;
  try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}

function recordKey(review: ExpertReviewRecord): string { return review.reviewId ?? review.diagnosisId; }

function evidenceIds(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => typeof item === "string" ? item : item && typeof item === "object" ? String((item as Record<string, unknown>).id ?? (item as Record<string, unknown>).evidenceId ?? "") : "").filter(Boolean);
}

export default function ExpertReviewPanel({
  isDemo,
  model,
  loading,
  selectedId,
  selected,
  expertDiagnosis,
  saving,
  graphNodes,
  onSelect,
  onDiagnosisChange,
  onSave,
  onFocusKnowledge,
}: {
  isDemo: boolean;
  model: KnowledgeExpertViewModel;
  loading: boolean;
  selectedId?: string;
  selected?: ExpertReviewRecord;
  expertDiagnosis: string;
  saving: boolean;
  graphNodes: KnowledgeGraphNode[];
  onSelect: (id: string) => void;
  onDiagnosisChange: (value: string) => void;
  onSave: (decision: "CONFIRMED" | "CORRECTED") => void;
  onFocusKnowledge: (nodeId: string) => void;
}) {
  const navigate = useNavigate();
  const linkedEvidence = selected ? evidenceIds(selected.evidence).find((id) => graphNodes.some((node) => node.id === id)) : undefined;
  return (
    <div className="fm-knowledge-center__review-grid">
      <Card className="fm-knowledge-card fm-knowledge-card--queue" title={<span><TeamOutlined /> Expert Review Queue</span>} extra={<Tag>{model.reviewQueue.status}</Tag>} loading={loading}>
        <Text type="secondary">{model.reviewQueue.detail}</Text>
        {model.reviewQueue.items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={model.reviewQueue.status === "UNAVAILABLE" ? "真实复核接口不可用" : "暂无真实复核记录"} /> : (
          <List className="fm-review-list" dataSource={model.reviewQueue.items} renderItem={(review) => (
            <List.Item className={selectedId === recordKey(review) ? "is-selected" : ""} onClick={() => onSelect(recordKey(review))}>
              <div><b>{review.diagnosisId}</b><small>{review.reviewId ? `${review.reviewId} · ` : ""}{review.reviewer || "未记录 reviewer"} · {review.reviewedAt || "未记录时间"}</small></div>
              <Tag color={statusColor(review.status)}>{review.status}</Tag>
            </List.Item>
          )} />
        )}
      </Card>

      <Card className="fm-knowledge-card fm-knowledge-card--workspace" title={<span><SafetyCertificateOutlined /> Review Workspace</span>} extra={selected && <Tag color={statusColor(selected.status)}>{selected.status}</Tag>}>
        {!selected ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择一条真实复核记录查看 AI 诊断与专家判断" /> : <>
          <div className="fm-review-workspace__meta"><b>{selected.diagnosisId}</b><span>AI Diagnosis ≠ Expert Judgment</span></div>
          <div className="fm-review-workspace__columns">
            <div><Text type="secondary">AI Diagnosis</Text><pre>{displayValue(selected.aiDiagnosis)}</pre></div>
            <div><Text type="secondary">Expert Judgment</Text><pre>{displayValue(selected.expertDiagnosis)}</pre></div>
          </div>
          <Divider />
          <Flex justify="space-between" align="center" gap={8} wrap><Text type="secondary">Evidence / Recommendations</Text>{linkedEvidence && <Button type="link" icon={<FileSearchOutlined />} onClick={() => onFocusKnowledge(linkedEvidence)}>查看知识来源</Button>}</Flex>
          <pre className="fm-review-workspace__evidence">{displayValue({ evidence: selected.evidence, recommendations: selected.recommendations })}</pre>
          {!isDemo && <div className="fm-review-actions"><Input.TextArea value={expertDiagnosis} onChange={(event) => onDiagnosisChange(event.target.value)} placeholder="仅在提交 CORRECTED 时填写专家修正意见" autoSize={{ minRows: 2, maxRows: 5 }} /><Space wrap><Button loading={saving} icon={<CheckCircleOutlined />} onClick={() => onSave("CONFIRMED")}>确认 AI 诊断</Button><Button type="primary" loading={saving} icon={<SendOutlined />} disabled={!expertDiagnosis.trim()} onClick={() => onSave("CORRECTED")}>提交专家修正</Button></Space></div>}
          {isDemo && <Text type="secondary">演示模式不显示复核写入动作。</Text>}
        </>}
      </Card>

      <Card className="fm-knowledge-card" title={<span><FileSearchOutlined /> Diagnosis / Agent Traceability</span>}>
        <List size="small">
          <List.Item><ClockCircleOutlined /><span>{model.traceability.diagnosisToReview}</span></List.Item>
          <List.Item><SafetyCertificateOutlined /><span>{model.traceability.reviewToCandidate}</span></List.Item>
          <List.Item><FileSearchOutlined /><span>{model.traceability.retrievalToEvidence}</span></List.Item>
        </List>
        <Button type="link" onClick={() => navigate("/diagnosis/a12-demo")}>查看 AI Diagnosis 入口</Button>
      </Card>
    </div>
  );
}

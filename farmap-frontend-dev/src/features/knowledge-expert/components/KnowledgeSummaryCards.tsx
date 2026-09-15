import { Card, Col, Row, Tag, Typography } from "antd";
import type { KnowledgeGraphResponse } from "@/types/knowledge-graph";

const { Text } = Typography;

function count(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : value.toLocaleString("zh-CN");
}

function statusLabel(value: string) {
  if (value === "READY") return "运行正常";
  if (value === "DEGRADED") return "降级";
  if (value === "READY_EMPTY") return "暂无内容";
  if (value === "UNAVAILABLE") return "暂不可用";
  return value;
}

export default function KnowledgeSummaryCards({ graph }: { graph: KnowledgeGraphResponse }) {
  const { summary, coverage, collections } = graph;
  const coverageValue = coverage.readyDocuments !== null && coverage.totalSourceFiles !== null
    ? `${count(coverage.readyDocuments)} / ${count(coverage.totalSourceFiles)}`
    : count(coverage.readyDocuments ?? coverage.registeredFiles);
  const cards = [
    ["Knowledge Sources", count(summary.sourceCount), "知识来源"],
    ["Documents", count(summary.documentCount), "当前文档"],
    ["READY Documents", count(coverage.readyDocuments), "生产可检索文档"],
    ["Production Chunks", count(summary.productionChunkCount), "Qwen 入库块"],
    ["Vector Count", count(summary.productionVectorCount), statusLabel(collections.productionKnowledge.status)],
    ["Coverage", coverageValue, coverage.unsupportedFiles === null ? "未索引数未登记" : `${count(coverage.unsupportedFiles)} 个未索引`],
  ];
  return (
    <Row gutter={[10, 10]} className="fm-knowledge-summary-cards">
      {cards.map(([label, value, detail]) => (
        <Col key={label} xs={12} sm={8} lg={4}>
          <Card className="fm-knowledge-kpi" variant="borderless">
            <Text type="secondary">{label}</Text>
            <strong>{value}</strong>
            <small>{detail}</small>
          </Card>
        </Col>
      ))}
      <Col xs={24}>
        <div className="fm-knowledge-runtime-line">
          <Tag color={graph.status === "READY" ? "green" : graph.status === "DEGRADED" ? "orange" : "default"}>{statusLabel(graph.status)}</Tag>
          <span>以知识来源、文档版本、Evidence 与 AI Diagnosis 的可追溯关系为主。</span>
          <span>生产向量与历史图像向量严格分开统计。</span>
        </div>
      </Col>
    </Row>
  );
}

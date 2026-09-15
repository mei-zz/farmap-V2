import { useState } from "react";
import { Alert, Button, Card, Col, Empty, Flex, Input, List, Row, Space, Table, Tag, Typography } from "antd";
import { DatabaseOutlined, FileSearchOutlined, NodeIndexOutlined, SearchOutlined } from "@ant-design/icons";
import type { KnowledgeGraphNode, KnowledgeGraphResponse } from "@/types/knowledge-graph";
import { searchKnowledge, type KnowledgeSearchResponse } from "@/features/knowledge-expert/service";

const { Text, Title } = Typography;

function statusLabel(value: string) {
  if (value === "READY") return "运行正常";
  if (value === "DEGRADED") return "降级";
  if (value === "READY_EMPTY") return "暂无内容";
  if (value === "UNAVAILABLE") return "暂不可用";
  return value;
}

function statusColor(value: string) { return value === "READY" ? "green" : value === "READY_EMPTY" ? "blue" : value === "DEGRADED" ? "orange" : "default"; }

function display(value: unknown) { return value === undefined || value === null || value === "" ? "—" : String(value); }

export default function KnowledgeStatusPanel({ graph, onSelectNode }: { graph: KnowledgeGraphResponse; onSelectNode: (node?: KnowledgeGraphNode) => void }) {
  const [query, setQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResponse>();
  const docs = graph.nodes.filter((node) => node.type === "KNOWLEDGE_DOCUMENT");
  const versions = new Map(graph.nodes.filter((node) => node.type === "KNOWLEDGE_VERSION").map((node) => [node.id, node]));
  const sources = new Map(graph.nodes.filter((node) => node.type === "KNOWLEDGE_SOURCE").map((node) => [node.id, node]));
  const submitSearch = async () => {
    if (!query.trim()) return;
    setSearching(true);
    try { setSearchResult(await searchKnowledge(query.trim())); } catch (error) { setSearchResult({ status: "UNAVAILABLE", results: [], rerankerStatus: error instanceof Error ? error.message : "检索接口暂不可用" }); }
    finally { setSearching(false); }
  };
  const rows = docs.map((doc) => {
    const source = sources.get(String(doc.metadata.sourceId));
    const version = versions.get(String(doc.metadata.currentVersionId));
    return { key: doc.id, doc, source, version };
  });
  return (
    <div className="fm-knowledge-status-panel">
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={14}>
          <Card className="fm-knowledge-card" title={<span><DatabaseOutlined /> Knowledge Base Health / Coverage</span>}>
            <Row gutter={[10, 10]} className="fm-knowledge-health-grid">
              <Col xs={12} sm={6}><div><small>已登记源文件</small><b>{display(graph.coverage.registeredFiles)}</b></div></Col>
              <Col xs={12} sm={6}><div><small>READY 文档</small><b>{display(graph.coverage.readyDocuments)}</b></div></Col>
              <Col xs={12} sm={6}><div><small>生产知识块</small><b>{display(graph.summary.productionChunkCount)}</b></div></Col>
              <Col xs={12} sm={6}><div><small>Evidence 关系</small><b>{display(graph.summary.evidenceCount)}</b></div></Col>
            </Row>
            <Alert className="fm-knowledge-honesty" type="info" showIcon title="知识图谱 V1 是 provenance / relationship graph，不是语义本体图谱。" description={graph.coverage.note} />
          </Card>
        </Col>
        <Col xs={24} lg={10}>
          <Card className="fm-knowledge-card" title={<span><NodeIndexOutlined /> Collections</span>}>
            {[graph.collections.productionKnowledge, graph.collections.historicalImage].map((collection) => (
              <div className="fm-knowledge-collection" key={collection.collection}>
                <Flex justify="space-between" gap={8}><b>{collection.collection}</b><Tag color={statusColor(collection.status)}>{statusLabel(collection.status)}</Tag></Flex>
                <small>{collection.embeddingModel || "历史图像向量"} · {collection.dimension}d · {collection.metric}</small>
                <strong>{collection.rowCount === null ? "—" : collection.rowCount.toLocaleString("zh-CN")}</strong>
                <Text type="secondary">{collection.note}</Text>
              </div>
            ))}
          </Card>
        </Col>
      </Row>

      <Card className="fm-knowledge-card" title={<span><NodeIndexOutlined /> Production RAG Pipeline</span>} extra={<Tag color={statusColor(graph.pipeline.status)}>{statusLabel(graph.pipeline.status)}</Tag>}>
        <div className="fm-knowledge-pipeline">{graph.pipeline.stages.map((stage, index) => <div key={stage.key} className="fm-knowledge-pipeline__stage"><span>{index + 1}</span><b>{stage.label}</b><small>{stage.detail}</small><Tag color={statusColor(stage.status)}>{statusLabel(stage.status)}</Tag></div>)}</div>
        <div className="fm-knowledge-retrieval-meta"><span>Candidate Top-N: {display(graph.retrieval.candidateTopN)}</span><span>Reranked Top-K: {display(graph.retrieval.finalTopK)}</span><span>Embedding: {display(graph.retrieval.embeddingModel)}</span><span>Reranker: {display(graph.retrieval.rerankerModel)} · {statusLabel(String(graph.retrieval.rerankerStatus))}</span></div>
      </Card>

      <Card className="fm-knowledge-card" title={<span><FileSearchOutlined /> Sources / Documents</span>}>
        {rows.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有可见知识文档" /> : <Table size="small" pagination={{ pageSize: 8, hideOnSinglePage: true }} dataSource={rows} rowKey="key" onRow={(row) => ({ onClick: () => onSelectNode(row.doc) })} columns={[
          { title: "Source", render: (_: unknown, row: typeof rows[number]) => row.source?.label || "—" },
          { title: "Document", render: (_: unknown, row: typeof rows[number]) => <b>{row.doc.label}</b> },
          { title: "Version", render: (_: unknown, row: typeof rows[number]) => row.version?.label || "—" },
          { title: "Status", render: (_: unknown, row: typeof rows[number]) => <Tag color={statusColor(row.doc.status || "")}>{statusLabel(row.doc.status || "—")}</Tag> },
          { title: "Chunks", render: (_: unknown, row: typeof rows[number]) => display(row.version?.metadata.chunkCount ?? row.doc.metadata.chunkCount) },
          { title: "Embedding", render: (_: unknown, row: typeof rows[number]) => display(row.version?.metadata.embeddingModel) },
          { title: "Updated", render: (_: unknown, row: typeof rows[number]) => display(row.doc.metadata.updatedAt) },
        ]} />}
      </Card>

      <Card className="fm-knowledge-card" title={<span><SearchOutlined /> Retrieval Observability</span>} extra={<Tag color={statusColor(graph.retrieval.status)}>{statusLabel(graph.retrieval.status)}</Tag>}>
        <Flex gap={8} wrap><Input value={query} onChange={(event) => setQuery(event.target.value)} onPressEnter={() => void submitSearch()} placeholder="输入只读知识检索问题" style={{ maxWidth: 520 }} /><Button type="primary" icon={<SearchOutlined />} loading={searching} onClick={() => void submitSearch()}>测试知识检索</Button></Flex>
        {searchResult && <div className="fm-knowledge-search-result"><Alert type={searchResult.status === "READY" ? "success" : "warning"} showIcon title={`检索状态：${statusLabel(searchResult.status)} · ${searchResult.results.length} 条 Evidence`} description={`${searchResult.embeddingModel || "—"} · ${searchResult.rerankerModel || "未使用 reranker"} · reranker ${statusLabel(searchResult.rerankerStatus || "—")}`} />{searchResult.results.length ? <List size="small" dataSource={searchResult.results} renderItem={(item, index) => <List.Item><div><b>{index + 1}. {item.documentTitle || item.chunkId || "Evidence"}</b><small>{item.sourceName || item.sourceId || "—"} · version {item.versionId || "—"}</small><p>{item.content || "—"}</p></div><Space direction="vertical" align="end"><Tag>vector {display(item.vectorScore)}</Tag><Tag>rerank {display(item.rerankScore)}</Tag></Space></List.Item>} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有返回 Evidence；不会用其他数据补齐。" />}</div>}
      </Card>

      <Card className="fm-knowledge-card fm-knowledge-traceability-card" title="Traceability">
        <Title level={5}>Diagnosis / Agent traceability</Title>
        <p>Diagnosis 节点只来自当前 actor 的 persisted `agent_diagnoses`；Evidence provenance 只使用 payload 中明确的 sourceId、documentId、versionId。</p>
        <p>Memory 不进入 Evidence 图谱；Review 与 Historical Case Candidate 只通过显式稳定 ID 连接。</p>
      </Card>
    </div>
  );
}

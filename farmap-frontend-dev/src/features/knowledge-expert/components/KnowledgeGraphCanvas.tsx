import { Fragment, useMemo, useState } from "react";
import { Button, Card, Empty, Flex, Input, Select, Tag, Typography } from "antd";
import { AimOutlined, ExpandOutlined, MinusOutlined, PlusOutlined, ReloadOutlined } from "@ant-design/icons";
import type { KnowledgeGraphNode, KnowledgeGraphNodeType, KnowledgeGraphResponse } from "@/types/knowledge-graph";

const { Text } = Typography;

const typeMeta: Record<KnowledgeGraphNodeType, { label: string; color: string }> = {
  KNOWLEDGE_SOURCE: { label: "Source", color: "#5fc88b" },
  KNOWLEDGE_DOCUMENT: { label: "Document", color: "#54a7e8" },
  KNOWLEDGE_VERSION: { label: "Version", color: "#9c72dc" },
  EVIDENCE: { label: "Evidence", color: "#f2b94b" },
  DIAGNOSIS: { label: "Diagnosis", color: "#ef6e6b" },
  EXPERT_REVIEW: { label: "Expert Review", color: "#ce77e8" },
  HISTORICAL_CASE_CANDIDATE: { label: "Candidate", color: "#65c1b0" },
};

const typeOptions = Object.entries(typeMeta).map(([value, meta]) => ({ value, label: meta.label }));
type Point = { x: number; y: number };

function hash(value: string): number {
  let result = 2166136261;
  for (let index = 0; index < value.length; index += 1) result = Math.imul(result ^ value.charCodeAt(index), 16777619);
  return (result >>> 0) / 4294967295;
}

function truncate(value: string, length = 13) {
  return value.length > length ? `${value.slice(0, length - 1)}…` : value;
}

function nodeRadius(node: KnowledgeGraphNode, degree: number, anchor: boolean) {
  const base = node.type === "KNOWLEDGE_SOURCE" || node.type === "EXPERT_REVIEW" ? 20 : node.type === "DIAGNOSIS" ? 18 : 15;
  return Math.min(anchor ? 34 : 28, base + Math.min(8, degree * 0.7) + (anchor ? 5 : 0));
}

function buildLayout(nodes: KnowledgeGraphNode[], edges: KnowledgeGraphResponse["edges"], selectedId?: string) {
  const width = 1000;
  const height = 620;
  const center = { x: width / 2, y: height / 2 };
  const nodeIds = new Set(nodes.map((node) => node.id));
  const adjacency = new Map<string, Set<string>>();
  nodes.forEach((node) => adjacency.set(node.id, new Set()));
  edges.forEach((edge) => {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) return;
    adjacency.get(edge.source)?.add(edge.target);
    adjacency.get(edge.target)?.add(edge.source);
  });
  const degree = new Map(nodes.map((node) => [node.id, adjacency.get(node.id)?.size ?? 0]));
  const anchor = nodes.find((node) => node.id === selectedId)?.id
    ?? [...nodes].sort((left, right) => (degree.get(right.id) ?? 0) - (degree.get(left.id) ?? 0) || left.id.localeCompare(right.id))[0]?.id;
  const positions = new Map<string, Point>();
  const radii = new Map<string, number>();
  const distances = new Map<string, number>();
  if (anchor) {
    distances.set(anchor, 0);
    const queue = [anchor];
    while (queue.length) {
      const current = queue.shift()!;
      for (const next of adjacency.get(current) ?? []) {
        if (distances.has(next)) continue;
        distances.set(next, (distances.get(current) ?? 0) + 1);
        queue.push(next);
      }
    }
  }
  nodes.forEach((node) => {
    const isAnchor = node.id === anchor;
    const radius = nodeRadius(node, degree.get(node.id) ?? 0, isAnchor);
    radii.set(node.id, radius);
    if (isAnchor) {
      positions.set(node.id, center);
      return;
    }
    const distance = distances.get(node.id) ?? 3;
    const angle = hash(node.id) * Math.PI * 2;
    const ring = Math.min(2.7, 0.62 + distance * 0.45);
    positions.set(node.id, {
      x: center.x + Math.cos(angle) * (width * 0.12 + width * 0.12 * ring),
      y: center.y + Math.sin(angle) * (height * 0.12 + height * 0.12 * ring),
    });
  });

  const velocities = new Map<string, Point>(nodes.map((node) => [node.id, { x: 0, y: 0 }]));
  for (let iteration = 0; iteration < 105; iteration += 1) {
    const forces = new Map<string, Point>(nodes.map((node) => [node.id, { x: 0, y: 0 }]));
    for (let leftIndex = 0; leftIndex < nodes.length; leftIndex += 1) {
      const left = nodes[leftIndex];
      const leftPoint = positions.get(left.id)!;
      for (let rightIndex = leftIndex + 1; rightIndex < nodes.length; rightIndex += 1) {
        const right = nodes[rightIndex];
        const rightPoint = positions.get(right.id)!;
        const dx = rightPoint.x - leftPoint.x;
        const dy = rightPoint.y - leftPoint.y;
        const distance = Math.max(1, Math.hypot(dx, dy));
        const minimumDistance = (radii.get(left.id) ?? 15) + (radii.get(right.id) ?? 15) + 16;
        const force = distance < minimumDistance
          ? (minimumDistance - distance) * 0.22 + 2.4
          : 11000 / (distance * distance);
        const fx = (dx / distance) * force;
        const fy = (dy / distance) * force;
        forces.get(left.id)!.x -= fx; forces.get(left.id)!.y -= fy;
        forces.get(right.id)!.x += fx; forces.get(right.id)!.y += fy;
      }
    }
    edges.forEach((edge) => {
      if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) return;
      const from = positions.get(edge.source)!;
      const to = positions.get(edge.target)!;
      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const distance = Math.max(1, Math.hypot(dx, dy));
      const targetDistance = 142 + Math.abs((distances.get(edge.source) ?? 2) - (distances.get(edge.target) ?? 2)) * 14;
      const force = (distance - targetDistance) * 0.009;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;
      forces.get(edge.source)!.x += fx; forces.get(edge.source)!.y += fy;
      forces.get(edge.target)!.x -= fx; forces.get(edge.target)!.y -= fy;
    });
    nodes.forEach((node) => {
      if (node.id === anchor) return;
      const point = positions.get(node.id)!;
      const force = forces.get(node.id)!;
      const toCenter = { x: (center.x - point.x) * 0.0025, y: (center.y - point.y) * 0.0025 };
      const velocity = velocities.get(node.id)!;
      velocity.x = (velocity.x + force.x + toCenter.x) * 0.84;
      velocity.y = (velocity.y + force.y + toCenter.y) * 0.84;
      velocity.x = Math.max(-10, Math.min(10, velocity.x));
      velocity.y = Math.max(-10, Math.min(10, velocity.y));
      point.x = Math.max(38 + (radii.get(node.id) ?? 15), Math.min(width - 38 - (radii.get(node.id) ?? 15), point.x + velocity.x));
      point.y = Math.max(38 + (radii.get(node.id) ?? 15), Math.min(height - 38 - (radii.get(node.id) ?? 15), point.y + velocity.y));
    });
  }
  return { positions, radii, degree, anchor, width, height };
}

function edgePath(from: Point, to: Point, edgeId: string, fromRadius: number, toRadius: number) {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const distance = Math.max(1, Math.hypot(dx, dy));
  const start = { x: from.x + (dx / distance) * fromRadius, y: from.y + (dy / distance) * fromRadius };
  const end = { x: to.x - (dx / distance) * (toRadius + 3), y: to.y - (dy / distance) * (toRadius + 3) };
  const bend = (hash(edgeId) - 0.5) * 25;
  const control = { x: (start.x + end.x) / 2 - (dy / distance) * bend, y: (start.y + end.y) / 2 + (dx / distance) * bend };
  return { d: `M ${start.x} ${start.y} Q ${control.x} ${control.y} ${end.x} ${end.y}`, label: control };
}

function nodeShape(node: KnowledgeGraphNode, point: Point, radius: number, selected: boolean, anchor: boolean, onClick: () => void) {
  const color = typeMeta[node.type].color;
  const labelLength = radius > 25 ? 14 : radius > 19 ? 11 : 8;
  return (
    <g className="fm-knowledge-graph-node" role="button" tabIndex={0} onClick={onClick} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") onClick(); }}>
      <title>{`${node.label} · ${node.id}`}</title>
      {anchor && <circle cx={point.x} cy={point.y} r={radius + 10} fill={color} opacity=".12" className="fm-knowledge-graph-node__halo" />}
      {selected && <circle cx={point.x} cy={point.y} r={radius + 6} fill="none" stroke="#123b2a" strokeWidth="2" strokeDasharray="3 3" />}
      <circle cx={point.x} cy={point.y} r={radius} fill={color} fillOpacity={selected ? ".98" : ".88"} stroke={selected ? "#123b2a" : "#fff"} strokeWidth={selected ? 3 : 1.5} />
      <text x={point.x} y={point.y - 1} textAnchor="middle" className="fm-knowledge-graph-node__label">{truncate(node.label, labelLength)}</text>
      <text x={point.x} y={point.y + 11} textAnchor="middle" className="fm-knowledge-graph-node__type">{typeMeta[node.type].label}</text>
    </g>
  );
}

function metadataValue(value: unknown): string {
  if (value === undefined || value === null || value === "") return "—";
  if (typeof value === "object") {
    try { return JSON.stringify(value); } catch { return String(value); }
  }
  return String(value);
}

function GraphDetails({ node, relationCount }: { node?: KnowledgeGraphNode; relationCount: number }) {
  if (!node) return <div className="fm-knowledge-graph-details__empty"><AimOutlined /><b>选择一个实体</b><span>点击点云中的 Source、Document、Version 或 Evidence 查看稳定 ID 与来源关系。</span></div>;
  const detailKeys = ["sourceId", "documentId", "versionId", "farmId", "fieldId", "sourceRunId", "diagnosisId", "reviewId", "chunkCount", "embeddingModel", "embeddingDimension", "indexCollection", "modality", "timestamp", "createdAt", "updatedAt"];
  return (
    <div className="fm-knowledge-graph-details">
      <Flex justify="space-between" align="flex-start" gap={8}>
        <div><Text type="secondary">Selected Entity</Text><h3>{node.label}</h3></div>
        <Tag color={typeMeta[node.type].color}>{typeMeta[node.type].label}</Tag>
      </Flex>
      <dl>
        <dt>Stable ID</dt><dd>{node.id}</dd>
        <dt>Status</dt><dd>{node.status || "未提供"}</dd>
        <dt>关系数</dt><dd>{relationCount}</dd>
        {detailKeys.filter((key) => node.metadata[key] !== undefined).map((key) => <Fragment key={key}><dt>{key}</dt><dd>{metadataValue(node.metadata[key])}</dd></Fragment>)}
      </dl>
    </div>
  );
}

export default function KnowledgeGraphCanvas({ graph, selectedId, scopeLabel, onSelectNode }: { graph: KnowledgeGraphResponse; selectedId?: string; scopeLabel?: string; onSelectNode: (node?: KnowledgeGraphNode) => void }) {
  const [types, setTypes] = useState<KnowledgeGraphNodeType[]>([]);
  const [search, setSearch] = useState("");
  const [zoom, setZoom] = useState(1);
  const allNodes = graph.nodes;
  const filteredNodes = useMemo(() => {
    const query = search.trim().toLowerCase();
    return allNodes.filter((node) => {
      if (types.length && !types.includes(node.type)) return false;
      if (!query) return true;
      return `${node.label} ${node.id} ${node.metadata.sourceId ?? ""} ${node.metadata.documentId ?? ""}`.toLowerCase().includes(query);
    });
  }, [allNodes, search, types]);
  const layout = useMemo(() => buildLayout(filteredNodes, graph.edges, selectedId), [filteredNodes, graph.edges, selectedId]);
  const selected = allNodes.find((node) => node.id === selectedId);
  const selectedRelationCount = selected ? graph.edges.filter((edge) => edge.source === selected.id || edge.target === selected.id).length : 0;
  const visibleIds = new Set(filteredNodes.map((node) => node.id));
  const visibleEdges = graph.edges.filter((edge) => visibleIds.has(edge.source) && visibleIds.has(edge.target));
  const selectedEdges = selected ? visibleEdges.filter((edge) => edge.source === selected.id || edge.target === selected.id) : [];
  return (
    <Card className="fm-knowledge-card fm-knowledge-graph-card" title={<span><AimOutlined /> Knowledge Graph · Point Cloud</span>} extra={<Flex align="center" gap={6}>{scopeLabel && <Tag color="blue">当前知识域：{scopeLabel}</Tag>}<Tag color={graph.status === "READY" ? "green" : "orange"}>{graph.nodes.length} nodes · {graph.edges.length} edges</Tag></Flex>}>
      <div className="fm-knowledge-graph-toolbar">
        <Select allowClear mode="multiple" maxTagCount="responsive" value={types} onChange={setTypes} options={typeOptions} placeholder="按节点类型筛选" />
        <Input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索 source / document / stable ID" allowClear />
        <Button icon={<ExpandOutlined />} onClick={() => setZoom(1)}>适配</Button>
        <Button icon={<MinusOutlined />} onClick={() => setZoom((value) => Math.max(.65, Number((value - .15).toFixed(2))))} aria-label="缩小图谱" />
        <Button icon={<PlusOutlined />} onClick={() => setZoom((value) => Math.min(1.5, Number((value + .15).toFixed(2))))} aria-label="放大图谱" />
        <Button icon={<ReloadOutlined />} onClick={() => { setTypes([]); setSearch(""); setZoom(1); onSelectNode(undefined); }}>重置</Button>
      </div>
      <div className="fm-knowledge-graph-layout">
        <div className="fm-knowledge-graph-canvas" data-testid="knowledge-graph-canvas">
          {filteredNodes.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={graph.status === "EMPTY" ? "知识库已可检索，但当前暂无跨业务关系图谱数据。" : "没有匹配的节点"} /> : (
            <svg viewBox={`0 0 ${layout.width} ${layout.height}`} role="img" aria-label="FarMap provenance relationship point cloud">
              <defs><marker id="fm-knowledge-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#a8bdb0" /></marker><marker id="fm-knowledge-arrow-selected" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#1e6843" /></marker></defs>
              <g transform={`translate(${(layout.width * (1 - zoom)) / 2} ${(layout.height * (1 - zoom)) / 2}) scale(${zoom})`}>
                {visibleEdges.map((edge) => {
                  const from = layout.positions.get(edge.source); const to = layout.positions.get(edge.target); if (!from || !to) return null;
                  const active = selectedEdges.some((item) => item.id === edge.id);
                  const path = edgePath(from, to, edge.id, layout.radii.get(edge.source) ?? 15, layout.radii.get(edge.target) ?? 15);
                  return <g key={edge.id}><path d={path.d} className={active ? "is-selected" : ""} markerEnd={`url(#${active ? "fm-knowledge-arrow-selected" : "fm-knowledge-arrow"})`} />{(active || graph.edges.length < 44) && <text x={path.label.x} y={path.label.y - 4} className="fm-knowledge-graph-edge-label">{edge.type}</text>}</g>;
                })}
                {filteredNodes.map((node) => { const point = layout.positions.get(node.id); return point ? <Fragment key={node.id}>{nodeShape(node, point, layout.radii.get(node.id) ?? 15, node.id === selectedId, node.id === layout.anchor, () => onSelectNode(node))}</Fragment> : null; })}
              </g>
            </svg>
          )}
        </div>
        <GraphDetails node={selected} relationCount={selectedRelationCount} />
      </div>
      <div className="fm-knowledge-graph-legend">{Object.entries(typeMeta).map(([type, meta]) => <span key={type}><i style={{ background: meta.color }} />{meta.label}</span>)}<Text type="secondary">真实节点以点云聚合展示；关系只使用服务端稳定 ID，chunk 以 Version 元数据聚合。</Text></div>
    </Card>
  );
}

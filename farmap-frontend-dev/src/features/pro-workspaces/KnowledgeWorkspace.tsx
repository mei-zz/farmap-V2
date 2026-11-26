import { useState } from "react";
import { useSearchParams } from "react-router";
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  List,
  Spin,
  Tabs,
  Typography,
} from "antd";
import { SearchOutlined } from "@ant-design/icons";
import {
  loadKnowledgeGraph,
  searchKnowledge,
  type KnowledgeSearchResponse,
} from "@/features/knowledge-expert/service";
import KnowledgeGraphCanvas from "@/features/knowledge-expert/components/KnowledgeGraphCanvas";
import KnowledgeExpertCenter from "@/features/knowledge-expert/components/KnowledgeExpertCenter";
import type { KnowledgeGraphResponse } from "@/types/knowledge-graph";
import { useUserStore } from "@/store/user";
import { permanence } from "@/utils/permanence";
import CaseLibrary from "./CaseLibrary";
import "./planning-knowledge.css";

export default function KnowledgeWorkspace() {
  const [params, setParams] = useSearchParams();
  const requested = params.get("view") || "library";
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<KnowledgeSearchResponse>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [graph, setGraph] = useState<KnowledgeGraphResponse>();
  const [fullGraph, setFullGraph] = useState<KnowledgeGraphResponse>();
  const [selectedId, setSelectedId] = useState<string>();
  const [graphLoading, setGraphLoading] = useState(false);
  const role = useUserStore((state) => state.role);
  const storedRole = permanence.user.useUserStore()?.role;
  const canReview =
    role === "expert" ||
    role === "admin" ||
    storedRole === "expert" ||
    storedRole === "admin";
  const search = async () => {
    if (!query.trim()) return;
    setLoading(true);
    setError(undefined);
    setGraph(undefined);
    setFullGraph(undefined);
    try {
      setResults(await searchKnowledge(query.trim()));
    } catch (e) {
      setResults(undefined);
      setError(e instanceof Error ? e.message : "知识检索暂不可用");
    } finally {
      setLoading(false);
    }
  };
  const explore = async (documentId: string) => {
    setGraphLoading(true);
    setError(undefined);
    setGraph(undefined);
    try {
      const value = await loadKnowledgeGraph({
        documentId,
        limit: 80,
        includeEvidence: true,
      });
      setFullGraph(value);
      const focus =
        value.nodes.find((node) => node.type === "KNOWLEDGE_DOCUMENT") ??
        value.nodes[0];
      if (focus) {
        const related = new Set([focus.id]);
        value.edges.forEach((edge) => {
          if (edge.source === focus.id) related.add(edge.target);
          if (edge.target === focus.id) related.add(edge.source);
        });
        setGraph({
          ...value,
          nodes: value.nodes.filter((node) => related.has(node.id)),
          edges: value.edges.filter(
            (edge) => related.has(edge.source) && related.has(edge.target),
          ),
        });
        setSelectedId(focus.id);
      } else setGraph(value);
    } catch (e) {
      setError(e instanceof Error ? e.message : "知识关系暂不可用");
    } finally {
      setGraphLoading(false);
    }
  };
  const selectNode = (nodeId?: string) => {
    setSelectedId(nodeId);
    if (!nodeId || !fullGraph) return;
    const related = new Set([nodeId]);
    fullGraph.edges.forEach((edge) => {
      if (edge.source === nodeId) related.add(edge.target);
      if (edge.target === nodeId) related.add(edge.source);
    });
    setGraph({
      ...fullGraph,
      nodes: fullGraph.nodes.filter((node) => related.has(node.id)),
      edges: fullGraph.edges.filter(
        (edge) => related.has(edge.source) && related.has(edge.target),
      ),
    });
  };
  const library = (
    <div
      className={`fm-pro-knowledge-grid${graph || graphLoading ? " has-graph" : ""}`}
    >
      <Card className="fm-pro-card" title="检索农业知识">
        <Typography.Paragraph type="secondary">
          搜索作物、病害或农事问题，查看资料及其关联依据。
        </Typography.Paragraph>
        <Input.Search
          size="large"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onSearch={() => void search()}
          enterButton={<SearchOutlined />}
          placeholder="输入作物、病害或农事问题"
          loading={loading}
        />
        {error && (
          <Alert
            style={{ marginTop: 16 }}
            type="error"
            showIcon
            title={error}
          />
        )}
        {results && (
          <List
            className="fm-pro-results"
            dataSource={results.results}
            locale={{ emptyText: <Empty description="没有找到相关资料" /> }}
            renderItem={(item, index) => (
              <List.Item
                key={item.chunkId ?? index}
                actions={
                  item.documentId
                    ? [
                        <Button
                          key="explore"
                          type="link"
                          onClick={() =>
                            void explore(item.documentId as string)
                          }
                        >
                          探索关联
                        </Button>,
                      ]
                    : []
                }
              >
                <List.Item.Meta
                  title={item.documentTitle || item.sourceName || "知识资料"}
                  description={
                    <>
                      <p>{item.content}</p>
                      <Typography.Text type="secondary">
                        {item.sourceName || "来源未返回"}
                      </Typography.Text>
                    </>
                  }
                />
              </List.Item>
            )}
          />
        )}
        {!results && !loading && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="输入问题开始检索"
          />
        )}
      </Card>
      {(graph || graphLoading) && (
        <Card className="fm-pro-card" title="知识关系探索">
          <Typography.Paragraph type="secondary">
            从检索结果选择资料，查看相关的局部关系。
          </Typography.Paragraph>
          {graphLoading ? (
            <Spin />
          ) : graph ? (
            <KnowledgeGraphCanvas
              graph={graph}
              selectedId={selectedId}
              onSelectNode={(node) => selectNode(node?.id)}
            />
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="选择一条资料后探索关联"
            />
          )}
          {graph && selectedId && (
            <div className="fm-pro-related">
              <b>相关节点</b>
              {graph.nodes
                .filter((node) => node.id !== selectedId)
                .map((node) => (
                  <Button
                    key={node.id}
                    type="link"
                    onClick={() => selectNode(node.id)}
                  >
                    {node.label}
                  </Button>
                ))}
            </div>
          )}
        </Card>
      )}
    </div>
  );
  return (
    <div className="fm-pro-workspace fm-pro-knowledge">
      <header className="fm-pro-heading">
        <div>
          <span>AGRICULTURAL KNOWLEDGE</span>
          <h1>知识中心</h1>
          <p>检索农业资料，连接知识依据与专家经验。</p>
        </div>
        
      </header>
      <Tabs
        activeKey={
          requested === "explore"
            ? "library"
            : requested === "review" && !canReview
              ? "library"
              : ["library", "review", "cases"].includes(requested)
                ? requested
                : "library"
        }
        onChange={(key) => {
          const next = new URLSearchParams(params);
          next.set("view", key);
          setParams(next);
        }}
        items={[
          { key: "library", label: "知识资料", children: library },
          ...(canReview
            ? [
                {
                  key: "review",
                  label: "专家复核",
                  children: <KnowledgeExpertCenter reviewOnly />,
                },
              ]
            : []),
          {
            key: "cases",
            label: "历史案例",
            children: (
              <Card className="fm-pro-card" title="当前范围历史案例">
                <CaseLibrary />
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}


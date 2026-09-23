import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, Flex, Spin, Tabs, Tag, Typography } from "antd";
import { HistoryOutlined, RobotOutlined, ShareAltOutlined } from "@ant-design/icons";
import { useLocation } from "react-router";
import { listExpertReviews, listExpertReviewsV2, listKnowledgeSources, loadKnowledgeGraph, saveExpertReviewRecord, updateExpertReviewV2, type KnowledgeSource } from "@/features/knowledge-expert/service";
import { buildKnowledgeExpertViewModel, normalizeExpertReviews } from "@/features/knowledge-expert/adapter";
import type { ExpertReviewRecord, KnowledgeExpertViewModel } from "@/types/knowledge-expert";
import type { KnowledgeGraphNode, KnowledgeGraphResponse } from "@/types/knowledge-graph";
import { getDemoSession } from "@/utils/demoSession";
import { knowledgeProfileForFarm, resolveFarmKnowledgeSource } from "@/features/knowledge-expert/farmKnowledge";
import { permanence } from "@/utils/permanence";
import { openOverviewCopilot } from "@/features/overview/copilot/context";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import KnowledgeSummaryCards from "./KnowledgeSummaryCards";
import KnowledgeGraphCanvas from "./KnowledgeGraphCanvas";
import KnowledgeStatusPanel from "./KnowledgeStatusPanel";
import ExpertReviewPanel from "./ExpertReviewPanel";

const { Text, Title, Paragraph } = Typography;

function emptyGraph(): KnowledgeGraphResponse {
  return {
    status: "UNAVAILABLE", generatedAt: new Date().toISOString(), nodes: [], edges: [],
    summary: { sourceCount: 0, documentCount: 0, versionCount: 0, chunkCount: 0, productionChunkCount: 0, productionVectorCount: null, evidenceCount: 0, diagnosisLinkedCount: 0, expertReviewCount: 0, historicalCandidateCount: 0 },
    coverage: { totalSourceFiles: null, readyDocuments: null, unsupportedFiles: null, corruptNoTextFiles: null, registeredFiles: null, status: "UNAVAILABLE" },
    collections: {
      productionKnowledge: { collection: "farmap_knowledge_chunks_qwen3_v1", status: "UNAVAILABLE", rowCount: null, embeddingModel: "qwen3.7-text-embedding-flash", dimension: 1024, metric: "COSINE" },
      historicalImage: { collection: "farmap_image_vectors_new", status: "UNAVAILABLE", rowCount: null, dimension: 512, metric: "L2" },
    },
    pipeline: { status: "UNAVAILABLE", stages: [] },
    retrieval: { status: "UNAVAILABLE", candidateTopN: 20, finalTopK: 5, embeddingModel: "qwen3.7-text-embedding-flash", rerankerModel: "gte-rerank-v2", rerankerStatus: "UNAVAILABLE" },
  };
}

function recordKey(review: ExpertReviewRecord): string { return review.reviewId ?? review.diagnosisId; }

function ModeTag({ mode }: { mode: KnowledgeExpertViewModel["mode"] }) {
  const color = mode === "REAL" ? "green" : mode === "DEMO" ? "gold" : "default";
  return <Tag color={color}>{mode === "REAL" ? "REAL" : mode === "DEMO" ? "DEMO · READ ONLY" : mode}</Tag>;
}

function HistoricalCasesPanel({ graph }: { graph: KnowledgeGraphResponse }) {
  const collection = graph.collections.historicalImage;
  const candidateCount = graph.summary.historicalCandidateCount;
  const status = collection.status === "READY_EMPTY" ? "READY_EMPTY" : collection.status;
  return (
    <div className="fm-knowledge-history-panel">
      <Card className="fm-knowledge-card" title={<span><HistoryOutlined /> Historical Cases</span>} extra={<Tag color={status === "READY_EMPTY" ? "blue" : "orange"}>{status}</Tag>}>
        <div className="fm-knowledge-history-hero"><div><Text type="secondary">Confirmed historical cases</Text><strong>0</strong><span>暂无已确认历史案例</span></div><div><Text type="secondary">Candidate count</Text><strong>{candidateCount === null ? "—" : candidateCount}</strong><span>来自 actor-scoped Expert Review 关系</span></div></div>
        <Alert type="info" showIcon title="历史案例库暂无内容" description="只有满足 CONFIRMED Expert Review、有效图像/记录、专家判断与 outcome 的记录，才可进入历史案例候选；Candidate 不等于 HistoricalCase。" />
        <div className="fm-knowledge-history-collection"><b>{collection.collection}</b><Tag color={status === "READY_EMPTY" ? "blue" : "orange"}>{collection.rowCount === null ? "行数不可用" : `${collection.rowCount} vectors`}</Tag><span>独立的 512d 历史图像集合，不与生产知识向量混用。</span></div>
      </Card>
    </div>
  );
}

export default function KnowledgeExpertCenter() {
  const location = useLocation();
  const isDemo = Boolean(getDemoSession());
  const [model, setModel] = useState<KnowledgeExpertViewModel>(() => buildKnowledgeExpertViewModel({ mode: isDemo ? "DEMO" : "REAL" }));
  const [graph, setGraph] = useState<KnowledgeGraphResponse>(emptyGraph);
  const [graphLoading, setGraphLoading] = useState(!isDemo);
  const [graphError, setGraphError] = useState<string>();
  const [selectedGraphId, setSelectedGraphId] = useState<string>();
  const [selectedId, setSelectedId] = useState<string>();
  const [selected, setSelected] = useState<ExpertReviewRecord>();
  const [expertDiagnosis, setExpertDiagnosis] = useState("");
  const [loading, setLoading] = useState(!isDemo);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string>();
  const [knowledgeSources, setKnowledgeSources] = useState<KnowledgeSource[]>([]);
  const [sourcesLoading, setSourcesLoading] = useState(!isDemo);
  const [sourceError, setSourceError] = useState<string>();
  const [activeTab, setActiveTab] = useState(() => location.pathname.endsWith("/expert-review") ? "review" : location.pathname.endsWith("/cases") ? "history" : "graph");
  const setAIContext = useAIContextStore((state) => state.setContext);
  const currentFarm = useAIContextStore((state) => state.context.currentFarm);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const setDraft = useCopilotStore((state) => state.setDraft);
  const openCopilot = useCopilotStore((state) => state.open);
  const storedUser = permanence.user.useUserStore();
  const activeFarmId = currentFarm.id > 0 ? currentFarm.id : storedUser?.farms?.[0]?.id ?? -1;
  const knowledgeProfile = knowledgeProfileForFarm(activeFarmId);
  const scopedKnowledgeSource = resolveFarmKnowledgeSource(knowledgeSources, activeFarmId);
  const displayFarmName = currentFarm.id > 0 ? currentFarm.name : knowledgeProfile?.farmName ?? currentFarm.name;

  useEffect(() => {
    if (location.pathname.endsWith("/expert-review")) setActiveTab("review");
    else if (location.pathname.endsWith("/cases")) setActiveTab("history");
  }, [location.pathname]);

  const refreshReviews = useCallback(async () => {
    if (isDemo) return;
    setLoading(true);
    try {
      let records = normalizeExpertReviews(await listExpertReviewsV2());
      if (!records.length) records = normalizeExpertReviews(await listExpertReviews());
      setModel(buildKnowledgeExpertViewModel({ mode: "REAL", reviews: records }));
      setSelectedId((current) => current && records.some((item) => recordKey(item) === current) ? current : records[0] ? recordKey(records[0]) : undefined);
    } catch (error) {
      setModel(buildKnowledgeExpertViewModel({ mode: "REAL", reviewUnavailable: true }));
      setNotice(error instanceof Error ? error.message : "Expert Review 接口暂不可用");
    } finally { setLoading(false); }
  }, [isDemo]);

  const refreshGraph = useCallback(async () => {
    if (isDemo) return;
    if (sourcesLoading) return;
    setGraphLoading(true); setGraphError(undefined);
    try {
      if (sourceError) throw new Error(sourceError);
      if (!knowledgeProfile || !scopedKnowledgeSource) {
        setGraph(emptyGraph());
        throw new Error(knowledgeProfile ? `当前农场暂无“${knowledgeProfile.corpusFolder}”知识源` : "当前农场尚未配置对应知识库");
      }
      setGraph(await loadKnowledgeGraph({ sourceId: scopedKnowledgeSource.sourceId, limit: 150, includeEvidence: true }));
      setAIContext({ sourceModes: { knowledge: "MYSQL" } });
    }
    catch (error) { setGraphError(error instanceof Error ? error.message : "Knowledge Graph 接口暂不可用"); }
    finally { setGraphLoading(false); }
  }, [isDemo, knowledgeProfile, scopedKnowledgeSource, setAIContext, sourceError, sourcesLoading]);

  useEffect(() => {
    if (isDemo) return;
    let active = true;
    setSourcesLoading(true);
    setSourceError(undefined);
    listKnowledgeSources()
      .then((sources) => { if (active) setKnowledgeSources(sources); })
      .catch((error: unknown) => { if (active) setSourceError(error instanceof Error ? error.message : "Knowledge Source 接口暂不可用"); })
      .finally(() => { if (active) setSourcesLoading(false); });
    return () => { active = false; };
  }, [isDemo]);

  useEffect(() => { void refreshReviews(); void refreshGraph(); }, [refreshReviews, refreshGraph]);

  useEffect(() => { setSelectedGraphId(undefined); }, [activeFarmId, scopedKnowledgeSource?.sourceId]);

  useEffect(() => {
    const item = model.reviewQueue.items.find((review) => recordKey(review) === selectedId);
    setSelected(item);
    if (item) setExpertDiagnosis(typeof item.expertDiagnosis === "string" ? item.expertDiagnosis : "");
  }, [model.reviewQueue.items, selectedId]);

  const selectedSummary = useMemo(() => selected ? `诊断 ${selected.diagnosisId} · ${selected.status}` : "尚未选择真实复核记录", [selected]);

  const openReviewCopilot = () => {
    openOverviewCopilot({
      contextType: "DIAGNOSIS", mode: model.mode === "DEMO" ? "DEMO" : "REAL", page: "/knowledge/expert-review",
      farm: { id: getDemoSession()?.farm.id ?? 0, name: getDemoSession()?.farm.name ?? "当前农场" },
      title: selected ? `专家复核：${selected.diagnosisId}` : "知识智能中心",
      summary: selectedSummary, diagnosis: selected ? { id: selected.diagnosisId, title: "AI 诊断结论", severity: "medium" } : undefined,
      evidence: { labels: selected?.evidence ? ["诊断 Evidence"] : [], count: Array.isArray(selected?.evidence) ? selected.evidence.length : undefined, historyUnavailable: graph.collections.historicalImage.status !== "READY" },
    }, { setAIContext, setCopilotContext, setDraft, open: openCopilot });
  };

  const saveReview = async (decision: "CONFIRMED" | "CORRECTED") => {
    if (isDemo || !selected) return;
    setSaving(true); setNotice(undefined);
    try {
      if (selected.reviewId) await updateExpertReviewV2(selected.reviewId, { version: selected.version ?? 0, diagnosisJudgment: decision === "CONFIRMED" ? "CORRECT" : "INCORRECT", diagnosisCorrection: decision === "CORRECTED" ? expertDiagnosis : selected.after, notes: selected.notes });
      else await saveExpertReviewRecord(selected.diagnosisId, { decision, aiDiagnosis: selected.aiDiagnosis, expertDiagnosis: decision === "CORRECTED" ? expertDiagnosis : selected.expertDiagnosis ?? selected.aiDiagnosis, evidence: selected.evidence ?? [], recommendations: selected.recommendations ?? [], before: selected.before, after: decision === "CORRECTED" ? expertDiagnosis : selected.after });
      setNotice(decision === "CONFIRMED" ? "已提交 CONFIRMED 专家复核结果。" : "已提交 CORRECTED 专家复核结果。");
      await Promise.all([refreshReviews(), refreshGraph()]);
    } catch (error) { setNotice(error instanceof Error ? error.message : "专家复核写入失败"); }
    finally { setSaving(false); }
  };

  const focusKnowledge = (nodeId: string) => { setSelectedGraphId(nodeId); setActiveTab("graph"); };
  const selectGraphNode = (node?: KnowledgeGraphNode) => setSelectedGraphId(node?.id);
  const graphBody = graphLoading ? <Card className="fm-knowledge-card fm-knowledge-loading"><Spin description="正在读取当前农场知识关系与运行时状态…" /></Card> : graphError ? <Alert type="error" showIcon title="当前农场知识图谱暂不可用" description={graphError} action={<Button onClick={() => void refreshGraph()}>重试</Button>} /> : <><KnowledgeSummaryCards graph={graph} /><KnowledgeGraphCanvas graph={graph} selectedId={selectedGraphId} scopeLabel={knowledgeProfile?.corpusFolder} onSelectNode={selectGraphNode} /></>;
  const statusBody = graphLoading ? <Card className="fm-knowledge-card fm-knowledge-loading"><Spin description="正在读取知识库状态…" /></Card> : graphError ? <Alert type="error" showIcon title="Knowledge Base 状态暂不可用" description={graphError} /> : <><KnowledgeSummaryCards graph={graph} /><KnowledgeStatusPanel graph={graph} onSelectNode={(node) => { setSelectedGraphId(node?.id); setActiveTab("graph"); }} /></>;

  return (
    <div className="fm-page fm-knowledge-center">
      <div className="fm-page-header fm-knowledge-center__header">
        <Flex justify="space-between" align="flex-start" gap={16} wrap>
          <div><Text className="fm-eyebrow">KNOWLEDGE INTELLIGENCE CENTER</Text><Title level={2}>Knowledge Intelligence Center</Title><Paragraph>农业知识、证据关系、RAG 检索与专家反馈中心</Paragraph></div>
          <Flex align="center" gap={8} wrap><ModeTag mode={model.mode} />{knowledgeProfile && <Tag color="green">{displayFarmName} · {knowledgeProfile.corpusFolder}</Tag>}<Button icon={<RobotOutlined />} onClick={openReviewCopilot}>Ask Copilot</Button></Flex>
        </Flex>
        {(isDemo || graph.status === "DEGRADED") && <Alert type="warning" showIcon title={isDemo ? "演示模式：知识图谱与专家写入均已关闭。" : "知识图谱部分能力暂不可用。"} />}
      </div>
      {notice && <Alert closable onClose={() => setNotice(undefined)} type={notice.includes("失败") || notice.includes("不可用") ? "error" : "success"} title={notice} />}
      <Tabs className="fm-knowledge-center__tabs" activeKey={activeTab} onChange={setActiveTab} items={[
        { key: "graph", label: <span><ShareAltOutlined /> 知识图谱</span>, children: graphBody },
        { key: "status", label: "知识库状态", children: statusBody },
        { key: "review", label: "专家复核", children: <ExpertReviewPanel isDemo={isDemo} model={model} loading={loading} selectedId={selectedId} selected={selected} expertDiagnosis={expertDiagnosis} saving={saving} graphNodes={graph.nodes} onSelect={setSelectedId} onDiagnosisChange={setExpertDiagnosis} onSave={(decision) => void saveReview(decision)} onFocusKnowledge={focusKnowledge} /> },
        { key: "history", label: "历史案例", children: <HistoricalCasesPanel graph={graph} /> },
      ]} />
    </div>
  );
}

import { useEffect, useState } from "react";
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CalendarOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  FileAddOutlined,
  FileSearchOutlined,
  FolderAddOutlined,
  MoreOutlined,
  PaperClipOutlined,
  SendOutlined,
  ToolOutlined,
} from "@ant-design/icons";
import { Button, Card, Flex, Space, Tabs, Tag, Typography } from "antd";
import { useNavigate } from "react-router";
import { demoAgentTools, demoDiagnosis, demoEvidence, demoFarm, demoFarmLocations, demoMapCrops, demoPrimaryField, demoRecommendations, demoWeather } from "@/demo";
import MapContainer from "@/views/dashboard/Map";
import { useAIContextStore } from "@/store/aiContext";
import FieldContextCard from "@/components/commercial/FieldContextCard";
import StatusBadge from "@/components/commercial/StatusBadge";
import EvidenceCard from "@/components/commercial/EvidenceCard";
import RecommendationCard from "@/components/commercial/RecommendationCard";

const { Text, Title } = Typography;

export default function DiagnosisDetail() {
  const navigate = useNavigate();
  const setContext = useAIContextStore((state) => state.setContext);
  const [activeTab, setActiveTab] = useState("report");
  const [reviewSubmitted, setReviewSubmitted] = useState(false);

  useEffect(() => {
    setContext({
      currentFarm: { id: demoFarm.id, name: demoFarm.name, address: demoFarm.address },
      currentField: { id: demoPrimaryField.id, name: demoPrimaryField.name, area: demoPrimaryField.area, crop: demoPrimaryField.crop },
      currentCrop: { name: demoPrimaryField.crop, variety: demoPrimaryField.variety, growthStage: demoPrimaryField.growthStage },
      currentWeather: { summary: demoWeather.summary, temperature: demoWeather.temperature, precipitation: `未来 24 小时 ${demoWeather.rainfallForecast}`, updatedAt: demoWeather.updatedAt },
      currentPhenology: { stage: demoPrimaryField.growthStage, progress: demoPrimaryField.phenologyProgress },
      selectedDiagnosis: { id: demoDiagnosis.id, title: demoDiagnosis.title, severity: demoDiagnosis.severity, confidence: demoDiagnosis.confidence, summary: demoDiagnosis.summary },
    });
  }, [setContext]);

  const reportContent = (
    <div className="fm-diagnosis-report">
      <Card className="fm-conclusion-card" bordered={false}>
        <Flex align="flex-start" gap={16}>
          <div className="fm-conclusion-card__icon">⌁</div>
          <div className="fm-conclusion-card__main"><Flex align="center" gap={10} wrap><Title level={4}>{demoDiagnosis.title}</Title><StatusBadge tone="risk" /></Flex><Text type="secondary">{demoDiagnosis.summary}</Text><div className="fm-confidence"><Flex justify="space-between"><span>置信度</span><b>{demoDiagnosis.confidence}%</b></Flex><span className="fm-confidence__track"><i style={{ width: `${demoDiagnosis.confidence}%` }} /></span></div></div>
          <div className="fm-alternatives"><b>其他可能性</b>{demoDiagnosis.alternatives.map((item) => <div key={item.label}><span>{item.label}</span><strong>{item.value}%</strong></div>)}</div>
        </Flex>
      </Card>

      <Card className="fm-evidence-section" bordered={false} title={<span><FileSearchOutlined /> 核心证据</span>} extra={<Button type="link" size="small" onClick={() => setActiveTab("evidence")}>按相关性排序 <ArrowRightOutlined /></Button>}>
        <div className="fm-evidence-filter"><Button type="primary" size="small">全部 {demoDiagnosis.evidenceCount}</Button>{["监控图像 8", "气象数据 6", "农业知识 12", "历史案例 4", "空间证据 4"].map((item) => <Button key={item} size="small">{item}</Button>)}</div>
        <div className="fm-evidence-grid">{demoEvidence.slice(0, 5).map((evidence, index) => <EvidenceCard key={evidence.id} evidence={evidence} index={index} />)}</div>
        <div className="fm-evidence-relations"><Flex justify="space-between" align="center"><Title level={5}>证据与结论的对应关系</Title><Button type="link" size="small">查看全部证据 <ArrowRightOutlined /></Button></Flex>{demoEvidence.slice(0, 4).map((evidence, index) => <button key={evidence.id} onClick={() => setActiveTab("evidence")}><span className="fm-relation-number">{index + 1}</span><span>{evidence.claim}</span><Tag>{evidence.category} [{index + 1}]</Tag><ArrowRightOutlined /></button>)}</div>
      </Card>

      <details className="fm-agent-trace"><summary><span className="fm-agent-trace__check"><CheckCircleFilled /></span><span><b>AI 分析过程（Agent Trace）</b><small>分析完成 · 使用 7 个工具 · 检索 {demoDiagnosis.evidenceCount} 条证据 · 耗时 2.34 s</small></span><span className="fm-agent-trace__action">查看运行详情⌄</span></summary><div className="fm-agent-trace__detail">GIS Tool、Camera Tool、Weather Tool、Knowledge Retriever、Case Retriever、Analysis Tool、Task Tool 已完成调用。</div></details>
    </div>
  );

  const evidenceContent = <Card bordered={false} title="证据链" className="fm-tab-card"><div className="fm-evidence-grid fm-evidence-grid--expanded">{demoEvidence.map((evidence, index) => <EvidenceCard key={evidence.id} evidence={evidence} index={index} />)}</div></Card>;
  const technicalContent = <Card bordered={false} title="技术详情" className="fm-tab-card"><div className="fm-technical-list">{demoAgentTools.map((tool, index) => <div key={tool.id}><span>{index + 1}</span><b>{tool.name}</b><StatusBadge tone="completed" label="已完成" /></div>)}</div></Card>;

  return (
    <div className="fm-page fm-diagnosis-page">
      <Flex className="fm-breadcrumb" align="center" gap={6}><Button type="link" size="small" icon={<ArrowLeftOutlined />} onClick={() => navigate("/overview")}>AI 农情诊断</Button><span>/</span><Text type="secondary">A-12 地块</Text></Flex>
      <Flex className="fm-page-header fm-diagnosis-header" justify="space-between" align="flex-start" gap={16} wrap><div><Flex align="center" gap={12} wrap><Title level={2}>A-12 地块诊断</Title><StatusBadge tone="completed" label="诊断完成" /><Tag icon={<CalendarOutlined />}>{demoDiagnosis.createdAt}</Tag><Tag icon={<PaperClipOutlined />}>{demoDiagnosis.sourceCount} 类数据源</Tag><Tag icon={<FileSearchOutlined />}>{demoDiagnosis.evidenceCount} 条证据</Tag><Tag color={reviewSubmitted ? "green" : "orange"} icon={<ClockCircleOutlined />}>{reviewSubmitted ? "已提交复核" : "专家待复核"}</Tag></Flex><Text type="secondary">基于卫星影像、监控图像、气象数据、土壤数据和农业知识的多模态 AI 诊断。</Text></div><Space><Button type={activeTab === "report" ? "primary" : "default"} onClick={() => setActiveTab("report")}>诊断报告</Button><Button type={activeTab === "evidence" ? "primary" : "default"} onClick={() => setActiveTab("evidence")}>证据</Button><Button type={activeTab === "technical" ? "primary" : "default"} onClick={() => setActiveTab("technical")}>技术详情</Button><Button type="primary" icon={<FileAddOutlined />}>生成报告</Button><Button icon={<MoreOutlined />} aria-label="更多操作" /></Space></Flex>

      <div className="fm-diagnosis-grid">
        <aside className="fm-diagnosis-left"><Card bordered={false} title="地块基本信息" extra={<Button type="link" size="small">编辑</Button>}><div className="fm-diagnosis-map"><MapContainer center={demoFarm.coordinates} zoom={13} crops={demoMapCrops} farmLocations={demoFarmLocations} infoKey="growth" modeKey="crop" slider={{ value: { left: 0, right: 100 }, scale: { min: 0, max: 100 }, decimal: false }} readOnly /><div className="fm-diagnosis-map__label">A-12</div></div><FieldContextCard field={demoPrimaryField} /><div className="fm-input-card"><Title level={5}>多模态输入数据</Title>{[["监控图像", "8 张", "fm-input-icon--blue"], ["卫星影像", "2 景", "fm-input-icon--green"], ["气象数据", "14 天", "fm-input-icon--sky"], ["土壤数据", "1 组", "fm-input-icon--dark"], ["历史诊断", "3 条", "fm-input-icon--cyan"]].map(([label, value, tone]) => <div key={label}><span className={`fm-input-icon ${tone}`}><PaperClipOutlined /></span><b>{label}</b><small>{value}</small><ArrowRightOutlined /></div>)}</div></Card></aside>
        <main className="fm-diagnosis-center"><Tabs activeKey={activeTab} onChange={setActiveTab} items={[{ key: "report", label: "诊断报告", children: reportContent }, { key: "evidence", label: "证据", children: evidenceContent }, { key: "technical", label: "技术详情", children: technicalContent }]} /></main>
        <aside className="fm-diagnosis-right"><Card bordered={false} title="专家复核" extra={<StatusBadge tone={reviewSubmitted ? "completed" : "pending"} label={reviewSubmitted ? "已提交" : "待复核"} />}><Text type="secondary">建议由农业专家对诊断结果进行复核，以提高结论可信度。</Text><div className="fm-review-status"><div className="fm-review-avatar">◌</div><div><small>当前状态</small><b>{reviewSubmitted ? "已提交专家复核" : "待专家复核"}</b></div></div><Button block type="primary" icon={<SendOutlined />} onClick={() => setReviewSubmitted(true)} disabled={reviewSubmitted}>提交专家复核</Button></Card><Card bordered={false} title="农事建议" extra={<Text type="secondary">基于诊断结果生成</Text>}>{demoRecommendations.map((item) => <RecommendationCard key={item.title} {...item} />)}</Card><Card bordered={false} title="相关操作"><div className="fm-action-grid"><Button icon={<FileAddOutlined />}>生成完整诊断报告</Button><Button icon={<CalendarOutlined />}>创建巡检任务</Button><Button icon={<FolderAddOutlined />}>加入历史案例库</Button></div><Button className="fm-deep-analysis" block icon={<ToolOutlined />} onClick={() => navigate(`/analysis/${demoDiagnosis.id}`)}>查看 Agent 深度分析 <ArrowRightOutlined /></Button></Card></aside>
      </div>
    </div>
  );
}

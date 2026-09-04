import { useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CheckCircleFilled,
  CheckOutlined,
  ClockCircleOutlined,
  CloudOutlined,
  DatabaseOutlined,
  FileSearchOutlined,
  PictureOutlined,
  PlayCircleFilled,
  ReloadOutlined,
  SettingOutlined,
  ToolOutlined,
} from "@ant-design/icons";
import { Button, Card, Flex, Space, Tabs, Tag, Typography } from "antd";
import { useNavigate } from "react-router";
import {
  demoAgentSteps,
  demoAgentTools,
  demoDiagnosis,
  demoEvidence,
  demoFarm,
  demoFarmLocations,
  demoLeafAssets,
  demoMapCrops,
  demoPrimaryField,
  demoWeather,
} from "@/demo";
import MapContainer from "@/views/dashboard/Map";
import { useAIContextStore } from "@/store/aiContext";
import FieldContextCard from "@/components/commercial/FieldContextCard";
import StatusBadge from "@/components/commercial/StatusBadge";

const { Text, Title } = Typography;
type RunStatus = "pending" | "running" | "completed" | "failed";
type ToolTone = "pending" | "running" | "completed";

const formatElapsed = (seconds: number) => `${Math.floor(seconds / 60)}m${String(seconds % 60).padStart(2, "0")}s`;

function getToolTones(runStatus: RunStatus, currentStep: number): ToolTone[] {
  if (runStatus === "completed") return demoAgentTools.map(() => "completed");
  if (runStatus !== "running") return demoAgentTools.map(() => "pending");

  const tones = demoAgentTools.map(() => "pending" as ToolTone);
  const complete = (indexes: number[]) => indexes.forEach((index) => { tones[index] = "completed"; });
  if (currentStep === 0) tones[0] = "running";
  if (currentStep === 1) { complete([0]); tones[1] = "running"; }
  if (currentStep === 2) { complete([0, 1]); tones[2] = "running"; }
  if (currentStep === 3) { complete([0, 1, 2]); tones[3] = "running"; }
  if (currentStep >= 4 && currentStep <= 5) { complete([0, 1, 2, 4]); tones[3] = "running"; }
  if (currentStep === 6) { complete([0, 1, 2, 3, 4]); tones[5] = "running"; }
  if (currentStep >= 7) complete([0, 1, 2, 3, 4, 5, 6]);
  return tones;
}

function StatusLabel({ tone }: { tone: ToolTone }) {
  return <StatusBadge tone={tone} label={tone === "completed" ? "已完成" : tone === "running" ? "运行中" : "等待中"} />;
}

export default function AgentWorkspace() {
  const navigate = useNavigate();
  const setContext = useAIContextStore((state) => state.setContext);
  const [runStatus, setRunStatus] = useState<RunStatus>("pending");
  const [currentStep, setCurrentStep] = useState(-1);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [activeTab, setActiveTab] = useState("conclusion");
  const [background, setBackground] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const elapsedRef = useRef(0);

  useEffect(() => {
    setContext({
      currentFarm: { id: demoFarm.id, name: demoFarm.name, address: demoFarm.address },
      currentField: { id: demoPrimaryField.id, name: demoPrimaryField.name, area: demoPrimaryField.area, crop: demoPrimaryField.crop },
      currentCrop: { name: demoPrimaryField.crop, variety: demoPrimaryField.variety, growthStage: demoPrimaryField.growthStage },
      currentWeather: { summary: demoWeather.summary, temperature: demoWeather.temperature, precipitation: `未来 24 小时 ${demoWeather.rainfallForecast}`, updatedAt: demoWeather.updatedAt },
      currentPhenology: { stage: demoPrimaryField.growthStage, progress: demoPrimaryField.phenologyProgress },
      selectedDiagnosis: { id: demoDiagnosis.id, title: demoDiagnosis.title, severity: demoDiagnosis.severity, confidence: demoDiagnosis.confidence, summary: demoDiagnosis.summary },
      currentPage: "analysis",
    });
  }, [setContext]);

  useEffect(() => () => {
    if (timerRef.current) clearInterval(timerRef.current);
  }, []);

  const startRun = () => {
    if (timerRef.current) clearInterval(timerRef.current);
    elapsedRef.current = 0;
    setElapsedSeconds(0);
    setRunStatus("running");
    setCurrentStep(0);
    setBackground(false);
    timerRef.current = setInterval(() => {
      elapsedRef.current += 1;
      setElapsedSeconds(elapsedRef.current);
      if (elapsedRef.current % 2 === 0) {
        setCurrentStep((step) => {
          if (step >= demoAgentSteps.length - 1) {
            if (timerRef.current) clearInterval(timerRef.current);
            setRunStatus("completed");
            return demoAgentSteps.length - 1;
          }
          return step + 1;
        });
      }
    }, 1000);
  };

  const toolTones = useMemo(() => getToolTones(runStatus, currentStep), [currentStep, runStatus]);
  const completedToolCount = toolTones.filter((tone) => tone !== "pending").length;
  const activeStepTitle = currentStep >= 0 ? demoAgentSteps[currentStep]?.title : "准备分析";
  const historicalCase = demoEvidence.find((evidence) => evidence.category === "历史案例");

  const idleContent = (
    <div className="fm-agent-idle">
      <Flex justify="space-between" align="flex-start" gap={10}>
        <div className="fm-agent-idle__title"><span className="fm-ai-mark">AI</span><div><Title level={4}>FarMap Agent</Title><Text type="secondary">准备协助你完成一次农情诊断</Text></div></div>
        <StatusBadge tone="completed" label="Ready" />
      </Flex>
      <div className="fm-agent-idle__section"><Text type="secondary">Current Context</Text><div className="fm-context-chips"><Tag>A-12</Tag><Tag>柑橘</Tag><Tag>果实膨大期</Tag></div></div>
      <div className="fm-agent-idle__section"><Text type="secondary">Available Context</Text><div className="fm-context-list"><div><span><DatabaseOutlined /> GIS</span><b>Ready</b></div><div><span><PictureOutlined /> Camera</span><b>8 images</b></div><div><span><CloudOutlined /> Weather</span><b>14 days</b></div><div><span><FileSearchOutlined /> Knowledge</span><b>12 sources</b></div><div><span><ClockCircleOutlined /> History</span><b>3 cases</b></div></div></div>
      <div className="fm-agent-idle__estimate"><div><b>7</b><small>Tools</small></div><div><b>5</b><small>Data Sources</small></div><span>预计执行</span></div>
      <Button type="primary" block icon={<PlayCircleFilled />} onClick={startRun}>开始分析</Button>
    </div>
  );

  const conclusionContent = runStatus === "pending" ? idleContent : (
    <div className="fm-agent-output">
      <Flex align="center" gap={12}><span className="fm-ai-mark">AI</span><div><Title level={4}>FarMap Agent</Title><StatusBadge tone={runStatus === "running" ? "running" : "completed"} label={runStatus === "running" ? "运行中" : "已完成"} /></div></Flex>
      <div className="fm-agent-output__message"><p>{runStatus === "running" ? `正在执行：${activeStepTitle}` : "已完成 A-12 地块叶片黄化的多模态分析。"}</p><p>已获取并检索监控图像、气象数据、土壤指标、农业知识和历史案例。</p><b>当前活动：</b><ul><li>叶片存在明显黄化特征</li><li>近 14 天有持续降雨，土壤湿度处于较高水平</li><li>与 B-07 历史案例高度相似</li></ul>{runStatus === "running" && <Text type="secondary">正在综合证据并准备下一步输出。</Text>}</div>
    </div>
  );

  const evidencePreview = <div className="fm-agent-evidence"><Title level={5}>证据预览</Title>{["监控图像 · 叶片黄化", "气象数据 · 近 14 日降雨 58 mm", "土壤数据 · 湿度 84%", "历史案例 · B-07 相似度较高"].map((item, index) => <div key={item}><span>{index + 1}</span><b>{item}</b><ArrowRightOutlined /></div>)}</div>;
  const dataView = <div className="fm-agent-data"><Title level={5}>已注入任务的数据上下文</Title>{[["农场", demoFarm.name], ["地块", `${demoPrimaryField.name} · ${demoPrimaryField.area} ha`], ["作物", `${demoPrimaryField.crop} · ${demoPrimaryField.growthStage}`], ["健康度", `${demoPrimaryField.health}/100`], ["NDVI / 土壤湿度", `${demoPrimaryField.ndvi.toFixed(2)} / ${demoPrimaryField.soilMoisture}%`], ["当前天气", `${demoWeather.temperature} · ${demoWeather.summary}`]].map(([label, value]) => <div key={label}><span>{label}</span><b>{value}</b></div>)}</div>;
  const recordsContent = <div className="fm-run-records"><Title level={5}>运行记录</Title><div><CheckCircleFilled /> 当前任务已创建 <small>刚刚</small></div><div><ClockCircleOutlined /> {runStatus === "running" ? `正在执行第 ${Math.max(1, currentStep + 1)} 步` : "分析任务完成"} <small>Demo Run</small></div><div><ToolOutlined /> 使用 {completedToolCount}/7 个工具 <small>只读演示</small></div></div>;

  return (
    <div className="fm-page fm-agent-page">
      <Flex className="fm-page-header" justify="space-between" align="flex-end" gap={20} wrap><div><div className="fm-eyebrow">MULTIMODAL AGENT / EXECUTION</div><Title level={2}>Agent 工作空间</Title><Text type="secondary">让 FarMap Agent 在你的农场数据上检索、分析并生成可执行任务。</Text></div><Space><Button icon={<ReloadOutlined />}>历史任务</Button><Button icon={<SettingOutlined />}>Agent 设置</Button><Button type="primary" icon={<PlayCircleFilled />} onClick={startRun}>{runStatus === "running" ? "分析进行中" : "运行新的分析"}</Button></Space></Flex>
      <div className="fm-agent-grid">
        <aside className="fm-agent-context"><Card bordered={false} title="任务上下文" extra={<Button size="small">更换地块</Button>}><div className="fm-agent-map"><MapContainer center={demoFarm.coordinates} zoom={13} crops={demoMapCrops} farmLocations={demoFarmLocations} infoKey="growth" modeKey="crop" slider={{ value: { left: 0, right: 100 }, scale: { min: 0, max: 100 }, decimal: false }} readOnly /><span>A-12</span></div><FieldContextCard field={demoPrimaryField} compact /><div className="fm-available-data"><Title level={5}>可用数据源</Title>{[["监控图像", "8 张"], ["卫星影像", "2 景"], ["气象数据", "14 天"], ["土壤数据", "1 组"], ["历史诊断", "3 条"], ["农业知识库", "12 条"]].map(([label, value]) => <div key={label}><FileSearchOutlined /><b>{label}</b><small>{value}</small></div>)}</div></Card></aside>
        <main className="fm-agent-center"><Card className="fm-task-goal" bordered={false}><Title level={4}>任务目标</Title><Flex gap={14}><div className="fm-task-goal__input">分析 A-12 地块叶片黄化的原因，并生成处理方案</div><Button type="primary" icon={<PlayCircleFilled />} onClick={startRun}>开始运行</Button></Flex><div className="fm-task-tags"><Tag>地块诊断</Tag><Tag>风险评估</Tag><Tag>处理方案</Tag><Tag>相似案例检索</Tag><Tag>专家建议</Tag></div></Card><Card className="fm-run-card" bordered={false} title={<Flex justify="space-between" align="center"><span>Agent 运行过程</span><span className="fm-run-state"><i className={runStatus === "running" ? "is-running" : ""} />{runStatus === "pending" ? "等待运行" : runStatus === "running" ? `运行中 · ${formatElapsed(elapsedSeconds)}` : "运行完成"}</span></Flex>}><div className="fm-agent-steps">{demoAgentSteps.map((step, index) => { const tone = runStatus === "completed" || index < currentStep ? "completed" : runStatus === "running" && index === currentStep ? "running" : "pending"; return <div className={`fm-agent-step-group fm-agent-step-group--${tone}`} key={step.id}><div className="fm-agent-step"><span className="fm-agent-step__number">{tone === "completed" ? <CheckCircleFilled /> : index + 1}</span><div><small>STEP {String(index + 1).padStart(2, "0")}</small><b>{step.title}</b><span>{step.description}</span></div><em>{tone === "running" ? "运行中" : tone === "pending" ? "等待中" : step.duration}</em><ArrowRightOutlined /></div>{tone === "running" && <div className="fm-agent-step__workspace"><Flex justify="space-between" align="flex-start" gap={14} wrap><div><div className="fm-agent-workspace__eyebrow">LIVE ACTIVITY</div><Title level={4}>正在分析证据...</Title></div><StatusBadge tone="running" label="多模态分析" /></Flex><div className="fm-agent-checklist"><div className="is-done"><CheckOutlined /> 分析监控图像中的叶片特征</div><div className="is-done"><CheckOutlined /> 分析气象数据</div><div className="is-done"><CheckOutlined /> 对比历史案例</div><div className="is-active"><i /> 综合农业知识</div><div className="is-pending"><i /> 生成诊断结论</div></div><div className="fm-agent-evidence-grid"><div className="fm-agent-evidence-panel fm-agent-evidence-panel--visual"><Flex justify="space-between"><b>Visual Evidence</b><small>3 images</small></Flex><div className="fm-agent-evidence-images">{demoLeafAssets.map((asset, imageIndex) => <div key={asset}><img src={asset} alt={`叶片证据 ${imageIndex + 1}`} /><span>{["Camera-03", "Camera-06", "Camera-08"][imageIndex]}</span></div>)}</div></div><div className="fm-agent-evidence-panel fm-agent-evidence-panel--weather"><Flex justify="space-between"><b>Weather Evidence</b><small>14 days</small></Flex><div className="fm-agent-weather-chart"><span><i style={{ height: "26%" }} /><i style={{ height: "38%" }} /><i style={{ height: "31%" }} /><i style={{ height: "66%" }} /><i style={{ height: "48%" }} /><i style={{ height: "82%" }} /><i style={{ height: "58%" }} /></span><strong>58 mm</strong></div><small>累计降雨 · 过去 14 天</small></div><div className="fm-agent-evidence-panel fm-agent-evidence-panel--cases"><Flex justify="space-between"><b>Similar Cases</b><small>3 cases</small></Flex><div className="fm-similar-cases">{[["B-07", "2025-07-12", historicalCase?.asset], ["C-03", "2025-06-18"], ["A-08", "2024-08-09"]].map(([field, date, asset]) => <div key={field}>{asset ? <img src={asset} alt={`${field} 历史案例`} /> : <span className="fm-case-placeholder">↗</span>}<span><b>{field}</b><small>{date}</small></span><Tag>相似</Tag></div>)}</div></div></div></div>}</div>; })}</div></Card></main>
        <aside className="fm-agent-output-panel"><Card bordered={false}><Tabs activeKey={activeTab} onChange={setActiveTab} items={[{ key: "conclusion", label: "分析结论", children: conclusionContent }, { key: "evidence", label: "证据预览", children: evidencePreview }, { key: "data", label: "数据视图", children: dataView }, { key: "records", label: "运行记录", children: recordsContent }]} /><div className="fm-tool-runs"><Flex justify="space-between" align="center"><Title level={5}>当前使用的工具</Title><Text type="secondary">{completedToolCount}/7</Text></Flex>{demoAgentTools.map((tool, index) => <div key={tool.id}><span className={`fm-tool-run__icon fm-tool-run__icon--${toolTones[index]}`}><ToolOutlined /></span><span><b>{tool.name}</b><small>{tool.description}</small></span><StatusLabel tone={toolTones[index]} /></div>)}</div><div className="fm-run-summary"><div><b>{completedToolCount}/7</b><small>已使用工具</small></div><div><b>{runStatus === "pending" ? "—" : "31"}</b><small>检索证据</small></div><div><b>{runStatus === "pending" ? "—" : formatElapsed(elapsedSeconds || 2)}</b><small>运行时间</small></div></div></Card></aside>
      </div>
      <Flex className="fm-agent-footer" justify="space-between" align="center"><Text type="secondary"><CloudOutlined /> Demo Agent Run · 数据仅用于产品演示，不会调用真实设备</Text><Space><Button onClick={() => setBackground(true)} disabled={runStatus !== "running"}>在后台继续</Button><Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate("/overview")}>返回工作台</Button></Space></Flex>
      {background && <div className="fm-background-toast"><CheckCircleFilled /> Agent 已在后台继续运行 <Button type="link" size="small" onClick={() => setBackground(false)}>知道了</Button></div>}
    </div>
  );
}

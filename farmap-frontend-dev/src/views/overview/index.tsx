import { useCallback, useEffect } from "react";
import {
  ArrowRightOutlined,
  CameraOutlined,
  CloudOutlined,
  EnvironmentOutlined,
  ExperimentOutlined,
  HomeOutlined,
  InboxOutlined,
  MenuOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { Button, Card, Flex, Space, Tag, Typography } from "antd";
import { useNavigate } from "react-router";
import { demoDiagnosis, demoFarm, demoFields, demoFarmLocations, demoInsights, demoMapCrops, demoPrimaryField, demoWeather } from "@/demo";
import MapContainer from "@/views/dashboard/Map";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import StatusBadge from "@/components/commercial/StatusBadge";
import MetricItem from "@/components/commercial/MetricItem";

const { Text, Title } = Typography;

export default function Overview() {
  const navigate = useNavigate();
  const openCopilot = useCopilotStore((state) => state.open);
  const setContext = useAIContextStore((state) => state.setContext);

  const setPrimaryFieldContext = useCallback(() => {
    setContext({
      currentFarm: { id: demoFarm.id, name: demoFarm.name, address: demoFarm.address },
      currentField: { id: demoPrimaryField.id, name: demoPrimaryField.name, area: demoPrimaryField.area, crop: demoPrimaryField.crop },
      currentCrop: { name: demoPrimaryField.crop, variety: demoPrimaryField.variety, growthStage: demoPrimaryField.growthStage },
      currentWeather: { summary: demoWeather.summary, temperature: demoWeather.temperature, precipitation: `未来 24 小时 ${demoWeather.rainfallForecast}`, updatedAt: demoWeather.updatedAt },
      currentPhenology: { stage: demoPrimaryField.growthStage, progress: demoPrimaryField.phenologyProgress },
      selectedDiagnosis: { id: demoDiagnosis.id, title: demoDiagnosis.title, severity: demoDiagnosis.severity, confidence: demoDiagnosis.confidence, summary: demoDiagnosis.summary },
      currentLocation: { label: "A-12 地块中心", latitude: 30.051, longitude: 103.832 },
      selectedDevice: { id: "CAM-A12-03", name: "Camera-03", status: "online", location: "A-12 东侧" },
      selectedImages: [],
      currentPage: "/overview",
    });
  }, [setContext]);

  useEffect(() => {
    setPrimaryFieldContext();
  }, [setPrimaryFieldContext]);

  const openDiagnosis = () => {
    setPrimaryFieldContext();
    navigate(`/diagnosis/${demoDiagnosis.id}`);
  };

  return (
    <div className="fm-page fm-overview-page">
      <Flex className="fm-page-header" justify="space-between" align="flex-end" gap={20} wrap>
        <div><div className="fm-eyebrow">SMART FARM / OVERVIEW</div><Title level={2}>农场智能总览</Title><Text type="secondary">基于卫星影像、气象数据、监控画面和 AI 分析，为你提供实时农情洞察与决策建议。</Text></div>
      </Flex>

      <div className="fm-metric-row">
        <MetricItem label="农场健康度" value={demoFarm.health} unit="/100" trend="↑ +6%" icon={<SafetyCertificateOutlined />} />
        <MetricItem label="地块总数" value={demoFarm.fieldCount} unit="块" icon={<EnvironmentOutlined />} />
        <MetricItem label="种植面积" value={demoFarm.acreage} unit="ha" icon={<InboxOutlined />} />
        <MetricItem label="设备在线" value={demoFarm.devicesOnline} unit={`/${demoFarm.devicesTotal}`} icon={<CameraOutlined />} />
      </div>

      <div className="fm-overview-main-grid">
        <Card className="fm-map-card" bordered={false}>
          <Flex className="fm-section-heading" justify="space-between" align="center"><div><Title level={4}>农场空间态势</Title><Text type="secondary">卫星影像 · 地块边界 · 风险分布</Text></div><Space size={6}><Button size="small" type="text">卫星影像</Button><Button size="small" type="text">NDVI</Button><Button size="small" type="text">地块边界</Button></Space></Flex>
          <div className="fm-map-frame">
            <MapContainer center={demoFarm.coordinates} zoom={13} crops={demoMapCrops} farmLocations={demoFarmLocations} infoKey="growth" modeKey="crop" slider={{ value: { left: 0, right: 100 }, scale: { min: 0, max: 100 }, decimal: false }} readOnly />
            <div className="fm-map-frame__toolbar"><Button size="small" icon={<MenuOutlined />}>图层</Button><Button size="small" icon={<EnvironmentOutlined />}>标注</Button><Button size="small" icon={<ThunderboltOutlined />}>测距</Button></div>
            <div className="fm-map-frame__legend"><span><i className="fm-dot fm-dot--green" />健康</span><span><i className="fm-dot fm-dot--yellow" />需关注</span><span><i className="fm-dot fm-dot--red" />风险</span></div>
            <div className="fm-map-frame__field-note"><Flex justify="space-between" align="center"><div><b>{demoPrimaryField.id} 地块</b><small>{demoPrimaryField.crop} · {demoPrimaryField.area} ha</small></div><StatusBadge tone="risk" /></Flex><div className="fm-field-note__facts"><span>NDVI <b>{demoPrimaryField.ndvi.toFixed(2)}</b></span><span>土壤湿度 <b>{demoPrimaryField.soilMoisture}%</b></span></div><Button block className="fm-button-dark" onClick={openDiagnosis}>查看地块诊断 <ArrowRightOutlined /></Button></div>
          </div>
        </Card>

        <Card className="fm-intelligence-card" bordered={false}>
          <Flex justify="space-between" align="center"><div><div className="fm-eyebrow">AI INSIGHT</div><Title level={4}>FarMap Intelligence</Title></div><span className="fm-ai-mark">AI</span></Flex>
          <div className="fm-intelligence-summary">基于当前农场数据，我发现 <b>3 个</b>值得关注的问题。</div>
          <div className="fm-risk-list">
            <button className="fm-risk-item fm-risk-item--danger" onClick={openDiagnosis}><span className="fm-risk-item__icon"><WarningOutlined /></span><span><b>{demoInsights[0].title}</b><small>检测到叶片黄化，可能与近期持续降雨导致的根区积水有关。</small><em>{demoInsights[0].confidence} · {demoInsights[0].basis} · Evidence {demoInsights[0].evidenceCount}</em></span><ArrowRightOutlined /></button>
            <button className="fm-risk-item" onClick={() => navigate("/farm/map")}><span className="fm-risk-item__icon fm-risk-item__icon--blue"><CloudOutlined /></span><span><b>{demoInsights[1].title}</b><small>预计降雨 58–76 mm，4 个地块可能受影响。</small><em>{demoInsights[1].confidence} · {demoInsights[1].basis} · Evidence {demoInsights[1].evidenceCount}</em></span><ArrowRightOutlined /></button>
            <button className="fm-risk-item" onClick={() => navigate("/operations/tasks")}><span className="fm-risk-item__icon fm-risk-item__icon--green"><ExperimentOutlined /></span><span><b>{demoInsights[2].title}</b><small>果实成熟度达到采收标准，建议 7 天内完成采收。</small><em>{demoInsights[2].confidence} · {demoInsights[2].basis} · Evidence {demoInsights[2].evidenceCount}</em></span><ArrowRightOutlined /></button>
          </div>
          <Button className="fm-intelligence-cta" block onClick={() => navigate(`/analysis/${demoDiagnosis.id}`)} icon={<RobotOutlined />}>让 Agent 深度分析当前农场 <ArrowRightOutlined /></Button>
          <div className="fm-ask-list"><Text type="secondary">你可以这样问：</Text>{["分析 A-12 地块黄化的原因", "生成未来一周的农事建议", "查看所有风险地块"].map((item) => <button key={item} onClick={openCopilot}><span />{item}</button>)}</div>
        </Card>
      </div>

      <div className="fm-overview-bottom-grid">
        <Card bordered={false} title="NDVI 长势趋势" extra={<Button type="link" size="small">近 1 个月</Button>}><div className="fm-chart-value"><b>0.72</b><span>↓ -8%</span></div><div className="fm-sparkline" aria-label="NDVI 近一个月趋势"><i style={{ height: "32%" }} /><i style={{ height: "43%" }} /><i style={{ height: "47%" }} /><i style={{ height: "54%" }} /><i style={{ height: "61%" }} /><i style={{ height: "56%" }} /><i style={{ height: "68%" }} /><i style={{ height: "58%" }} /><i style={{ height: "72%" }} /><i style={{ height: "64%" }} /><i style={{ height: "55%" }} /></div><Flex justify="space-between" className="fm-chart-axis"><span>7/1</span><span>7/15</span><span>8/1</span><span>8/15</span><span>9/1</span></Flex></Card>
        <Card bordered={false} title="天气预报" extra={<Button type="link" size="small">查看详情 <ArrowRightOutlined /></Button>}><Text type="secondary">{demoWeather.location}</Text><div className="fm-weather-strip">{demoWeather.days.map((day) => <div key={day.day}><span>{day.icon === "sun" ? "☀" : day.icon === "rain" ? "☔" : "☁"}</span><small>{day.day}</small><b>{day.high}°</b><em>{day.low}°</em></div>)}</div><div className="fm-weather-alert"><CloudOutlined /><span>未来 24h 降雨量 <b>{demoWeather.rainfallForecast}</b></span><Tag color="error">4 个地块可能受影响</Tag></div></Card>
        <Card bordered={false} title="监控画面" extra={<StatusBadge tone="healthy" label="在线" />}><Text type="secondary">Camera-03 · A-12 地块</Text><div className="fm-monitor-preview"><div className="fm-monitor-preview__scene"><span>CAMERA 03</span><b>叶片状态采集正常</b></div><small>2026-09-04 09:32:18</small></div><Button block>查看全部设备 <ArrowRightOutlined /></Button></Card>
        <Card bordered={false} title="今日农事任务" extra={<Text className="fm-warn-text">4 项待执行</Text>}><div className="fm-task-list">{[["A-12 排水沟检查", "09:30", "risk"], ["B-03 病虫害巡查", "10:00", "attention"], ["果园施肥（B-04）", "14:00", "attention"], ["设备例行检查", "16:30", "healthy"]].map(([name, time, tone]) => <div key={name}><span className={`fm-task-dot fm-task-dot--${tone}`} /><b>{name}</b><small>{time}</small></div>)}</div><Button type="link" size="small">查看全部任务 <ArrowRightOutlined /></Button></Card>
      </div>

      <div className="fm-overview-lower-grid">
        <Card bordered={false} title="最近 AI 诊断" extra={<Button type="link" size="small" onClick={openDiagnosis}>查看全部 <ArrowRightOutlined /></Button>}><div className="fm-diagnosis-list">{demoFields.slice(0, 3).map((field) => <button key={field.id} onClick={field.id === demoPrimaryField.id ? openDiagnosis : undefined}><span className={`fm-leaf-thumb fm-leaf-thumb--${field.status}`}><ExperimentOutlined /></span><span><b>{field.name} {field.statusLabel}</b><small><strong>{field.health}%</strong> 置信度 · {field.id === "A-12" ? "2026-09-03" : "2026-09-01"}</small></span><ArrowRightOutlined /></button>)}</div></Card>
        <Card bordered={false} title="农场动态" extra={<Button type="link" size="small">查看更多 <ArrowRightOutlined /></Button>}><div className="fm-activity-list"><div><i />09:32 <span>Camera-03 检测到异常叶片</span></div><div><i />08:15 <span>未来 24h 将有强降雨</span></div><div><i />06:40 <span>B-04 地块进入采收窗口</span></div></div></Card>
      </div>
      <div className="fm-overview-footer-note"><HomeOutlined /> {demoFarm.name} · {demoFarm.location} · 数据更新于 {demoFarm.lastUpdated}</div>
    </div>
  );
}
